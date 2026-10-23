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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 固定本地任务入口；不调用会话，客户端不能指定文件路径或系统命令。 */
@Component
final class TodoistHttpService extends OncePerRequestFilter {
    private final Environment environment;
    private volatile RelayRequests relay;
    private volatile LocalTasks storage;
    private RecurringScheduler recurring;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    @Autowired TodoistHttpService(Environment environment) { this.environment = environment; }
    TodoistHttpService(Environment environment, RelayRequests relay) { this(environment); this.relay = relay; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException {
        response.setHeader("Cache-Control", "no-store"); response.setHeader("X-Content-Type-Options", "nosniff");
        try {
            if (request.getRequestURI().equals("/todoist/health") && request.getMethod().equals("GET")) {
                local(); Failure.require(storage.ready(), "STORAGE_UNAVAILABLE"); write(response, 200, Map.of("status", "ready", "mode", "local")); return;
            }
            if (request.getRequestURI().equals("/todoist/recurring/preview") && request.getMethod().equals("GET")) {
                write(response, 200, local().previewRecurring()); return;
            }
            // 只读任务文件接口：每次请求重新读取解析，只用于列表展示，不写文件。
            if (request.getRequestURI().equals("/todoist/tasks") && request.getMethod().equals("GET")) {
                writeTasks(response, local().list()); return;
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
            TaskOperation operation = TaskOperation.parse(body);
            String input = json.writeValueAsString(operation);
            Failure.require(input.getBytes(StandardCharsets.UTF_8).length <= 16384, "INPUT_TOO_LARGE");
            UUID requestId = id(operation.requestId());
            configure(); write(response, 202, relay.submit(requestId, input));
        } catch (Failure failure) {
            int status = switch (failure.code) {
                case "NOT_FOUND", "RESULT_NOT_FOUND" -> 404;
                case "INPUT_TOO_LARGE" -> 413;
                case "TASKS_UNAVAILABLE", "STORAGE_UNAVAILABLE" -> 503;
                case "REQUEST_MISMATCH", "TASK_CHANGED", "DUPLICATE_TASK", "SIMILAR_TASK", "FILE_LOCKED", "ARCHIVE_CONFLICT" -> 409;
                default -> 400;
            };
            Map<String, Object> error = new LinkedHashMap<>(failure.details); error.put("code", failure.code);
            write(response, status, error);
        } catch (Exception ignored) { write(response, 500, Map.of("code", "RESULT_UNKNOWN")); }
    }
    private UUID id(String value) {
        try {
            UUID id = UUID.fromString(value); Failure.require(id.toString().equals(value), "INVALID_REQUEST"); return id;
        } catch (IllegalArgumentException | NullPointerException ignored) { throw new Failure("INVALID_REQUEST"); }
    }
    private synchronized void configure() {
        if (relay != null) return;
        LocalTasks tasks = local(); relay = new RelayRequests(tasks, tasks::lookup);
    }
    @EventListener(ApplicationReadyEvent.class)
    synchronized void startRecurring() {
        if (recurring != null || !environment.getProperty("todoist.recurring.enabled", Boolean.class, false)) return;
        configure();
        recurring = new RecurringScheduler(relay, code -> org.slf4j.LoggerFactory.getLogger(TodoistHttpService.class)
            .error("todoist recurring check failed: {}", code));
        recurring.start();
    }
    private synchronized LocalTasks local() {
        if (storage != null) return storage;
        String todo = environment.getProperty("todoist.task-file", ""), archive = environment.getProperty("todoist.archive-file", "");
        Failure.require(!todo.isBlank() && !archive.isBlank(), "TASKS_UNAVAILABLE");
        String state = environment.getProperty("todoist.state-dir", "");
        Path statePath = state.isBlank() ? Path.of(System.getProperty("user.home"), "Library", "Application Support", "personweb", "todoist-state") : Path.of(state);
        storage = new LocalTasks(Path.of(todo), Path.of(archive), statePath); return storage;
    }
    private void writeTasks(HttpServletResponse response, List<Map<String, Object>> tasks) throws IOException {
        response.setStatus(200); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(Map.of("tasks", tasks)));
    }
    private void write(HttpServletResponse response, int status, Map<String, ?> body) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(body));
    }
    @PreDestroy synchronized void close() { if (recurring != null) recurring.close(); if (relay != null) relay.close(); }
}
