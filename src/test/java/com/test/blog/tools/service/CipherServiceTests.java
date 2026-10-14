package com.test.blog.tools.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 独立校验 UTF-8 字节边界及旧 Java 8 合成密文，不依赖 HTTP 层。 */
class CipherServiceTests {
    private final CipherService service = new CipherService();

    @Test
    void enforcesUtf8KeyAndPlaintextBoundariesWithoutTruncation() {
        // 临界值使用四字节 emoji 拼接，防止按字符计数造成测试假通过。
        for (int offset : new int[]{-1, 0}) {
            String key = "k".repeat(CipherService.MAX_KEY_BYTES + offset);
            int size = CipherService.MAX_PLAINTEXT_BYTES + offset;
            String plain = "🔐".repeat(size / 4) + "x".repeat(size % 4);
            String encrypted = service.encrypt(key, plain);
            assertTrue(encrypted.getBytes(StandardCharsets.UTF_8).length < CipherService.MAX_CIPHERTEXT_BYTES);
            assertEquals(plain, service.decrypt(key, encrypted.replace("\n", "\r\n")));
        }
        String unicodeKey = "密".repeat(341) + "a";
        assertEquals("", service.decrypt(unicodeKey, service.encrypt(unicodeKey, "")));
        for (String key : new String[]{"k".repeat(1025), "密".repeat(342)}) {
            assertThrows(InputTooLargeException.class, () -> service.encrypt(key, ""));
            assertThrows(InputTooLargeException.class, () -> service.decrypt(key, "AAAA"));
        }
        assertThrows(InputTooLargeException.class,
                () -> service.encrypt("key", "a".repeat(CipherService.MAX_PLAINTEXT_BYTES + 1)));
    }

    // 解码会移除换行，但限长必须先计入这些字节。
    @Test
    void ciphertextLimitIncludesLegacyLineSeparators() {
        String encrypted = service.encrypt("key", "text");
        for (int offset : new int[]{-1, 0, 1}) {
            String padded = encrypted + "\n".repeat(CipherService.MAX_CIPHERTEXT_BYTES + offset - encrypted.length());
            if (offset > 0) assertThrows(InputTooLargeException.class, () -> service.decrypt("key", padded));
            else assertEquals("text", service.decrypt("key", padded));
        }
    }

    // 固定样本来自独立旧实现，同时检查 LF 与 CRLF 两种换行格式。
    @Test
    void decryptsAllIndependentJava8FixturesIncludingCrLf() throws Exception {
        try (var source = getClass().getResourceAsStream("/legacy-java8-fixtures.json")) {
            assertNotNull(source);
            var fixtures = JsonMapper.builder().build().readTree(source);
            assertEquals(17, fixtures.size());
            for (var fixture : fixtures) {
                String id = fixture.get("id").asString();
                String key = fixture.get("key").asString();
                String ciphertext = fixture.get("ciphertext").asString();
                String plaintext = fixture.get("plaintext").asString();
                assertEquals(plaintext, service.decrypt(key, ciphertext), id);
                assertEquals(plaintext, service.decrypt(key, ciphertext.replace("\n", "\r\n")), id);
            }
        }
    }

    @Test
    void preservesLegacyLineFoldingAndExactChunkTerminator() {
        // 891 个 ASCII 字符加 5 字节前缀及填充等于 912 字节，恰好是 16 × 57。
        String ciphertext = service.encrypt("boundary-key", "x".repeat(891));
        String[] lines = ciphertext.split("\n", -1);
        assertEquals(17, lines.length);
        for (int i = 0; i < 16; i++) assertEquals(76, lines[i].length());
        assertEquals("", lines[16]);
        assertEquals("x".repeat(891), service.decrypt("boundary-key", ciphertext));
    }

    @Test
    void permitsEmptyPlaintextButNotMissingInputs() {
        assertEquals("", service.decrypt("key", service.encrypt("key", "")));
        assertThrows(IllegalArgumentException.class, () -> service.encrypt(null, "text"));
        assertThrows(IllegalArgumentException.class, () -> service.encrypt("", "text"));
        assertThrows(IllegalArgumentException.class, () -> service.encrypt("key", null));
        assertThrows(IllegalArgumentException.class, () -> service.decrypt("key", null));
    }

    @Test
    void rejectsPlaintextWithoutTheLegacyPrefixAndMalformedUtf8() throws Exception {
        assertThrows(DecryptionException.class, () -> service.decrypt("fixture-key", encryptRaw(new byte[]{1, 2, 3})));
        assertThrows(DecryptionException.class, () -> service.decrypt("fixture-key",
                encryptRaw("abcdeInvalid prefix".getBytes(StandardCharsets.UTF_8))));
        assertThrows(DecryptionException.class, () -> service.decrypt("fixture-key",
                encryptRaw(new byte[]{'1', '2', '3', '4', '5', (byte) 0xc3, 0x28})));
    }

    // 绕过业务层前缀拼接以构造负例，只用于模拟缺前缀及非法 UTF-8 数据。
    private String encryptRaw(byte[] data) throws Exception {
        SecureRandom seeded = SecureRandom.getInstance("SHA1PRNG", "SUN");
        seeded.setSeed("fixture-key".getBytes(StandardCharsets.UTF_8));
        KeyGenerator keygen = KeyGenerator.getInstance("AES", "SunJCE");
        keygen.init(128, seeded);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding", "SunJCE");
        cipher.init(Cipher.ENCRYPT_MODE, keygen.generateKey());
        return Base64.getEncoder().encodeToString(cipher.doFinal(data));
    }
}
