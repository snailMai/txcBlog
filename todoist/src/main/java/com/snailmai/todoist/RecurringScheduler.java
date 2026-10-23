package com.snailmai.todoist;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;

/** 应用内部每分钟投递一次检查；不创建系统任务，不越过网页 FIFO 直接写文件。 */
final class RecurringScheduler implements AutoCloseable {
    private final RelayRequests queue;
    private final Consumer<String> errors;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("todoist-recurring").factory());
    private final JsonMapper json = JsonMapper.builder().build();
    private UUID pending;
    private boolean started, closed;
    private String lastError = "";

    RecurringScheduler(RelayRequests queue, Consumer<String> errors) { this.queue = queue; this.errors = errors; }
    synchronized void start() {
        if (started || closed) return; started = true;
        timer.scheduleWithFixedDelay(this::tick, 0, 60, TimeUnit.SECONDS);
    }
    synchronized void tick() {
        if (closed) return;
        try {
            if (pending != null) {
                var result = queue.get(pending);
                String status = result.get("status").toString();
                if (Set.of("queued", "running").contains(status)) return;
                if (status.equals("unknown")) { report(result.get("code").toString()); return; }
                pending = null;
                if (status.equals("failed")) { report(result.get("code").toString()); return; }
                lastError = "";
            }
            UUID id = UUID.randomUUID();
            String command = json.writeValueAsString(new TaskOperation(id.toString(), "recurring", Map.of(), List.of(), ""));
            queue.submit(id, command); pending = id;
        } catch (Failure failure) { report(failure.code); }
        catch (Exception ignored) { report("RESULT_UNKNOWN"); }
    }
    private void report(String code) {
        String safe = code.matches("[A-Z_]{1,64}") ? code : "RESULT_UNKNOWN";
        if (!safe.equals(lastError)) { lastError = safe; errors.accept(safe); }
    }
    @Override public synchronized void close() { closed = true; timer.shutdownNow(); }
}
