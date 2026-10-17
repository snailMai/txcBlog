package com.snailmai.todoist;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 命令队列：内存 FIFO，一次只送一条命令给原会话，消除写入连接争用；
 * 一条失败不阻塞后续命令。仅保存网页取回复所需的短期状态，不读写任务文件。
 */
final class RelayRequests implements AutoCloseable {
    interface Runner { String run(String input) throws Exception; }
    // 结果可能已经执行、无法确定的失败，以「结果未知」状态呈现，不自动重发。
    private static final Set<String> UNKNOWN_CODES = Set.of("RESULT_UNKNOWN", "NEEDS_ATTENTION");
    private final Runner runner;
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>();
    private final BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
    private final Thread worker;

    private static final class Request {
        final UUID id;
        final String input;
        volatile Map<String, String> result;
        volatile boolean running;
        volatile Instant completed;
        Request(UUID id, String input) { this.id = id; this.input = input; }
    }

    RelayRequests(Runner runner) {
        this.runner = runner;
        this.worker = Thread.ofVirtual().name("todoist-queue").start(this::drain);
    }

    private void drain() {
        while (!Thread.currentThread().isInterrupted()) {
            Request request;
            try { request = queue.take(); }
            catch (InterruptedException ignored) { return; }
            request.running = true;
            Map<String, String> result;
            try {
                result = Map.of("requestId", request.id.toString(), "status", "completed", "output", runner.run(request.input));
            } catch (Failure failure) {
                boolean unknown = UNKNOWN_CODES.contains(failure.code);
                result = Map.of("requestId", request.id.toString(), "status", unknown ? "unknown" : "failed", "code", failure.code);
            } catch (Exception ignored) {
                result = Map.of("requestId", request.id.toString(), "status", "unknown", "code", "RESULT_UNKNOWN");
            }
            request.result = result;
            request.completed = Instant.now();
        }
    }

    Map<String, String> submit(UUID id, String input) {
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

    Map<String, String> get(UUID id) {
        Request request = requests.get(id);
        Failure.require(request != null, "RESULT_NOT_FOUND");
        Map<String, String> result = request.result;
        if (result != null) return result;
        return Map.of("requestId", id.toString(), "status", request.running ? "running" : "queued");
    }

    @Override public void close() {
        worker.interrupt();
        try { worker.join(Duration.ofSeconds(2)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
