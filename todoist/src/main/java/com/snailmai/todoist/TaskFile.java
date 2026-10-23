package com.snailmai.todoist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.*;

/** 只读解析任务文件供列表展示；不写文件、不改格式、不记录任务内容日志。 */
final class TaskFile {
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
        // 与写入共用字段层级解析，备注续行不再被当成任务状态／期限；不收紧历史只读校验。
        TaskDocument document = new TaskDocument(text.getBytes(StandardCharsets.UTF_8), false);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (TaskDocument.Block block : document.blocks) {
            if (!block.fields().getOrDefault("状态", "待办").strip().equals("待办")) continue;
            String due = block.fields().getOrDefault("截止时间", "未设置").strip();
            String display = due.equals("未设置") ? "无期限" : due;
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", block.number()); task.put("content", block.name().strip()); task.put("priority", block.priority());
            task.put("due", display); task.put("overdue", isOverdue(due, today));
            task.put("key", block.key()); task.put("version", block.version());
            task.put("note", block.field("备注"));
            tasks.add(task);
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
