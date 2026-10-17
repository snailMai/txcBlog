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
        assertEquals(Map.of("id", 1, "content", "合成任务甲", "priority", "P1", "due", "无期限", "overdue", false), tasks.get(0));
        assertEquals("P2", tasks.get(1).get("priority"));
        Path done = write("done.md", sample("已完成", "未设置"));
        assertEquals(1, TaskFile.read(done).size());
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
        var service = new TodoistHttpService(new MockEnvironment().withProperty("todoist.task-file", file.toString()));
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
                .withProperty("todoist.task-file", write("tasks.md", sample("待办", "未设置")).toString()), relay);
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
