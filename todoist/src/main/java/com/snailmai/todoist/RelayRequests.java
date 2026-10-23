package com.snailmai.todoist;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * 本地命令队列：一次只执行一项文件操作；未知结果从执行器回执恢复，不自动重发。
 */
final class RelayRequests implements AutoCloseable {
    interface Runner { String run(String input) throws Exception; }
    // 结果可能已经执行、无法确定的失败，以「结果未知」状态呈现，不自动重发。
    private static final Set<String> UNKNOWN_CODES = Set.of("RESULT_UNKNOWN", "NEEDS_ATTENTION", "RECOVERY_REQUIRED");
    private final Runner runner;
    private final Function<UUID, Map<String, Object>> lookup;
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>();
    private final BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
    private final Thread worker;

    private static final class Request {
        final UUID id;
        final String input;
        volatile Map<String, Object> result;
        volatile boolean running;
        volatile Instant completed;
        Request(UUID id, String input) { this.id = id; this.input = input; }
    }

    RelayRequests(Runner runner) {
        this(runner, id -> null);
    }
    RelayRequests(Runner runner, Function<UUID, Map<String, Object>> lookup) {
        this.runner = runner; this.lookup = lookup;
        this.worker = Thread.ofVirtual().name("todoist-queue").start(this::drain);
    }

    private void drain() {
        while (!Thread.currentThread().isInterrupted()) {
            Request request;
            try { request = queue.take(); }
            catch (InterruptedException ignored) { return; }
            request.running = true;
            Map<String, Object> result;
            try {
                result = Map.of("requestId", request.id.toString(), "status", "completed", "output", runner.run(request.input));
            } catch (Failure failure) {
                boolean unknown = UNKNOWN_CODES.contains(failure.code);
                result = new LinkedHashMap<>(failure.details);
                result.put("requestId", request.id.toString()); result.put("status", unknown ? "unknown" : "failed"); result.put("code", failure.code);
            } catch (Exception ignored) {
                result = Map.of("requestId", request.id.toString(), "status", "unknown", "code", "RESULT_UNKNOWN");
            }
            request.result = result;
            request.completed = Instant.now();
        }
    }

    Map<String, Object> submit(UUID id, String input) {
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        requests.entrySet().removeIf(entry -> entry.getValue().completed != null && entry.getValue().completed.isBefore(cutoff));
        Request candidate = new Request(id, input);
        Request existing = requests.putIfAbsent(id, candidate);
        if (existing != null) {
            Failure.require(existing.input.equals(input), "REQUEST_MISMATCH"); return get(id);
        }
        queue.add(candidate);
        return get(id);
    }

    Map<String, Object> get(UUID id) {
        Request request = requests.get(id);
        if (request == null) {
            Map<String, Object> saved = lookup.apply(id);
            Failure.require(saved != null, "RESULT_NOT_FOUND"); return saved;
        }
        Map<String, Object> result = request.result;
        if (result != null && result.get("status").equals("unknown")) {
            Map<String, Object> saved = lookup.apply(id);
            if (saved != null) request.result = result = saved;
        }
        if (result != null) return result;
        return Map.of("requestId", id.toString(), "status", request.running ? "running" : "queued");
    }

    @Override public void close() {
        worker.interrupt();
        try { worker.join(Duration.ofSeconds(2)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
