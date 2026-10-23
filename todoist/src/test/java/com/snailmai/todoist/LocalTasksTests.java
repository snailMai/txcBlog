package com.snailmai.todoist;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 所有写入均为独立合成文件；不读取用户待办，也不连接桌面应用。 */
class LocalTasksTests {
    @TempDir Path root;
    final JsonMapper json = JsonMapper.builder().build();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-20T03:00:00Z"), ZoneOffset.UTC);
    Path todo, archive, state;
    String task(int number, String priority, String name, String end) {
        return number + ". [" + priority + "] " + name + "\n"
            + "   - 状态：待办\n   - 创建时间：2026-10-01 10:00\n   - 截止时间：未设置\n   - 结束时间：" + end + "\n"
            + "   - 任务目标：合成目标\n   - 完成标准：合成标准\n   - 依赖：无\n   - 备注：无\n"
            + "   - 历史扩展字段：不得丢失\n\n";
    }
    LocalTasks fixture() throws Exception {
        todo = root.resolve("todo.md"); archive = root.resolve("archive.md"); state = root.resolve("private");
        Files.writeString(todo, "# P0\n\n# P1\n\n" + task(1, "P1", "核对备份结果", "—")
            + "# P2\n\n" + task(2, "P2", "整理本周文章提纲", "") + "# P3\n\n" + task(3, "P3", "整理旧照片", "—"));
        Files.writeString(archive, "# AI Todoist Archive\n\n## 2026\n\n");
        return store();
    }
    LocalTasks store() { return new LocalTasks(todo, archive, state, clock, () -> {}); }
    Map<String, String> addFields(String name) { return Map.of("name", name, "goal", "明确的合成目标", "criteria", "明确的合成标准"); }
    TaskOperation command(String operation, Map<String, String> fields, List<TaskOperation.Target> targets) {
        return TaskOperation.parse(json.valueToTree(Map.of("requestId", UUID.randomUUID().toString(), "operation", operation, "fields", fields, "targets", targets)));
    }
    String run(LocalTasks store, TaskOperation operation) throws Exception { return store.run(json.writeValueAsString(operation)); }
    List<TaskOperation.Target> targets(int... numbers) throws Exception {
        TaskDocument document = new TaskDocument(Files.readAllBytes(todo), false);
        return document.blocks.stream().filter(block -> Arrays.stream(numbers).anyMatch(number -> number == block.number()))
            .map(block -> new TaskOperation.Target(block.key(), block.version())).toList();
    }

    @Test void addPreservesBomCrlfFieldsAndUsesShanghaiClockWithBlankEnd() throws Exception {
        LocalTasks store = fixture();
        Files.writeString(todo, "\uFEFF" + Files.readString(todo).replace("\n", "\r\n"));
        String untouched = new TaskDocument(Files.readAllBytes(todo), false).blocks.getFirst().version();
        assertTrue(run(store, command("add", addFields("合成新增任务"), List.of())).contains("验证通过"));
        byte[] bytes = Files.readAllBytes(todo); assertEquals((byte)239, bytes[0]);
        String text = Files.readString(todo); assertFalse(text.replace("\r\n", "").contains("\n"));
        TaskDocument after = new TaskDocument(bytes, false); assertEquals(4, after.blocks.size());
        var added = after.blocks.stream().filter(block -> block.name().equals("合成新增任务")).findFirst().orElseThrow();
        assertEquals("P2", added.priority()); assertEquals("2026-10-20 11:00", added.field("创建时间"));
        assertEquals("", added.field("结束时间")); assertEquals("未设置", added.field("截止时间"));
        assertEquals("无", added.field("备注")); assertEquals(untouched, after.blocks.getFirst().version());
        String receipt = Files.readString(state.resolve(Files.list(state).filter(path -> path.toString().endsWith(".json")).findFirst().orElseThrow().getFileName()));
        assertFalse(receipt.contains("beforeTodo")); assertFalse(receipt.contains("合成新增任务"));
    }
    @Test void legacyUnindentedFieldsAreReadableAndEditableWithoutReformattingOthers() throws Exception {
        LocalTasks store = fixture();
        Files.writeString(todo, Files.readString(todo).replace(task(1, "P1", "核对备份结果", "—"),
            task(1, "P1", "核对备份结果", "—").replace("   - ", "- ")));
        var original = new TaskDocument(Files.readAllBytes(todo), false);
        original.validateForWrite();
        run(store, command("edit", Map.of("note", "第一行\n第二行"), targets(1)));
        var edited = new TaskDocument(Files.readAllBytes(todo), false);
        assertEquals("", edited.blocks.getFirst().indent());
        assertEquals("第一行\n第二行", edited.blocks.getFirst().field("备注"));
        assertTrue(edited.blocks.getFirst().raw().contains("\n- 备注：第一行\n   第二行\n"));
        assertEquals("第一行\n第二行", store.list().getFirst().get("note"));
        assertEquals(original.blocks.get(1).raw(), edited.blocks.get(1).raw());
        run(store, command("add", Map.of("name", "独立合成事项"), List.of()));
        run(store, command("complete", Map.of(), targets(1)));
        var archived = new TaskDocument(Files.readAllBytes(archive), true).blocks.getFirst();
        assertEquals("已完成", archived.field("状态"));
        assertEquals("第一行\n第二行", archived.field("备注"));
        assertEquals("不得丢失", archived.field("历史扩展字段"));
        assertEquals("2026-10-01 10:00", archived.field("创建时间"));
    }
    @Test void editOnlyExplicitFieldsAndMovePriorityWithoutLosingHistoricalData() throws Exception {
        LocalTasks store = fixture(); String before = new TaskDocument(Files.readAllBytes(todo), false).blocks.get(1).raw();
        run(store, command("edit", Map.of("note", "第一行\n第二行"), targets(2)));
        var modified = new TaskDocument(Files.readAllBytes(todo), false).blocks.get(1);
        assertEquals("第一行\n第二行", modified.field("备注"));
        assertEquals(before.replace("   - 备注：无", "   - 备注：第一行\n      第二行"), modified.raw());
        run(store, command("edit", Map.of("name", "修订合成文章提纲", "priority", "P0"), targets(2)));
        var after = new TaskDocument(Files.readAllBytes(todo), false); assertEquals("P0", after.blocks.getFirst().priority());
        assertEquals("2026-10-01 10:00", after.blocks.getFirst().field("创建时间"));
        assertEquals("不得丢失", after.blocks.getFirst().field("历史扩展字段"));
        assertEquals("第一行\n第二行", after.blocks.getFirst().field("备注"));
    }
    @Test void completeArchivesBeforeRemovalAndRetainsCreatedFieldsAndBlankEndCompatibility() throws Exception {
        LocalTasks store = fixture();
        run(store, command("complete", Map.of(), targets(1, 2)));
        var after = new TaskDocument(Files.readAllBytes(todo), false);
        var history = new TaskDocument(Files.readAllBytes(archive), true);
        assertEquals(1, after.blocks.size()); assertEquals(1, after.blocks.getFirst().number());
        assertEquals(2, history.blocks.size());
        for (var block : history.blocks) {
            assertEquals("已完成", block.field("状态")); assertEquals("2026-10-01 10:00", block.field("创建时间"));
            assertEquals("2026-10-20 11:00", block.field("结束时间")); assertEquals("不得丢失", block.field("历史扩展字段"));
            assertTrue(block.raw().startsWith("### [")); assertTrue(block.raw().contains("\n- 任务目标："));
        }
    }
    @Test void abandonedHistoricalArchiveRemainsUnchangedAndStillParticipatesInDedup() throws Exception {
        LocalTasks store = fixture();
        String old = "### [P3] 历史放弃事项\n\n- 状态：已放弃\n- 创建时间：2025-01-01 10:00\n- 结束时间：2025-01-02 10:00\n\n";
        Files.writeString(archive, Files.readString(archive) + old);
        byte[] before = Files.readAllBytes(archive);
        run(store, command("add", Map.of("name", "独立新增事项"), List.of()));
        assertArrayEquals(before, Files.readAllBytes(archive));
        assertEquals("DUPLICATE_TASK", assertThrows(Failure.class,
            () -> run(store, command("add", Map.of("name", "历史放弃事项"), List.of()))).code);
        run(store, command("complete", Map.of(), targets(1)));
        assertTrue(Files.readString(archive).contains(old));
        assertEquals("已完成", new TaskDocument(Files.readAllBytes(archive), true).blocks.getLast().field("状态"));
    }
    @Test void archiveAppendsWithinCurrentYearAndPreservesLaterHistoricalSections() throws Exception {
        LocalTasks store = fixture();
        String history = "## 2025\n\n历史说明，不得改写。\n";
        Files.writeString(archive, Files.readString(archive) + history);
        run(store, command("complete", Map.of(), targets(2)));
        String after = Files.readString(archive);
        assertTrue(after.indexOf("### [P2] 整理本周文章提纲") < after.indexOf("## 2025"));
        assertTrue(after.endsWith(history));
    }
    @Test void removalUsesDifferentArchiveStatusAndQueuedOldNumberCannotHitAnotherTask() throws Exception {
        LocalTasks store = fixture(); List<TaskOperation.Target> third = targets(3);
        run(store, command("remove", Map.of(), targets(1)));
        assertEquals("已移除", new TaskDocument(Files.readAllBytes(archive), true).blocks.getFirst().field("状态"));
        run(store, command("complete", Map.of(), third));
        assertEquals("整理本周文章提纲", new TaskDocument(Files.readAllBytes(todo), false).blocks.getFirst().name());
    }
    @Test void staleTaskVersionFailsWithoutChangingEitherFile() throws Exception {
        LocalTasks store = fixture(); List<TaskOperation.Target> old = targets(2);
        run(store, command("edit", Map.of("note", "已经改动"), old));
        byte[] before = Files.readAllBytes(todo), history = Files.readAllBytes(archive);
        assertEquals("TASK_CHANGED", assertThrows(Failure.class, () -> run(store, command("remove", Map.of(), old))).code);
        assertArrayEquals(before, Files.readAllBytes(todo)); assertArrayEquals(history, Files.readAllBytes(archive));
    }
    @Test void identicalNormalizedAndLimitedAliasNamesAreHardDuplicates() throws Exception {
        LocalTasks store = fixture();
        run(store, command("add", addFields("测试 1234"), List.of()));
        for (String name : List.of("测试 1234", "测试１２３４", "测试任务 1234", "测试\u30001234")) {
            byte[] before = Files.readAllBytes(todo);
            Failure error = assertThrows(Failure.class, () -> run(store, command("add", addFields(name), List.of())));
            assertEquals("DUPLICATE_TASK", error.code); assertTrue(error.details.containsKey("duplicates"));
            assertArrayEquals(before, Files.readAllBytes(todo));
        }
        run(store, command("add", addFields("测试 1235"), List.of()));
        assertEquals(5, new TaskDocument(Files.readAllBytes(todo), false).blocks.size());
    }
    @Test void fuzzyMatchRequiresExplicitCurrentConfirmationAndDoesNotWriteBeforeIt() throws Exception {
        LocalTasks store = fixture(); var command = command("add", addFields("整理本周文章大纲"), List.of());
        byte[] before = Files.readAllBytes(todo);
        Failure error = assertThrows(Failure.class, () -> run(store, command)); assertEquals("SIMILAR_TASK", error.code);
        assertArrayEquals(before, Files.readAllBytes(todo));
        TaskOperation confirmed = new TaskOperation(UUID.randomUUID().toString(), command.operation(), command.fields(), command.targets(), error.details.get("confirmation").toString());
        run(store, confirmed); assertEquals(4, new TaskDocument(Files.readAllBytes(todo), false).blocks.size());
    }
    @Test void oldConfirmationCannotBypassChangedCandidateSet() throws Exception {
        LocalTasks store = fixture(); var command = command("add", addFields("整理本周文章大纲"), List.of());
        Failure error = assertThrows(Failure.class, () -> run(store, command));
        run(store, command("add", addFields("独立的新事项"), List.of()));
        var stale = new TaskOperation(UUID.randomUUID().toString(), command.operation(), command.fields(), command.targets(), error.details.get("confirmation").toString());
        assertEquals("SIMILAR_TASK", assertThrows(Failure.class, () -> run(store, stale)).code);
    }
    @Test void archiveAlsoParticipatesInDedupButDifferentPeriodsRemainSeparate() throws Exception {
        LocalTasks store = fixture(); run(store, command("complete", Map.of(), targets(3)));
        assertEquals("DUPLICATE_TASK", assertThrows(Failure.class, () -> run(store, command("add", addFields("整理旧照片"), List.of()))).code);
        run(store, command("add", addFields("工资转移（2026-10）"), List.of()));
        run(store, command("add", addFields("工资转移（2026-11）"), List.of()));
        assertFalse(TaskDuplicates.similar("工资转移(2026-10)", "工资转移(2026-11)"));
        assertNotEquals(TaskDuplicates.equivalent("研究任务调度"), TaskDuplicates.equivalent("研究调度"));
        assertNotEquals(TaskDuplicates.equivalent("测试任务"), TaskDuplicates.equivalent("测试"));
    }
    @Test void hardDuplicateCandidateIsNotHiddenByEarlierFuzzyMatches() {
        String text = "# P0\n\n# P1\n\n# P2\n\n";
        for (int i = 0; i < 8; i++) text += task(i + 1, "P2", "整理本周文章提纲" + (char)('A' + i), "");
        text += task(9, "P2", "整理本周文章提纲", "") + "# P3\n";
        var check = TaskDuplicates.check("整理本周文章提纲", "", new TaskDocument(text.getBytes(StandardCharsets.UTF_8), false),
            new TaskDocument("# 归档\n".getBytes(StandardCharsets.UTF_8), true));
        assertTrue(check.exact()); assertEquals("整理本周文章提纲", check.candidates().getFirst().name());
    }
    @Test void sameRequestAfterRestartIsIdempotentAndChangedInputCannotReuseId() throws Exception {
        LocalTasks store = fixture(); var command = command("add", addFields("幂等合成事项"), List.of());
        String output = run(store, command); byte[] after = Files.readAllBytes(todo);
        assertEquals(output, run(store(), command)); assertArrayEquals(after, Files.readAllBytes(todo));
        var changed = new TaskOperation(command.requestId(), "add", addFields("另一件事项"), List.of(), "");
        assertEquals("REQUEST_MISMATCH", assertThrows(Failure.class, () -> run(store(), changed)).code);
        assertEquals("completed", store().lookup(UUID.fromString(command.requestId())).get("status"));
    }
    @Test void archiveInterruptionIsRecoveredWithoutDuplicatingOrResettingEndTime() throws Exception {
        fixture(); var command = command("complete", Map.of(), targets(2));
        var interrupted = new LocalTasks(todo, archive, state, clock, () -> { throw new IOException("synthetic-private-value"); });
        assertEquals("RECOVERY_REQUIRED", assertThrows(Failure.class, () -> run(interrupted, command)).code);
        assertEquals(3, new TaskDocument(Files.readAllBytes(todo), false).blocks.size());
        assertEquals(1, new TaskDocument(Files.readAllBytes(archive), true).blocks.size());
        var result = store().lookup(UUID.fromString(command.requestId())); assertEquals("completed", result.get("status"));
        assertEquals(2, new TaskDocument(Files.readAllBytes(todo), false).blocks.size());
        assertEquals(1, new TaskDocument(Files.readAllBytes(archive), true).blocks.size());
        assertEquals(result, store().lookup(UUID.fromString(command.requestId())));
    }
    @Test void externalChangesDuringRecoveryAreNeverOverwrittenAndBlockNewWrites() throws Exception {
        fixture(); var command = command("remove", Map.of(), targets(2));
        var interrupted = new LocalTasks(todo, archive, state, clock, () -> { throw new IOException(); });
        assertThrows(Failure.class, () -> run(interrupted, command));
        Files.writeString(todo, Files.readString(todo).replace("核对备份结果", "外部明确修改的任务"));
        byte[] changed = Files.readAllBytes(todo);
        assertEquals("unknown", store().lookup(UUID.fromString(command.requestId())).get("status"));
        assertEquals("RECOVERY_REQUIRED", assertThrows(Failure.class, () -> run(store(), command("add", addFields("不应写入"), List.of()))).code);
        assertArrayEquals(changed, Files.readAllBytes(todo));
    }
    @Test void archiveLockAndMalformedTaskFailBeforeAnyWrite() throws Exception {
        LocalTasks store = fixture(); byte[] before = Files.readAllBytes(todo), history = Files.readAllBytes(archive);
        try (FileChannel channel = FileChannel.open(archive, StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertEquals("FILE_LOCKED", assertThrows(Failure.class, () -> run(store, command("add", addFields("锁测试"), List.of()))).code);
        }
        assertArrayEquals(before, Files.readAllBytes(todo)); assertArrayEquals(history, Files.readAllBytes(archive));
        Files.writeString(todo, Files.readString(todo).replace("   - 完成标准：合成标准\n", ""));
        assertEquals("TASK_FORMAT_INVALID", assertThrows(Failure.class, () -> run(store, command("add", addFields("格式测试"), List.of()))).code);
    }
    @Test void nameOnlyUsesBackendDefaultsWithoutRequiringFrontendFields() throws Exception {
        LocalTasks store = fixture();
        run(store, command("add", Map.of("name", "后端默认字段验证"), List.of()));
        var added = new TaskDocument(Files.readAllBytes(todo), false).blocks.stream()
            .filter(block -> block.name().equals("后端默认字段验证")).findFirst().orElseThrow();
        assertEquals("完成「后端默认字段验证」。", added.field("任务目标"));
        assertEquals("完成任务所述事项，并核对结果。", added.field("完成标准"));
        assertEquals("无", added.field("依赖")); assertEquals("", added.field("结束时间"));
    }
    @Test void malformedArchiveAndWrongPrioritySectionNeverWrite() throws Exception {
        LocalTasks store = fixture();
        Files.writeString(archive, "# 归档\n\n### [P2] 合成异常归档\n\n- 状态：已完成\n");
        byte[] before = Files.readAllBytes(todo), history = Files.readAllBytes(archive);
        assertEquals("TASK_FORMAT_INVALID", assertThrows(Failure.class,
            () -> run(store, command("add", addFields("不应新增"), List.of()))).code);
        assertArrayEquals(before, Files.readAllBytes(todo)); assertArrayEquals(history, Files.readAllBytes(archive));
        Files.writeString(archive, "# 归档\n");
        Files.writeString(todo, Files.readString(todo).replace("1. [P1]", "1. [P0]"));
        before = Files.readAllBytes(todo);
        assertEquals("TASK_FORMAT_INVALID", assertThrows(Failure.class,
            () -> run(store, command("add", addFields("不应新增"), List.of()))).code);
        assertArrayEquals(before, Files.readAllBytes(todo));
    }
    @Test void structuredContractRejectsPathsRawCommandsAndEmptyRequiredFields() {
        for (Map<String, Object> value : List.<Map<String, Object>>of(
            Map.of("requestId", UUID.randomUUID().toString(), "operation", "add", "fields", Map.of("name", "")),
            Map.of("requestId", UUID.randomUUID().toString(), "operation", "add", "fields", addFields("第一行\n# P0")),
            Map.of("requestId", UUID.randomUUID().toString(), "operation", "shell", "path", "/private")))
            assertThrows(Failure.class, () -> TaskOperation.parse(json.valueToTree(value)));
        var old = assertThrows(Failure.class, () -> TaskOperation.parse(json.valueToTree(Map.of("requestId", UUID.randomUUID().toString(), "input", "添加任务：任务"))));
        assertEquals("CLIENT_UPDATE_REQUIRED", old.code);
    }
}
