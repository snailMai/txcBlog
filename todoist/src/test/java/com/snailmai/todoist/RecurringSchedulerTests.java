package com.snailmai.todoist;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class RecurringSchedulerTests {
    final JsonMapper json = JsonMapper.builder().build();
    @Test void startsWithoutBrowserAndDoesNotEnqueueOverlappingChecks() throws Exception {
        var began = new CountDownLatch(1); var release = new CompletableFuture<String>(); var calls = new AtomicInteger();
        try (var queue = new RelayRequests(input -> { calls.incrementAndGet(); assertEquals("recurring", json.readTree(input).path("operation").asText()); began.countDown(); return release.get(); });
             var scheduler = new RecurringScheduler(queue, code -> fail(code))) {
            scheduler.start(); scheduler.start(); assertTrue(began.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) scheduler.tick();
            assertEquals(1, calls.get()); release.complete("检查结束");
        } finally { release.complete("结束"); }
    }
    @Test void webOperationAndPeriodicCheckUseSameFifo() throws Exception {
        var release = new CompletableFuture<String>(); var order = Collections.synchronizedList(new ArrayList<String>());
        try (var queue = new RelayRequests(input -> { order.add(input); if (input.equals("web")) return release.get(); return "周期完成"; });
             var scheduler = new RecurringScheduler(queue, code -> fail(code))) {
            UUID first = UUID.randomUUID(); queue.submit(first, "web"); scheduler.tick();
            release.complete("网页完成");
            for (int i = 0; i < 200 && order.size() < 2; i++) Thread.sleep(5);
            assertEquals(2, order.size()); assertEquals("web", order.getFirst());
            assertEquals("recurring", json.readTree(order.get(1)).path("operation").asText());
        } finally { release.complete("结束"); }
    }
    @Test void knownFailureRetriesOnLaterTickAndOnlyLogsSafeCode() throws Exception {
        var ids = Collections.synchronizedList(new ArrayList<UUID>()); var errors = new ArrayList<String>(); var calls = new AtomicInteger();
        try (var queue = new RelayRequests(input -> { ids.add(UUID.fromString(json.readTree(input).path("requestId").asText())); calls.incrementAndGet(); throw new Failure("FILE_LOCKED"); });
             var scheduler = new RecurringScheduler(queue, errors::add)) {
            scheduler.tick(); awaitCall(ids, queue, 1); scheduler.tick();
            assertEquals(List.of("FILE_LOCKED"), errors);
            scheduler.tick(); awaitCall(ids, queue, 2); scheduler.tick();
            assertEquals(2, calls.get()); assertNotEquals(ids.get(0), ids.get(1)); assertEquals(List.of("FILE_LOCKED"), errors);
        }
    }
    @Test void unknownResultDoesNotResubmitUntilOriginalReceiptResolves() throws Exception {
        var ids = Collections.synchronizedList(new ArrayList<UUID>()); var recovered = new AtomicBoolean(); var errors = new ArrayList<String>();
        try (var queue = new RelayRequests(input -> { ids.add(UUID.fromString(json.readTree(input).path("requestId").asText())); throw new Failure("RECOVERY_REQUIRED"); },
                id -> recovered.get() ? Map.of("requestId", id.toString(), "status", "completed", "output", "已恢复") : null);
             var scheduler = new RecurringScheduler(queue, errors::add)) {
            scheduler.tick(); awaitCall(ids, queue, 1);
            for (int i = 0; i < 10; i++) scheduler.tick();
            assertEquals(1, ids.size()); assertEquals(List.of("RECOVERY_REQUIRED"), errors);
            recovered.set(true); scheduler.tick();
            for (int i = 0; i < 200 && ids.size() < 2; i++) Thread.sleep(5);
            assertEquals(2, ids.size());
        }
    }
    @Test void unexpectedExceptionNeverExposesTaskTextToErrorSink() throws Exception {
        var ids = Collections.synchronizedList(new ArrayList<UUID>()); var errors = new ArrayList<String>();
        try (var queue = new RelayRequests(input -> { ids.add(UUID.fromString(json.readTree(input).path("requestId").asText())); throw new IOException("private-task-name"); });
             var scheduler = new RecurringScheduler(queue, errors::add)) {
            scheduler.tick(); awaitCall(ids, queue, 1); scheduler.tick(); scheduler.tick();
            assertEquals(List.of("RESULT_UNKNOWN"), errors); assertEquals(1, ids.size());
        }
    }
    @Test void closingSchedulerPreventsFurtherSubmission() {
        var calls = new AtomicInteger();
        try (var queue = new RelayRequests(input -> { calls.incrementAndGet(); return "不应执行"; });
             var scheduler = new RecurringScheduler(queue, code -> fail(code))) {
            scheduler.close(); scheduler.start(); scheduler.tick(); assertEquals(0, calls.get());
        }
    }
    static void awaitCall(List<UUID> ids, RelayRequests queue, int expected) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (ids.size() >= expected) {
                String status = queue.get(ids.getLast()).get("status").toString();
                if (!Set.of("queued", "running").contains(status)) return;
            }
            Thread.sleep(5);
        }
        fail("合成操作未结束");
    }
}
