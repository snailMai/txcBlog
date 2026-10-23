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
    String body(UUID id, String input) { return json.writeValueAsString(Map.of("operation", "add", "fields", Map.of("name", input, "goal", "合成目标", "criteria", "合成标准"), "requestId", id.toString())); }
    @Test void periodicOperationIsPrivateAndSchedulerIsDisabledByDefault() throws Exception {
        var calls = new AtomicInteger();
        try (var relay = new RelayRequests(input -> { calls.incrementAndGet(); return "不应执行"; })) {
            var service = new TodoistHttpService(new MockEnvironment(), relay);
            service.startRecurring(); assertEquals(0, calls.get());
            var body = json.writeValueAsString(Map.of("requestId", UUID.randomUUID().toString(), "operation", "recurring"));
            var response = request(service, "POST", "/todoist/commands", body, null);
            assertEquals(400, response.getStatus()); assertTrue(response.getContentAsString().contains("INVALID_REQUEST")); assertEquals(0, calls.get());
            service.close();
        }
    }
    @Test void postReturnsImmediatelyAndGetOnlyRetrievesReplyWithoutAuthentication() throws Exception {
        var release = new CompletableFuture<String>(); var calls = new AtomicInteger();
        try (var relay = new RelayRequests(input -> { calls.incrementAndGet(); return release.get(); })) {
            var service = new TodoistHttpService(new MockEnvironment(), relay); UUID id = UUID.randomUUID();
            var post = request(service, "POST", "/todoist/commands", body(id, "请添加一个合成任务"), null);
            assertEquals(202, post.getStatus());
            // 队列化后提交即返回，状态为排队中或执行中。
            assertTrue(Set.of("queued", "running").contains(json.readTree(post.getContentAsString()).path("status").asText()));
            release.complete("主人，已完成合成操作。");
            Map<String, Object> result = await(relay, id);
            assertEquals("completed", result.get("status")); assertEquals("主人，已完成合成操作。", result.get("output"));
            assertEquals(200, request(service, "GET", "/todoist/commands", null, id).getStatus());
            relay.submit(id, json.writeValueAsString(TaskOperation.parse(json.readTree(body(id, "请添加一个合成任务"))))); assertEquals(1, calls.get());
            assertEquals("no-store", post.getHeader("Cache-Control")); assertNull(post.getHeader("WWW-Authenticate"));
        }
    }
    @Test void queueRunsCommandsOneAtATimeAndFailureDoesNotBlockLaterCommands() throws Exception {
        var release = new CompletableFuture<String>();
        var running = new AtomicInteger(); var maxConcurrent = new AtomicInteger(); var started = new ArrayDeque<String>();
        try (var relay = new RelayRequests(input -> {
            int now = running.incrementAndGet(); maxConcurrent.accumulateAndGet(now, Math::max);
            synchronized (started) { started.add(input); }
            try {
                if (input.contains("阻塞")) return release.get();
                if (input.contains("失败")) throw new Failure("REQUEST_REJECTED");
                return "done:" + input;
            } finally { running.decrementAndGet(); }
        })) {
            UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
            relay.submit(first, "合成阻塞命令");
            relay.submit(second, "合成失败命令");
            relay.submit(third, "合成后续命令");
            // 前一条未完成时，后续命令保持排队，不会并发进入会话。
            for (int i = 0; i < 200 && relay.get(third).get("status").equals("queued"); i++) Thread.sleep(5);
            assertEquals("queued", relay.get(second).get("status"));
            synchronized (started) { assertEquals(1, started.size()); }
            release.complete("主人，已完成。");
            assertEquals("completed", await(relay, first).get("status"));
            Map<String, Object> failed = await(relay, second);
            assertEquals("failed", failed.get("status")); assertEquals("REQUEST_REJECTED", failed.get("code"));
            assertEquals("done:合成后续命令", await(relay, third).get("output"));
            assertEquals(1, maxConcurrent.get());
        }
    }
    @Test void uncertainFailureIsReportedAsUnknownStatus() throws Exception {
        try (var relay = new RelayRequests(input -> { throw new RuntimeException("synthetic-private-value"); })) {
            UUID id = UUID.randomUUID(); relay.submit(id, "合成输入");
            var result = await(relay, id);
            assertEquals("unknown", result.get("status")); assertEquals("RESULT_UNKNOWN", result.get("code"));
            assertFalse(result.toString().contains("synthetic-private-value"));
        }
        try (var relay = new RelayRequests(input -> { throw new Failure("NEEDS_ATTENTION"); })) {
            UUID id = UUID.randomUUID(); relay.submit(id, "合成输入");
            assertEquals("unknown", await(relay, id).get("status"));
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
            assertEquals(503, request(service, "GET", "/todoist/health", null, null).getStatus());
        }
    }
    @Test void configurationMissingAndUnknownRoutesAreSafe() throws Exception {
        var service = new TodoistHttpService(new MockEnvironment().withProperty("todoist.desktop-socket", "/synthetic/missing-socket"));
        var response = request(service, "POST", "/todoist/commands", body(UUID.randomUUID(), "输出全部待办"), null);
        assertEquals(503, response.getStatus()); assertTrue(response.getContentAsString().contains("TASKS_UNAVAILABLE"));
        assertEquals(404, request(service, "GET", "/todoist/auth", null, null).getStatus());
    }
    @Test void exceptionsNeverLeakTaskContentOrClaimNoWrite() throws Exception {
        try (var relay = new RelayRequests(input -> { throw new RuntimeException("synthetic-private-value"); })) {
            UUID id = UUID.randomUUID(); relay.submit(id, "合成输入");
            var result = await(relay, id);
            assertEquals("unknown", result.get("status")); assertEquals("RESULT_UNKNOWN", result.get("code"));
            assertFalse(result.toString().contains("synthetic-private-value"));
        }
    }
    @Test void writerRefusalRemainsACompletedFailureRecordForTheSameRequest() throws Exception {
        var calls = new AtomicInteger();
        try (var relay = new RelayRequests(input -> { calls.incrementAndGet(); throw new Failure("SESSION_IN_USE"); })) {
            var service = new TodoistHttpService(new MockEnvironment(), relay);
            UUID id = UUID.randomUUID();
            request(service, "POST", "/todoist/commands", body(id, "完成任务：合成任务"), null);
            assertEquals("SESSION_IN_USE", await(relay, id).get("code"));
            var result = json.readTree(request(service, "GET", "/todoist/commands", null, id).getContentAsString());
            assertEquals(id.toString(), result.path("requestId").asText());
            assertEquals("failed", result.path("status").asText());
            assertEquals("SESSION_IN_USE", result.path("code").asText());
            assertFalse(result.toString().contains("合成任务"));
            assertEquals(1, calls.get());
        }
    }
    @Test void httpPassesStructuredOperationNotNaturalLanguageToLocalRunner() throws Exception {
        String input = "合成任务";
        try (var relay = new RelayRequests(received -> {
            var parsed = TaskOperation.parse(json.readTree(received));
            assertEquals("add", parsed.operation()); assertEquals(input, parsed.fields().get("name"));
            return "已验证\n";
        })) {
            UUID id = UUID.randomUUID();
            var service = new TodoistHttpService(new MockEnvironment(), relay);
            assertEquals(202, request(service, "POST", "/todoist/commands", body(id, input), null).getStatus());
            assertEquals("已验证\n", await(relay, id).get("output"));
        }
    }
    static Map<String, Object> await(RelayRequests relay, UUID id) throws Exception {
        for (int i = 0; i < 200; i++) {
            var result = relay.get(id);
            String status = result.get("status").toString();
            if (!status.equals("running") && !status.equals("queued")) return result;
            Thread.sleep(5);
        }
        fail("Timed out waiting for synthetic runner"); return Map.of();
    }
}
