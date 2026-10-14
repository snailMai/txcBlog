package com.snailmai.todoist;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 网页取回复所需的内存状态，不读写任务文件，不承担文件事务或跨端协调。 */
final class RelayRequests implements AutoCloseable {
    interface Runner { String run(String input) throws Exception; }
    private final Runner runner;
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private static final class Request {
        final String input;
        final CompletableFuture<Map<String, String>> result = new CompletableFuture<>();
        volatile Instant completed;
        Request(String input) { this.input = input; }
    }
    RelayRequests(Runner runner) { this.runner = runner; }
    Map<String, String> submit(UUID id, String input) {
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        requests.entrySet().removeIf(entry -> entry.getValue().completed != null && entry.getValue().completed.isBefore(cutoff));
        Request candidate = new Request(input);
        Request existing = requests.putIfAbsent(id, candidate);
        if (existing != null) {
            Failure.require(existing.input.equals(input), "REQUEST_MISMATCH"); return get(id);
        }
        executor.submit(() -> {
            Map<String, String> result;
            try { result = Map.of("requestId", id.toString(), "status", "completed", "output", runner.run(input)); }
            catch (Failure failure) { result = Map.of("requestId", id.toString(), "status", "failed", "code", failure.code); }
            catch (Exception ignored) { result = Map.of("requestId", id.toString(), "status", "failed", "code", "RESULT_UNKNOWN"); }
            candidate.result.complete(result); candidate.completed = Instant.now();
        });
        return get(id);
    }
    Map<String, String> get(UUID id) {
        Request request = requests.get(id);
        Failure.require(request != null, "RESULT_NOT_FOUND");
        return request.result.getNow(Map.of("requestId", id.toString(), "status", "running"));
    }
    @Override public void close() { executor.shutdownNow(); }
}
