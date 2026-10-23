package com.snailmai.todoist;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 全部使用合成任务文件，不读取真实待办。 */
class TaskFileTests {
    final JsonMapper json = JsonMapper.builder().build();
    @TempDir Path dir;

    Path write(String name, String text) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    String sample(String status, String due) {
        return """
            # P0

            # P1

            1. [P1] 合成任务甲
               - 状态：%s
               - 创建时间：2026-09-18 23:36
               - 截止时间：%s
               - 结束时间：—
               - 备注：合成备注。

            # P3

            2. [P2] 合成任务乙
               - 状态：待办
               - 截止时间：未设置
            """.formatted(status, due);
    }

    @Test void parsesTasksWithInlinePriorityAndKeepsOnlyOpenStatus() throws Exception {
        // 编号 2 在 # P3 分节但标签为 [P2]，以行内标签为准；非待办状态不展示。
        Path file = write("open.md", sample("待办", "未设置"));
        List<Map<String, Object>> tasks = TaskFile.read(file);
        assertEquals(2, tasks.size());
        assertEquals("合成任务甲", tasks.get(0).get("content"));
        assertEquals("P1", tasks.get(0).get("priority")); assertEquals("无期限", tasks.get(0).get("due"));
        assertEquals(false, tasks.get(0).get("overdue"));
        assertTrue(tasks.get(0).get("key").toString().matches("[a-f0-9]{64}"));
        assertTrue(tasks.get(0).get("version").toString().matches("[a-f0-9]{64}"));
        assertEquals("P2", tasks.get(1).get("priority"));
        Path done = write("done.md", sample("已完成", "未设置"));
        assertEquals(1, TaskFile.read(done).size());
    }

    String nestedFieldsSample(String indent, String eol, boolean bom, String status, String noteStatus) {
        return (bom ? "\uFEFF" : "") + String.join(eol,
            "# P0", "# P1", "# P2", "1. [P2] 合成多行备注任务",
            indent + "- 状态：" + status,
            indent + "- 创建时间：2026-10-24 10:00",
            indent + "- 截止时间：未设置",
            indent + "- 结束时间：",
            indent + "- 任务目标：合成目标",
            indent + "- 完成标准：合成标准",
            indent + "- 依赖：无",
            indent + "- 备注：以下仅为备注示例",
            indent + "   - 状态：" + noteStatus,
            indent + "   - 截止时间：2000-01-01",
            "", "# P3", "");
    }

    @Test void multilineNotesCannotOverrideTaskStatusOrDueAcrossLegacyFormats() throws Exception {
        for (String indent : List.of("", "   ")) for (String eol : List.of("\n", "\r\n")) for (boolean bom : List.of(false, true)) {
            Path file = write("nested.md", nestedFieldsSample(indent, eol, bom, "待办", "已完成"));
            byte[] before = Files.readAllBytes(file);
            TaskDocument document = new TaskDocument(before, false);
            document.validateForWrite(); // 合法写入格式不能在列表中因备注而漏显示。
            var block = document.blocks.getFirst();
            var tasks = TaskFile.read(file);
            assertEquals(1, tasks.size());
            var task = tasks.getFirst();
            assertEquals(1, task.get("id"));
            assertEquals("合成多行备注任务", task.get("content"));
            assertEquals("P2", task.get("priority"));
            assertEquals("无期限", task.get("due"));
            assertEquals(false, task.get("overdue"));
            assertEquals("以下仅为备注示例\n- 状态：已完成\n- 截止时间：2000-01-01", task.get("note"));
            assertEquals(block.key(), task.get("key"));
            assertEquals(block.version(), task.get("version"));
            assertArrayEquals(before, Files.readAllBytes(file));
        }
    }

    @Test void noteStatusCannotMakeClosedTaskVisible() throws Exception {
        for (String indent : List.of("", "   ")) {
            Path file = write("closed-note.md", nestedFieldsSample(indent, "\n", false, "已完成", "待办"));
            assertTrue(TaskFile.read(file).isEmpty());
        }
    }

    @Test void httpUsesTaskFieldsInsteadOfNestedNoteExamples() throws Exception {
        Path file = write("nested-http.md", nestedFieldsSample("", "\r\n", true, "待办", "已完成"));
        byte[] before = Files.readAllBytes(file);
        var service = new TodoistHttpService(new MockEnvironment().withProperty("todoist.task-file", file.toString())
            .withProperty("todoist.archive-file", dir.resolve("unused-archive.md").toString()));
        var response = request(service, "GET", "/todoist/tasks");
        assertEquals(200, response.getStatus());
        var tasks = json.readTree(response.getContentAsString()).path("tasks");
        assertEquals(1, tasks.size());
        assertEquals("无期限", tasks.get(0).path("due").asText());
        assertFalse(tasks.get(0).path("overdue").asBoolean());
        assertTrue(tasks.get(0).path("note").asText().contains("- 状态：已完成"));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertFalse(Files.exists(dir.resolve("unused-archive.md")));
    }

    @Test void readKeepsDefaultsForMissingLegacyFields() throws Exception {
        var tasks = TaskFile.read(write("legacy-defaults.md", "# P2\n1. [P2] 合成缺字段历史任务\n"));
        assertEquals(1, tasks.size());
        assertEquals("无期限", tasks.getFirst().get("due"));
        assertEquals("", tasks.getFirst().get("note"));
    }

    @Test void dueDateJudgedAgainstTodayAndUnknownFormatsTolerated() throws Exception {
        LocalDate today = LocalDate.now();
        Path past = write("past.md", sample("待办", today.minusDays(1) + " 18:00"));
        assertEquals(true, TaskFile.read(past).get(0).get("overdue"));
        assertEquals(today.minusDays(1) + " 18:00", TaskFile.read(past).get(0).get("due"));
        Path now = write("now.md", sample("待办", today.toString()));
        assertEquals(false, TaskFile.read(now).get(0).get("overdue"));
        Path future = write("future.md", sample("待办", today.plusDays(3).toString()));
        assertEquals(false, TaskFile.read(future).get(0).get("overdue"));
        // 任意非 ISO 格式原样展示，不解析也不崩溃。
        Path text = write("text.md", sample("待办", "下周五之前"));
        assertEquals(Map.of("due", "下周五之前", "overdue", false),
            Map.of("due", TaskFile.read(text).get(0).get("due"), "overdue", TaskFile.read(text).get(0).get("overdue")));
        Path invalid = write("invalid.md", sample("待办", "2026-02-30"));
        assertEquals(false, TaskFile.read(invalid).get(0).get("overdue"));
        assertEquals("2026-02-30", TaskFile.read(invalid).get(0).get("due"));
    }

    @Test void missingRelativeOrOversizedFilesAreUnavailableWithoutContentLeak() throws Exception {
        for (Path path : new Path[] { dir.resolve("missing.md"), Path.of("relative.md") }) {
            var failure = assertThrows(Failure.class, () -> TaskFile.read(path));
            assertEquals("TASKS_UNAVAILABLE", failure.code);
        }
        Path big = dir.resolve("big.md");
        Files.write(big, "x".repeat(1024 * 1024 + 1).getBytes(StandardCharsets.UTF_8));
        assertEquals("TASKS_UNAVAILABLE", assertThrows(Failure.class, () -> TaskFile.read(big)).code);
    }

    @Test void httpReturnsStructuredTasksAndRejectsOtherMethods() throws Exception {
        Path file = write("tasks.md", sample("待办", "未设置"));
        var service = new TodoistHttpService(new MockEnvironment().withProperty("todoist.task-file", file.toString())
            .withProperty("todoist.archive-file", dir.resolve("archive.md").toString()));
        var response = request(service, "GET", "/todoist/tasks");
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        var body = json.readTree(response.getContentAsString());
        assertEquals(2, body.path("tasks").size());
        assertEquals(1, body.path("tasks").get(0).path("id").asInt());
        assertEquals("合成任务甲", body.path("tasks").get(0).path("content").asText());
        assertEquals("P1", body.path("tasks").get(0).path("priority").asText());
        assertEquals("无期限", body.path("tasks").get(0).path("due").asText());
        assertEquals(false, body.path("tasks").get(0).path("overdue").asBoolean());
        assertEquals(404, request(service, "POST", "/todoist/tasks").getStatus());
        assertEquals(404, request(service, "GET", "/todoist/file").getStatus());
    }

    @Test void httpUnavailableWhenFileMissingOrNotConfigured() throws Exception {
        var missing = new TodoistHttpService(new MockEnvironment().withProperty("todoist.task-file", dir.resolve("none.md").toString()));
        var response = request(missing, "GET", "/todoist/tasks");
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("TASKS_UNAVAILABLE"));
        assertFalse(response.getContentAsString().contains("合成任务"));
        var blank = new TodoistHttpService(new MockEnvironment());
        assertEquals(503, request(blank, "GET", "/todoist/tasks").getStatus());
        // 请求参数不能改变读取路径。
        var injected = new MockHttpServletRequest("GET", "/todoist/tasks");
        injected.setParameter("path", "/private/other.md");
        var injectedResponse = new MockHttpServletResponse();
        blank.doFilter(injected, injectedResponse, new MockFilterChain());
        assertEquals(503, injectedResponse.getStatus());
    }

    @Test void commandsFlowUnchangedByTasksRoute() throws Exception {
        try (var relay = new RelayRequests(input -> "done")) {
            var service = new TodoistHttpService(new MockEnvironment()
                .withProperty("todoist.task-file", write("tasks.md", sample("待办", "未设置")).toString())
                .withProperty("todoist.archive-file", dir.resolve("archive.md").toString()), relay);
            assertEquals(200, request(service, "GET", "/todoist/tasks").getStatus());
            assertEquals(400, request(service, "GET", "/todoist/commands").getStatus());
        }
    }

    @Test void defaultTaskFilePropertyDecodesUnicodeEscapes() throws Exception {
        // 配置回归：properties 读取编码不保证 UTF-8，默认路径必须用 Unicode 转义且解码正确；不访问真实文件。
        var props = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        String expected = "/Users/macminimai/Library/CloudStorage/坚果云-15507814601@163.com/snailmaiNotes/journal/aiTodoist.md";
        assertEquals(expected, props.getProperty("todoist.task-file"));
    }

    MockHttpServletResponse request(TodoistHttpService service, String method, String path) throws Exception {
        var response = new MockHttpServletResponse();
        service.doFilter(new MockHttpServletRequest(method, path), response, new MockFilterChain());
        return response;
    }
}
