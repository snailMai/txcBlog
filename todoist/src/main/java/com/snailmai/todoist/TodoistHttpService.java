package com.snailmai.todoist;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 固定消息入口；仅校验传输格式，不附加业务提示词。 */
@Component
final class TodoistHttpService extends OncePerRequestFilter {
    private final Environment environment;
    private volatile RelayRequests relay;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    @Autowired TodoistHttpService(Environment environment) { this.environment = environment; }
    TodoistHttpService(Environment environment, RelayRequests relay) { this(environment); this.relay = relay; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException {
        response.setHeader("Cache-Control", "no-store"); response.setHeader("X-Content-Type-Options", "nosniff");
        try {
            if (request.getRequestURI().equals("/todoist/health") && request.getMethod().equals("GET")) {
                configure(); write(response, 200, Map.of("status", "ready")); return;
            }
            // 只读任务文件接口：每次请求重新读取解析，只用于列表展示，不写文件。
            if (request.getRequestURI().equals("/todoist/tasks") && request.getMethod().equals("GET")) {
                writeTasks(response, TaskFile.read(taskFile())); return;
            }
            Failure.require(request.getRequestURI().equals("/todoist/commands"), "NOT_FOUND");
            if (request.getMethod().equals("GET")) {
                configure(); write(response, 200, relay.get(id(request.getParameter("requestId")))); return;
            }
            Failure.require(request.getMethod().equals("POST"), "NOT_FOUND");
            Failure.require(request.getContentType() != null && request.getContentType().split(";", 2)[0].strip().equalsIgnoreCase("application/json"), "INVALID_REQUEST");
            byte[] bytes = request.getInputStream().readNBytes(128 * 1024 + 1);
            Failure.require(bytes.length <= 128 * 1024, "INPUT_TOO_LARGE");
            JsonNode body;
            try { body = json.readTree(bytes); } catch (Exception ignored) { throw new Failure("INVALID_REQUEST"); }
            Failure.require(body != null && body.isObject() && body.size() == 2 && body.path("input").isTextual() && body.path("requestId").isTextual(), "INVALID_REQUEST");
            String input = body.path("input").asText();
            Failure.require(!input.isBlank(), "INVALID_REQUEST");
            Failure.require(input.getBytes(StandardCharsets.UTF_8).length <= 16384, "INPUT_TOO_LARGE");
            UUID requestId = id(body.path("requestId").asText());
            configure(); write(response, 202, relay.submit(requestId, input));
        } catch (Failure failure) {
            int status = switch (failure.code) {
                case "NOT_FOUND", "RESULT_NOT_FOUND" -> 404;
                case "INPUT_TOO_LARGE" -> 413;
                case "NOT_CONNECTED", "TASKS_UNAVAILABLE" -> 503;
                case "REQUEST_MISMATCH" -> 409;
                default -> 400;
            };
            write(response, status, Map.of("code", failure.code));
        } catch (Exception ignored) { write(response, 500, Map.of("code", "RESULT_UNKNOWN")); }
    }
    private UUID id(String value) {
        try {
            UUID id = UUID.fromString(value); Failure.require(id.toString().equals(value), "INVALID_REQUEST"); return id;
        } catch (IllegalArgumentException | NullPointerException ignored) { throw new Failure("INVALID_REQUEST"); }
    }
    private synchronized void configure() {
        if (relay != null) return;
        String value = environment.getProperty("todoist.desktop-socket", "");
        Path path = value.isBlank() ? Path.of(System.getProperty("user.home"), ".codex", "ipc", "ipc.sock") : Path.of(value);
        Failure.require(path.isAbsolute() && Files.exists(path), "NOT_CONNECTED");
        relay = new RelayRequests(new AppServerRelay(path));
    }
    private Path taskFile() {
        // 任务文件路径只来自后端配置，不在业务逻辑中硬编码，也不接受请求指定。
        String value = environment.getProperty("todoist.task-file", "");
        Failure.require(!value.isBlank(), "TASKS_UNAVAILABLE");
        return Path.of(value);
    }
    private void writeTasks(HttpServletResponse response, List<Map<String, Object>> tasks) throws IOException {
        response.setStatus(200); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(Map.of("tasks", tasks)));
    }
    private void write(HttpServletResponse response, int status, Map<String, String> body) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(body));
    }
    @PreDestroy void close() { if (relay != null) relay.close(); }
}
