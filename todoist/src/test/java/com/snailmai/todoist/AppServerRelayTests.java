package com.snailmai.todoist;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class AppServerRelayTests {
    final JsonMapper json = JsonMapper.builder().build();
    static class FakeProcess extends Process {
        final ByteArrayInputStream output;
        final ByteArrayOutputStream input = new ByteArrayOutputStream();
        FakeProcess(String output) { this.output = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)); }
        public OutputStream getOutputStream() { return input; }
        public InputStream getInputStream() { return output; }
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        public int waitFor() { return 0; } public int exitValue() { return 0; } public void destroy() {}
    }
    String response(int id, Object result) { return json.writeValueAsString(Map.of("id", id, "result", result)) + "\n"; }
    String setup(String sandbox) {
        return response(1, Map.of()) + response(2, Map.of("thread", Map.of("id", AppServerRelay.THREAD_ID), "sandbox", Map.of("type", sandbox)))
            + response(3, Map.of("turn", Map.of("id", "turn-test")));
    }
    String event(String method, Object extra) {
        var params = new HashMap<String, Object>();
        params.put("threadId", AppServerRelay.THREAD_ID); params.put("turnId", "turn-test");
        params.put(method.equals("turn/completed") ? "turn" : "item", extra);
        return json.writeValueAsString(Map.of("method", method, "params", params)) + "\n";
    }
    String completed() { return event("turn/completed", Map.of("id", "turn-test", "status", "completed")); }
    AppServerRelay relay(FakeProcess process) {
        return new AppServerRelay(Path.of("/synthetic/codex"), Path.of("/synthetic/workspace"), (command, cwd) -> {
            assertEquals(List.of("/synthetic/codex", "app-server", "--listen", "stdio://"), command); return process;
        });
    }
    @Test void nativeToolsExecuteAndOnlyFinalReplyIsReturned() throws Exception {
        var process = new FakeProcess(setup("workspaceWrite")
            + event("item/started", Map.of("type", "commandExecution"))
            + event("item/completed", Map.of("type", "fileChange", "status", "completed"))
            + event("item/completed", Map.of("type", "agentMessage", "phase", "commentary", "text", "正在执行"))
            + event("item/completed", Map.of("type", "agentMessage", "phase", "final_answer", "text", "主人，已添加。")) + completed());
        assertEquals("主人，已添加。", relay(process).run("添加任务：合成任务"));
        String sent = process.input.toString(StandardCharsets.UTF_8);
        assertTrue(sent.contains(AppServerRelay.THREAD_ID)); assertTrue(sent.contains("workspace-write"));
        assertTrue(sent.contains("never")); assertTrue(sent.contains("添加任务：合成任务"));
        assertFalse(sent.contains("outputSchema")); assertFalse(sent.contains("thread/start"));
        assertFalse(sent.contains("config/value/write")); assertFalse(sent.contains("features."));
    }
    @Test void queryAlsoGoesToConversationAndHasNoFileParser() throws Exception {
        var process = new FakeProcess(setup("workspaceWrite")
            + event("item/completed", Map.of("type", "agentMessage", "text", "合成查询结果")) + completed());
        assertEquals("合成查询结果", relay(process).run("输出全部待办"));
        assertTrue(process.input.toString(StandardCharsets.UTF_8).contains("输出全部待办"));
    }
    @Test void readonlyConnectionCannotPretendToExecute() {
        var process = new FakeProcess(setup("readOnly"));
        assertEquals("NOT_CONNECTED", assertThrows(Failure.class, () -> relay(process).run("添加任务：合成")).code);
        assertFalse(process.input.toString(StandardCharsets.UTF_8).contains("turn/start"));
    }
    @Test void failedTurnAndDisconnectAreUnknownNotUnwritten() {
        for (String ending : List.of("", event("turn/completed", Map.of("id", "turn-test", "status", "failed")), completed())) {
            var process = new FakeProcess(setup("workspaceWrite") + ending);
            assertEquals("RESULT_UNKNOWN", assertThrows(Failure.class, () -> relay(process).run("添加任务：合成")).code);
        }
    }
    @Test void desktopInteractionIsNotAutomaticallyApproved() {
        var process = new FakeProcess(setup("workspaceWrite") + json.writeValueAsString(Map.of("id", 50, "method", "item/tool/requestUserInput", "params", Map.of())) + "\n");
        assertEquals("NEEDS_ATTENTION", assertThrows(Failure.class, () -> relay(process).run("修改任务：合成")).code);
        assertFalse(process.input.toString(StandardCharsets.UTF_8).contains("accept"));
    }
    @Test void userInputIsForwardedExactlyWithoutAddedPrompt() throws Exception {
        for (String input : List.of("输出全部待办", "添加任务：合成任务", "修改任务：合成任务的备注\n保留换行与引号：\"原文\"")) {
            var process = new FakeProcess(setup("workspaceWrite")
                + event("item/completed", Map.of("type", "agentMessage", "text", "合成结果")) + completed());
            assertEquals("合成结果", relay(process).run(input));
            var start = process.input.toString(StandardCharsets.UTF_8).lines()
                .map(json::readTree).filter(message -> message.path("method").asText().equals("turn/start"))
                .findFirst().orElseThrow();
            var content = start.path("params").path("input");
            assertEquals(1, content.size());
            assertEquals("text", content.get(0).path("type").asText());
            assertEquals(input, content.get(0).path("text").asText());
        }
    }
}
