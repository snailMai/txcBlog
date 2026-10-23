package com.snailmai.todoist;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 固定时钟和合成两文件；不读取真实知识库，不启动生产调度。 */
class RecurringTasksTests {
    @TempDir Path root;
    Path todo, archive, state;
    final JsonMapper json = JsonMapper.builder().build();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-20T03:00:00Z"), ZoneOffset.UTC);
    String original() {
        return "1. [P2] 合成原有事项\n   - 状态：待办\n   - 创建时间：2026-08-01 10:00\n"
            + "   - 截止时间：未设置\n   - 结束时间：—\n   - 任务目标：保留原目标\n   - 完成标准：保留原标准\n"
            + "   - 依赖：无\n   - 备注：无\n   - 历史扩展：不得丢失\n\n";
    }
    @BeforeEach void fixture() throws Exception {
        todo = root.resolve("todo.md"); archive = root.resolve("archive.md"); state = root.resolve("state");
        Files.writeString(todo, "# P0\n\n# P1\n\n# P2\n\n" + original() + "# P3\n\n");
        Files.writeString(archive, "# Archive\n\n## 2026\n\n");
    }
    LocalTasks store() { return new LocalTasks(todo, archive, state, clock, () -> {}); }
    TaskDocument document() throws Exception { return new TaskDocument(Files.readAllBytes(todo), false); }
    String command(String operation, Map<String, String> fields, List<TaskOperation.Target> targets) {
        return json.writeValueAsString(new TaskOperation(UUID.randomUUID().toString(), operation, fields, targets, ""));
    }
    String check(LocalTasks store) throws Exception { return store.run(command("recurring", Map.of(), List.of())); }
    TaskDocument.Block transfer() throws Exception {
        return document().blocks.stream().filter(block -> block.field("备注").contains("salary-transfer/2026-10")).findFirst().orElseThrow();
    }
    String action(String operation, TaskDocument.Block block, Map<String, String> fields) {
        return command(operation, fields, List.of(new TaskOperation.Target(block.key(), block.version())));
    }
    List<RecurringTasks.Occurrence> at(String local) { return RecurringTasks.due(LocalDateTime.parse(local).atZone(LocalTasks.ZONE)); }
    long receipts() throws Exception { try (var paths = Files.list(state)) { return paths.filter(path -> path.toString().endsWith(".json")).count(); } }

    @Test void exactTenOClockAndStartingDatesMatchExistingScripts() {
        assertEquals(0, at("2026-09-15T09:59:59").size()); assertEquals(1, at("2026-09-15T10:00:00").size());
        assertEquals(1, at("2026-10-10T09:59:59").size()); assertEquals(2, at("2026-10-10T10:00:00").size());
        assertEquals(2, at("2026-10-15T09:59:59").size()); assertEquals(4, at("2026-10-15T10:00:00").size());
        assertEquals(4, at("2026-11-01T09:59:59").size()); assertEquals(5, at("2026-11-01T10:00:00").size());
        assertEquals(6, at("2026-11-15T09:59:59").size()); assertEquals(8, at("2026-11-15T10:00:00").size());
        assertFalse(at("2026-10-15T10:00:00").stream().anyMatch(item -> item.marker().contains("writing/2026-10-01")));
    }
    @Test void timezoneDoesNotDependOnMachineZone() {
        var instant = ZonedDateTime.parse("2026-10-15T02:00:00Z");
        assertEquals(at("2026-10-15T10:00:00"), RecurringTasks.due(instant));
        assertEquals(RecurringTasks.due(instant), RecurringTasks.due(instant.withZoneSameInstant(ZoneId.of("America/New_York"))));
    }
    @Test void crossedYearsAndLeapMonthKeepFirstAndFifteenthRules() {
        var due = at("2028-03-01T10:00:00");
        assertTrue(due.stream().anyMatch(item -> item.marker().equals("twice-monthly-writing/2028-02-15")));
        assertTrue(due.stream().anyMatch(item -> item.marker().equals("salary-transfer/2028-01")));
        assertFalse(due.stream().anyMatch(item -> item.marker().contains("2028-02-29")));
        assertEquals(due.size(), due.stream().map(RecurringTasks.Occurrence::marker).distinct().count());
    }
    @Test void generatesAllMissingPeriodsWithOriginalTemplatesAndActualCreationTime() throws Exception {
        byte[] oldArchive = Files.readAllBytes(archive);
        assertTrue(check(store()).contains("新增 4 项"));
        var doc = document(); assertEquals(5, doc.blocks.size()); doc.validateForWrite();
        for (var block : doc.blocks) if (block.priority().equals("P1")) {
            assertEquals("2026-10-20 11:00", block.field("创建时间"));
            assertEquals("未设置", block.field("截止时间")); assertEquals("", block.field("结束时间"));
            assertTrue(block.field("备注").startsWith("周期标识："));
            assertNotEquals("完成任务所述事项，并核对结果。", block.field("完成标准"));
        }
        assertEquals("完成 2026-10 对应的工资资金转移，并核对到账结果。", transfer().field("任务目标"));
        assertTrue(transfer().field("备注").contains("计划生成时间：2026-10-10 10:00"));
        assertEquals(TaskDocument.withoutNumber(original()), TaskDocument.withoutNumber(doc.blocks.getLast().raw()));
        assertArrayEquals(oldArchive, Files.readAllBytes(archive));
    }
    @Test void repeatedChecksAndRestartDoNotRewriteFilesOrAccumulateEmptyReceipts() throws Exception {
        check(store()); byte[] before = Files.readAllBytes(todo), history = Files.readAllBytes(archive);
        var modified = Files.getLastModifiedTime(todo); long count = receipts();
        for (int i = 0; i < 15; i++) assertTrue(check(store()).contains("新增 0 项"));
        assertEquals(1, count); assertEquals(count, receipts());
        assertArrayEquals(before, Files.readAllBytes(todo)); assertArrayEquals(history, Files.readAllBytes(archive));
        assertEquals(modified, Files.getLastModifiedTime(todo));
    }
    @Test void noDuePeriodsDoesNotCreateReceiptsOrChangeData() throws Exception {
        byte[] before = Files.readAllBytes(todo);
        LocalTasks early = new LocalTasks(todo, archive, state, Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC), () -> {});
        assertTrue(check(early).contains("新增 0 项")); assertEquals(0, receipts());
        assertArrayEquals(before, Files.readAllBytes(todo));
    }
    @Test void sameTitleWithoutMarkerIsRecognizedRegardlessOfPriority() throws Exception {
        Files.writeString(todo, Files.readString(todo).replace("合成原有事项", "工资转移（2026-10）"));
        assertTrue(check(store()).contains("新增 3 项"));
        assertEquals(1, document().blocks.stream().filter(block -> block.name().equals("工资转移（2026-10）")).count());
    }
    @Test void renameAndNoteEditKeepIdentityThenCompletionDoesNotRegeneratePeriod() throws Exception {
        LocalTasks store = store(); check(store);
        store.run(action("edit", transfer(), Map.of("name", "核对合成转移结果", "note", "我补充的新备注")));
        var edited = transfer(); assertEquals("核对合成转移结果", edited.name());
        assertEquals("周期标识：salary-transfer/2026-10；我补充的新备注", edited.field("备注"));
        assertTrue(check(store).contains("新增 0 项"));
        store.run(action("complete", edited, Map.of()));
        assertTrue(check(store()).contains("新增 0 项"));
        assertEquals(4, document().blocks.size());
    }
    @Test void removedAndAbandonedArchivedPeriodsAreNotGeneratedAgain() throws Exception {
        LocalTasks store = store(); check(store); store.run(action("remove", transfer(), Map.of()));
        assertTrue(check(store()).contains("新增 0 项"));
        Files.writeString(archive, Files.readString(archive).replace("状态：已移除", "状态：已放弃"));
        assertTrue(check(store()).contains("新增 0 项"));
    }
    @Test void newMonthStillGeneratedWhenPreviousTasksRemainPending() throws Exception {
        check(store()); int before = document().blocks.size();
        LocalTasks november = new LocalTasks(todo, archive, state, Clock.fixed(Instant.parse("2026-11-15T02:00:00Z"), ZoneOffset.UTC), () -> {});
        assertTrue(check(november).contains("新增 4 项")); assertEquals(before + 4, document().blocks.size());
        assertEquals(1, document().blocks.stream().filter(block -> block.name().equals("工资转移（2026-10）")).count());
    }
    @Test void duplicatePeriodAcrossTodoAndArchiveStopsWholeBatchWithoutWrites() throws Exception {
        check(store()); var block = transfer();
        Files.writeString(archive, Files.readString(archive) + block.raw().replaceFirst("^\\d+\\. ", "### ").replaceAll("(?m)^   ", "")
            .replace("- 状态：待办", "- 状态：已完成").replace("- 结束时间：\n", "- 结束时间：2026-10-20 12:00\n"));
        byte[] before = Files.readAllBytes(todo), history = Files.readAllBytes(archive); long count = receipts();
        assertEquals("DUPLICATE_TASK", assertThrows(Failure.class, () -> check(store())).code);
        assertArrayEquals(before, Files.readAllBytes(todo)); assertArrayEquals(history, Files.readAllBytes(archive)); assertEquals(count, receipts());
    }
    @Test void lockConflictDoesNotWriteAndNextCheckCanSucceed() throws Exception {
        byte[] before = Files.readAllBytes(todo);
        try (var channel = FileChannel.open(archive, StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertEquals("FILE_LOCKED", assertThrows(Failure.class, () -> check(store())).code);
        }
        assertArrayEquals(before, Files.readAllBytes(todo)); assertTrue(check(store()).contains("新增 4 项"));
    }
    @Test void interruptedGenerationRecoversOriginalTransactionWithoutDuplicates() throws Exception {
        var interrupted = new LocalTasks(todo, archive, state, clock, () -> { throw new IOException("synthetic-secret"); });
        String command = command("recurring", Map.of(), List.of());
        assertEquals("RECOVERY_REQUIRED", assertThrows(Failure.class, () -> interrupted.run(command)).code);
        assertEquals(1, document().blocks.size());
        UUID id = UUID.fromString(json.readTree(command).path("requestId").asText());
        assertEquals("completed", store().lookup(id).get("status"));
        assertEquals(5, document().blocks.size()); assertTrue(check(store()).contains("新增 0 项"));
    }
    @Test void interruptedArchivingIsRecoveredBeforeNextPeriodicCheck() throws Exception {
        check(store()); var interrupted = new LocalTasks(todo, archive, state, clock, () -> { throw new IOException(); });
        assertEquals("RECOVERY_REQUIRED", assertThrows(Failure.class, () -> interrupted.run(action("complete", transfer(), Map.of()))).code);
        assertTrue(check(store()).contains("新增 0 项")); assertEquals(4, document().blocks.size());
        assertEquals(1, new TaskDocument(Files.readAllBytes(archive), true).blocks.size());
    }
    @Test void externalChangesDuringRecoveryAreNeverOverwrittenByPeriodicCheck() throws Exception {
        var interrupted = new LocalTasks(todo, archive, state, clock, () -> { throw new IOException(); });
        assertThrows(Failure.class, () -> check(interrupted));
        Files.writeString(todo, Files.readString(todo).replace("合成原有事项", "外部合成修改"));
        byte[] changed = Files.readAllBytes(todo);
        assertEquals("RECOVERY_REQUIRED", assertThrows(Failure.class, () -> check(store())).code);
        assertArrayEquals(changed, Files.readAllBytes(todo));
    }
    @Test void mixedLegacyIndentBomCrlfAndHistoricalFieldsArePreserved() throws Exception {
        Files.writeString(todo, "\uFEFF" + Files.readString(todo).replace("   - ", "- ").replace("\n", "\r\n"));
        check(store()); byte[] bytes = Files.readAllBytes(todo); assertEquals((byte)239, bytes[0]);
        String text = Files.readString(todo); assertFalse(text.replace("\r\n", "").contains("\n"));
        assertTrue(text.contains("\r\n- 历史扩展：不得丢失\r\n"));
        assertTrue(text.contains("   - 结束时间：\r\n"));
    }
    @Test void previewIsPureAndDoesNotCreateFilesOrChangeDocuments() throws Exception {
        byte[] before = Files.readAllBytes(todo), history = Files.readAllBytes(archive);
        assertEquals(4, RecurringTasks.missing(document(), new TaskDocument(history, true), ZonedDateTime.now(clock)).size());
        var preview = store().previewRecurring();
        assertEquals(4, preview.get("missingCount")); assertEquals("Asia/Shanghai", preview.get("timezone"));
        assertArrayEquals(before, Files.readAllBytes(todo)); assertArrayEquals(history, Files.readAllBytes(archive)); assertFalse(Files.exists(state));
    }
    @Test void privateHttpPreviewNeverStartsGenerationOrCreatesState() throws Exception {
        byte[] before = Files.readAllBytes(todo), history = Files.readAllBytes(archive);
        var environment = new org.springframework.mock.env.MockEnvironment().withProperty("todoist.task-file", todo.toString())
            .withProperty("todoist.archive-file", archive.toString()).withProperty("todoist.state-dir", state.toString());
        var service = new TodoistHttpService(environment);
        var response = new TodoistHttpTests().request(service, "GET", "/todoist/recurring/preview", null, null);
        assertEquals(200, response.getStatus()); assertEquals("Asia/Shanghai", json.readTree(response.getContentAsString()).path("timezone").asText());
        assertFalse(Files.exists(state)); assertArrayEquals(before, Files.readAllBytes(todo)); assertArrayEquals(history, Files.readAllBytes(archive));
        service.close();
    }
    @Test void machineMarkerCannotBeReplacedButOrdinaryNotesStayUnchanged() {
        String previous = "周期标识：salary-transfer/2026-10；计划生成时间：2026-10-10 10:00";
        assertEquals("周期标识：salary-transfer/2026-10", RecurringTasks.preserveMarker(previous, "无"));
        assertEquals("普通备注", RecurringTasks.preserveMarker("无", "普通备注"));
        assertEquals("INVALID_REQUEST", assertThrows(Failure.class,
            () -> RecurringTasks.preserveMarker(previous, "周期标识：salary-transfer/2026-11")).code);
    }
    @Test void multilineNotesKeepOneMarkerAndDoNotTruncateLookalikeUserText() {
        String prefix = "周期标识：salary-transfer/2026-10";
        assertEquals(prefix + "；第一行\n第二行", RecurringTasks.preserveMarker(prefix, prefix + "\n第一行\n第二行"));
        assertEquals(prefix + "；" + prefix + "-引用文本", RecurringTasks.preserveMarker(prefix, prefix + "-引用文本"));
    }
    @Test void internalOperationCannotBeSubmittedByPublicClientOrContainUserFields() {
        var body = json.readTree(command("recurring", Map.of(), List.of()));
        assertEquals("INVALID_REQUEST", assertThrows(Failure.class, () -> TaskOperation.parse(body)).code);
        assertEquals("recurring", TaskOperation.parseInternal(body).operation());
        assertThrows(Failure.class, () -> TaskOperation.parseInternal(json.readTree(command("recurring", Map.of("name", "不能注入"), List.of()))));
    }
}
