package com.snailmai.todoist;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 将文本交给原会话实际执行；文件操作属于 Codex 工具，不属于本服务。 */
final class AppServerRelay implements RelayRequests.Runner {
    static final String THREAD_ID = "01a13607-d04d-7800-b5f4-59891d7e7da5";
    private final Path executable, workspace;
    interface ProcessFactory { Process start(List<String> command, Path workspace) throws IOException; }
    private final ProcessFactory factory;
    private final JsonMapper json = JsonMapper.builder().build();

    AppServerRelay(Path executable, Path workspace) {
        this(executable, workspace, (command, cwd) -> {
            ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            // 不继承当前开发会话的任务身份；继续使用用户已有登录。
            builder.environment().keySet().removeIf(name -> name.startsWith("CODEX_") && !name.equals("CODEX_HOME"));
            return builder.start();
        });
    }
    AppServerRelay(Path executable, Path workspace, ProcessFactory factory) {
        this.executable = executable; this.workspace = workspace; this.factory = factory;
    }
    @Override public String run(String input) throws Exception {
        Process process;
        try { process = factory.start(List.of(executable.toString(), "app-server", "--listen", "stdio://"), workspace); }
        catch (IOException ignored) { throw new Failure("NOT_CONNECTED"); }
        boolean submitted = false;
        try (Connection connection = new Connection(process, Duration.ofMinutes(10))) {
            connection.rpc("initialize", Map.of("clientInfo", Map.of("name", "personweb_todoist", "version", "2.0.0")));
            connection.send(Map.of("method", "initialized", "params", Map.of()));
            JsonNode resumed = connection.rpc("thread/resume", Map.of("threadId", THREAD_ID, "excludeTurns", true,
                "cwd", workspace.toString(), "sandbox", "workspace-write", "approvalPolicy", "never"));
            Failure.require(resumed.path("thread").path("id").asText().equals(THREAD_ID), "NOT_CONNECTED");
            Failure.require(Set.of("workspaceWrite", "dangerFullAccess").contains(resumed.path("sandbox").path("type").asText()), "NOT_CONNECTED");
            // 无结构化提案、无工具禁用；查询也交由原会话读取最新任务。
            submitted = true;
            JsonNode start = connection.rpc("turn/start", Map.of("threadId", THREAD_ID,
                "input", List.of(Map.of("type", "text", "text", input, "text_elements", List.of()))));
            String turnId = start.path("turn").path("id").asText();
            Failure.require(!turnId.isBlank(), "RESULT_UNKNOWN");
            return connection.finish(turnId);
        } catch (Failure failure) {
            if (!submitted || failure.code.equals("NEEDS_ATTENTION")) throw failure;
            throw new Failure("RESULT_UNKNOWN");
        } catch (Exception ignored) {
            throw new Failure(submitted ? "RESULT_UNKNOWN" : "NOT_CONNECTED");
        } finally {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }
    /** 仅返回最终文本；不保存工具输出、推理或请求正文日志。 */
    private final class Connection implements AutoCloseable {
        final Process process;
        final BufferedWriter writer;
        final BlockingQueue<JsonNode> messages = new ArrayBlockingQueue<>(1024);
        final List<JsonNode> deferred = new ArrayList<>();
        final long deadline;
        final Thread reader;
        volatile boolean ended;
        int sequence;
        Connection(Process process, Duration duration) {
            this.process = process;
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            deadline = System.nanoTime() + duration.toNanos();
            reader = Thread.ofVirtual().start(() -> {
                try (Reader input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                    StringBuilder line = new StringBuilder(); int c;
                    while ((c = input.read()) != -1) {
                        if (c == '\n') {
                            if (!line.isEmpty()) { messages.put(json.readTree(line.toString())); line.setLength(0); }
                        } else {
                            if (line.length() >= 8 * 1024 * 1024) throw new IOException();
                            line.append((char)c);
                        }
                    }
                } catch (Exception ignored) { process.destroy(); }
                finally { ended = true; }
            });
        }
        void send(Object value) throws IOException { writer.write(json.writeValueAsString(value)); writer.newLine(); writer.flush(); }
        JsonNode next() throws Exception {
            while (System.nanoTime() < deadline) {
                JsonNode message = messages.poll(100, TimeUnit.MILLISECONDS);
                if (message != null) {
                    if (message.has("method") && message.has("id")) {
                        // 原生 shell／文件工具由 App Server 执行；不能伪造桌面专属交互的确认。
                        send(Map.of("id", message.get("id"), "error", Map.of("code", -32601, "message", "Please reply in text or continue in the desktop conversation")));
                        throw new Failure("NEEDS_ATTENTION");
                    }
                    return message;
                }
                if (ended) throw new Failure("RESULT_UNKNOWN");
            }
            throw new Failure("RESULT_UNKNOWN");
        }
        JsonNode rpc(String method, Object params) throws Exception {
            int id = ++sequence;
            send(Map.of("id", id, "method", method, "params", params));
            while (true) {
                JsonNode message = next();
                if (message.path("id").asInt(-1) == id) {
                    Failure.require(!message.has("error"), "NOT_CONNECTED"); return message.path("result");
                }
                Failure.require(deferred.size() < 1024, "RESULT_UNKNOWN"); deferred.add(message);
            }
        }
        String finish(String turnId) throws Exception {
            String answer = null; int index = 0;
            while (true) {
                JsonNode event = index < deferred.size() ? deferred.get(index++) : next();
                JsonNode params = event.path("params");
                if (!params.path("threadId").asText().equals(THREAD_ID)) continue;
                String method = event.path("method").asText();
                if (method.equals("turn/completed") && params.path("turn").path("id").asText().equals(turnId)) {
                    Failure.require(params.path("turn").path("status").asText().equals("completed") && answer != null, "RESULT_UNKNOWN");
                    return answer;
                }
                if (!params.path("turnId").asText().equals(turnId)) continue;
                if (method.equals("item/completed")) {
                    JsonNode item = params.path("item");
                    if (item.path("type").asText().equals("agentMessage") && !item.path("phase").asText().equals("commentary"))
                        answer = item.path("text").asText();
                }
            }
        }
        @Override public void close() { process.destroy(); reader.interrupt(); }
    }
}
