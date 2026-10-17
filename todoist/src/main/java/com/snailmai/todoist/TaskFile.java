package com.snailmai.todoist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.*;

/** 只读解析任务文件供列表展示；不写文件、不改格式、不记录任务内容日志。 */
final class TaskFile {
    // 任务行「N. [P1] 内容」：全局连续编号，优先级以行内标签为准，不依赖分节。
    private static final Pattern TASK_LINE = Pattern.compile("^(\\d+)\\. \\[(P[0-3])\\] (.+)$");
    private static final Pattern SECTION = Pattern.compile("^#\\s");
    private static final Pattern STATUS_FIELD = Pattern.compile("^\\s+- 状态：(.+)$");
    private static final Pattern DUE_FIELD = Pattern.compile("^\\s+- 截止时间：(.+)$");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final DateTimeFormatter ISO_STRICT = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final long MAX_BYTES = 1024 * 1024;

    private TaskFile() {}

    /** 只返回状态为「待办」的任务；截止时间「未设置」显示为「无期限」，其余原样展示并尽力判断逾期。 */
    static List<Map<String, Object>> read(Path path) {
        Failure.require(path != null && path.isAbsolute(), "TASKS_UNAVAILABLE");
        final String text;
        try {
            Failure.require(Files.isRegularFile(path) && Files.size(path) <= MAX_BYTES, "TASKS_UNAVAILABLE");
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException ignored) {
            throw new Failure("TASKS_UNAVAILABLE");
        }
        record Draft(int id, String priority, String content, String status, String due) {}
        List<Draft> drafts = new ArrayList<>();
        int id = -1; String priority = null; String content = null; String status = "待办"; String due = "未设置";
        // 每段任务以任务行开始，遇到下一个任务行或分节标题结束。
        for (String line : text.split("\n", -1)) {
            Matcher task = TASK_LINE.matcher(line);
            if (task.matches()) {
                if (id >= 0) drafts.add(new Draft(id, priority, content, status, due));
                id = Integer.parseInt(task.group(1)); priority = task.group(2); content = task.group(3).strip();
                status = "待办"; due = "未设置";
                continue;
            }
            if (id < 0) continue;
            if (SECTION.matcher(line).find()) {
                drafts.add(new Draft(id, priority, content, status, due)); id = -1;
                continue;
            }
            Matcher field = STATUS_FIELD.matcher(line);
            if (field.matches()) { status = field.group(1).strip(); continue; }
            field = DUE_FIELD.matcher(line);
            if (field.matches()) due = field.group(1).strip();
        }
        if (id >= 0) drafts.add(new Draft(id, priority, content, status, due));
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (Draft draft : drafts) {
            if (!draft.status().equals("待办")) continue;
            String display = draft.due().equals("未设置") ? "无期限" : draft.due();
            tasks.add(Map.of("id", draft.id(), "content", draft.content(), "priority", draft.priority(),
                "due", display, "overdue", isOverdue(draft.due(), today)));
        }
        return tasks;
    }

    /** 任意时间格式原样容忍；只对含 ISO 日期的值判断逾期，今天不算逾期。 */
    private static boolean isOverdue(String due, LocalDate today) {
        Matcher match = ISO_DATE.matcher(due);
        if (!match.find()) return false;
        try {
            return LocalDate.parse(match.group(), ISO_STRICT).isBefore(today);
        } catch (Exception ignored) {
            return false;
        }
    }
}
