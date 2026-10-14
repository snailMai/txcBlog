package com.snailmai.todoist;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class TodoistHttpTests {
    final JsonMapper json = JsonMapper.builder().build();
    MockHttpServletResponse request(TodoistHttpService service, String method, String path, String body, UUID id) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setContentType("application/json");
        if (body != null) request.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (id != null) request.setParameter("requestId", id.toString());
        var response = new MockHttpServletResponse(); service.doFilter(request, response, new MockFilterChain()); return response;
    }
    String body(UUID id, String input) { return json.writeValueAsString(Map.of("input", input, "requestId", id.toString())); }
    @Test void postReturnsImmediatelyAndGetOnlyRetrievesReplyWithoutAuthentication() throws Exception {
        var release = new CompletableFuture<String>(); var calls = new AtomicInteger();
        try (var relay = new RelayRequests(input -> { calls.incrementAndGet(); return release.get(); })) {
            var service = new TodoistHttpService(new MockEnvironment(), relay); UUID id = UUID.randomUUID();
            var post = request(service, "POST", "/todoist/commands", body(id, "请添加一个合成任务"), null);
            assertEquals(202, post.getStatus()); assertEquals("running", json.readTree(post.getContentAsString()).path("status").asText());
            release.complete("主人，已完成合成操作。");
            Map<String, String> result = await(relay, id);
            assertEquals("completed", result.get("status")); assertEquals("主人，已完成合成操作。", result.get("output"));
            assertEquals(200, request(service, "GET", "/todoist/commands", null, id).getStatus());
            relay.submit(id, "请添加一个合成任务"); assertEquals(1, calls.get());
            assertEquals("no-store", post.getHeader("Cache-Control")); assertNull(post.getHeader("WWW-Authenticate"));
        }
    }
    @Test void missingResultDoesNotResubmitAndChangedInputCannotReuseId() throws Exception {
        try (var relay = new RelayRequests(input -> "done")) {
            var service = new TodoistHttpService(new MockEnvironment(), relay);
            assertEquals(404, request(service, "GET", "/todoist/commands", null, UUID.randomUUID()).getStatus());
            UUID id = UUID.randomUUID(); relay.submit(id, "one");
            assertEquals(409, request(service, "POST", "/todoist/commands", body(id, "two"), null).getStatus());
        }
    }
    @Test void transportValidationRejectsEmptyMalformedAndExtraConfiguration() throws Exception {
        try (var relay = new RelayRequests(input -> { fail("Must not reach model"); return ""; })) {
            var service = new TodoistHttpService(new MockEnvironment(), relay);
            for (String value : List.of("{}", "[]", "null", "{", body(UUID.randomUUID(), " "),
                "{\"input\":\"test\",\"requestId\":\"bad\"}",
                body(UUID.randomUUID(), "test").replace("}", ",\"path\":\"/private\"}"),
                body(UUID.randomUUID(), "test") + "{}",
                body(UUID.randomUUID(), "test").replace("}", ",\"input\":\"again\"}")))
                assertEquals(400, request(service, "POST", "/todoist/commands", value, null).getStatus());
            assertEquals(413, request(service, "POST", "/todoist/commands", body(UUID.randomUUID(), "中".repeat(6000)), null).getStatus());
            assertEquals(413, request(service, "POST", "/todoist/commands", " ".repeat(128 * 1024 + 1), null).getStatus());
        }
    }
    @Test void noBackendCommandGateAndNoTodoFileRequirement() throws Exception {
        try (var relay = new RelayRequests(input -> "不支持此命令")) {
            var service = new TodoistHttpService(new MockEnvironment(), relay); UUID id = UUID.randomUUID();
            assertEquals(202, request(service, "POST", "/todoist/commands", body(id, "删除任务：合成任务"), null).getStatus());
            assertEquals("不支持此命令", await(relay, id).get("output"));
            assertEquals(200, request(service, "GET", "/todoist/health", null, null).getStatus());
        }
    }
    @Test void configurationMissingAndUnknownRoutesAreSafe() throws Exception {
        var service = new TodoistHttpService(new MockEnvironment());
        var response = request(service, "POST", "/todoist/commands", body(UUID.randomUUID(), "输出全部待办"), null);
        assertEquals(503, response.getStatus()); assertTrue(response.getContentAsString().contains("NOT_CONNECTED"));
        assertEquals(404, request(service, "GET", "/todoist/auth", null, null).getStatus());
    }
    @Test void exceptionsNeverLeakTaskContentOrClaimNoWrite() throws Exception {
        try (var relay = new RelayRequests(input -> { throw new RuntimeException("synthetic-private-value"); })) {
            UUID id = UUID.randomUUID(); relay.submit(id, "合成输入");
            var result = await(relay, id);
            assertEquals("failed", result.get("status")); assertEquals("RESULT_UNKNOWN", result.get("code"));
            assertFalse(result.toString().contains("synthetic-private-value"));
        }
    }
    static Map<String, String> await(RelayRequests relay, UUID id) throws Exception {
        for (int i = 0; i < 200; i++) {
            var result = relay.get(id); if (!result.get("status").equals("running")) return result; Thread.sleep(5);
        }
        fail("Timed out waiting for synthetic runner"); return Map.of();
    }
}
