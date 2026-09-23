package com.test.blog;

import java.net.URI;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import com.test.blog.tools.config.ToolsRequestSizeFilter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ToolsWithoutDatabaseTests {
    private static final String INVALID_MESSAGE = "请求参数无效，请检查后重试。";
    private static final String DECRYPTION_MESSAGE = "解密失败，请检查密钥和密文。";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${local.server.port}") int port;
    @Autowired ApplicationContext context;
    @Autowired JsonMapper json;

    @Test
    void startsWithoutDatabaseOrRetiredDependencies() {
        assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
        for (String name : new String[]{"org.apache.ibatis.session.SqlSession",
                "com.mysql.cj.jdbc.Driver", "com.zaxxer.hikari.HikariDataSource",
                "org.hibernate.Session", "org.springframework.cloud.config.client.ConfigClientProperties",
                "lombok.Data"}) {
            assertFalse(ClassUtils.isPresent(name, getClass().getClassLoader()), name);
        }
    }

    @Test
    void oversizedFieldsReturnSafe413() throws Exception {
        for (String path : new String[]{"/tools/encryption", "/tools/decryption"}) {
            assertTooLarge(post(path, "密".repeat(342), "synthetic-private-input"));
            int max = path.endsWith("encryption") ? 64 * 1024 : 128 * 1024;
            assertTooLarge(post(path, "key", "x".repeat(max + 1)));
        }
    }

    @Test
    void boundedBodiesWorkWithContentLengthAndChunkedTransfer() throws Exception {
        String valid = "{\"key\":\"key\",\"pwd\":\"text\"}";
        for (boolean chunked : new boolean[]{false, true}) {
            for (int offset : new int[]{-1, 0, 1}) {
                // Valid JSON followed by whitespace ensures ignored trailing bytes cannot bypass the limit.
                byte[] body = (valid + " ".repeat(ToolsRequestSizeFilter.MAX_BODY_BYTES + offset - valid.length()))
                        .getBytes(StandardCharsets.UTF_8);
                var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                        : HttpRequest.BodyPublishers.ofByteArray(body);
                assertEquals(chunked ? -1 : body.length, publisher.contentLength());
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/tools/encryption"))
                        .version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json").POST(publisher).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (offset > 0) assertTooLarge(response);
                else assertTextResponse(response);
            }
        }
        assertTooLarge(request("POST", "/tools;matrix=1/encryption", valid + " ".repeat(ToolsRequestSizeFilter.MAX_BODY_BYTES)));
    }

    @Test
    void maximumEscapedJsonAndCiphertextRoundTrip() throws Exception {
        String key = "\u0000".repeat(1024);
        String plain = "\u0000".repeat(64 * 1024);
        var encrypted = post("/tools/encryption", key, plain);
        assertTextResponse(encrypted);
        var decrypted = post("/tools/decryption", key, encrypted.body().replace("\n", "\r\n"));
        assertTextResponse(decrypted);
        assertEquals(plain, decrypted.body());
    }

    private void assertTooLarge(HttpResponse<String> response) {
        assertEquals(413, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        var error = json.readTree(response.body());
        assertEquals(2, error.size());
        assertEquals("INPUT_TOO_LARGE", error.get("code").asString());
        assertEquals("输入过大，请缩短密钥或文本后重试。", error.get("message").asString());
    }

    @Test
    void preservesConfiguredTomcatCapacity() {
        var server = (TomcatWebServer) ((ServletWebServerApplicationContext) context).getWebServer();
        var connector = server.getTomcat().getConnector();
        assertEquals("50", String.valueOf(connector.getProperty("maxThreads")));
        assertEquals("5", String.valueOf(connector.getProperty("minSpareThreads")));
        assertEquals("200", String.valueOf(connector.getProperty("maxConnections")));
    }

    @Test
    void databaseRoutesAreRetired() throws Exception {
        for (String path : new String[]{"/testuser/number", "/testuser/allTestUser",
                "/testuser/allTestUserVue", "/testuser/allTestUserVue/page/1",
                "/testuser/filterTestUser", "/testuser/1", "/testuser/pic"}) {
            assertEquals(404, request("GET", path, null).statusCode(), path);
        }
        assertEquals(404, request("POST", "/testuser/addTestUser", "{}").statusCode());
        assertEquals(404, request("DELETE", "/testuser/1", null).statusCode());
        assertEquals(404, request("PUT", "/testuser/updateTestUser/1", "{}").statusCode());
        assertEquals(404, request("POST", "/bbs/user", "{}").statusCode());
    }

    @Test
    void encryptionPreservesExactTextAndDoesNotCache() throws Exception {
        for (String plain : new String[]{"", "回归测试：hello 123！🔐", "123456789012345678901234567890",
                "\"保留引号\"", "null", "{\"message\":true}", "  保留空白\n\t  "}) {
            HttpResponse<String> encrypted = post("/tools/encryption", " 中文 key 🔑 ", plain);
            assertTextResponse(encrypted);
            assertFalse(encrypted.body().isEmpty());
            HttpResponse<String> decrypted = post("/tools/decryption", " 中文 key 🔑 ", encrypted.body());
            assertTextResponse(decrypted);
            assertEquals(plain, decrypted.body());
        }
    }

    @Test
    void decryptsFixtureFromOriginalImplementation() throws Exception {
        // Synthetic fixture produced by the original Java 8 implementation before this refactor.
        String fixture = "15F7PaVh2GRYdbvNLyLVHcMZVWyQHmnCA9FG48trWHtKcXGsSzhmehkO2j20yjP1";
        HttpResponse<String> response = post("/tools/decryption", "fixture-key", fixture);
        assertTextResponse(response);
        assertEquals("旧版兼容：hello 123！", response.body());
    }

    @Test
    void invalidJsonAndStringFieldsReturnSafeErrors() throws Exception {
        for (String body : new String[]{"", "null", "{}", "[]", "{",
                "{\"pwd\":\"x\"}", "{\"key\":\"x\"}",
                "{\"key\":null,\"pwd\":\"x\"}", "{\"key\":\"\",\"pwd\":\"x\"}",
                "{\"key\":\"x\",\"pwd\":null}", "{\"key\":123,\"pwd\":\"x\"}",
                "{\"key\":true,\"pwd\":\"x\"}", "{\"key\":\"x\",\"pwd\":123}",
                "{\"key\":\"x\",\"pwd\":1.5}", "{\"key\":\"x\",\"pwd\":false}",
                "{\"key\":{},\"pwd\":\"x\"}", "{\"key\":\"x\",\"pwd\":[]}"}) {
            for (String path : new String[]{"/tools/encryption", "/tools/decryption"}) {
                assertError(request("POST", path, body), "INVALID_REQUEST", INVALID_MESSAGE);
            }
        }
    }

    @Test
    void detectableDecryptionFailuresReturnSafeErrors() throws Exception {
        String encrypted = post("/tools/encryption", "correct-key", "private synthetic input").body();
        for (String invalid : new String[]{"", "not base64!", "AAAA", "!!!!", encrypted + "@",
                encrypted.substring(0, 4) + "!" + encrypted.substring(4)}) {
            assertError(post("/tools/decryption", "correct-key", invalid),
                    "DECRYPTION_FAILED", DECRYPTION_MESSAGE);
        }
        String legacyFixture = "15F7PaVh2GRYdbvNLyLVHcMZVWyQHmnCA9FG48trWHtKcXGsSzhmehkO2j20yjP1";
        assertError(post("/tools/decryption", "wrong-key", legacyFixture),
                "DECRYPTION_FAILED", DECRYPTION_MESSAGE);
    }

    @Test
    void randomPasswordDefaultsAndBoundaries() throws Exception {
        for (String query : new String[]{"", "?num=0", "?num=0&quantity=1"}) {
            HttpResponse<String> response = request("GET", "/tools/get/randompwd" + query, null);
            assertTextResponse(response);
            assertTrue(response.body().matches("(?:[a-hjkmnp-zA-HJKMNP-Z][1-9]){4}(?:[a-hjkmnp-zA-HJKMNP-Z][1-9]?)?\n"));
        }
        for (int length : new int[]{1, 128}) {
            HttpResponse<String> response = request("GET", "/tools/get/randompwd?num=" + length +
                    "&quantity=100", null);
            assertTextResponse(response);
            String[] lines = response.body().split("\n", -1);
            assertEquals(101, lines.length);
            assertEquals("", lines[100]);
            for (int i = 0; i < 100; i++) {
                assertEquals(length, lines[i].length());
                assertTrue(lines[i].matches("[a-hjkmnp-zA-HJKMNP-Z1-9!@#$%^&*(){}\\[\\].?_]+"));
            }
        }
        for (String query : new String[]{"num=-1", "num=129", "quantity=0", "quantity=-1",
                "quantity=101", "num=abc", "quantity=1.5", "num=1.0", "num=2147483648",
                "quantity=2147483648"}) {
            assertError(request("GET", "/tools/get/randompwd?" + query, null),
                    "INVALID_REQUEST", INVALID_MESSAGE);
        }
    }

    private HttpResponse<String> post(String path, String key, String plain) throws Exception {
        return request("POST", path, json.writeValueAsString(Map.of("key", key, "pwd", plain)));
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void assertTextResponse(HttpResponse<String> response) {
        assertEquals(200, response.statusCode());
        assertEquals("text/plain;charset=UTF-8", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    private void assertError(HttpResponse<String> response, String code, String message) {
        assertEquals(400, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var error = json.readTree(response.body());
        assertEquals(2, error.size());
        assertEquals(code, error.get("code").asString());
        assertEquals(message, error.get("message").asString());
    }
}
