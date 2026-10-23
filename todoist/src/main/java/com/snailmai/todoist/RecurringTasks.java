package com.snailmai.todoist;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** 三个固定周期的纯计算与模板；规则来自现有脚本，不调用脚本或模型。 */
final class RecurringTasks {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern MARKER = Pattern.compile("周期标识：((?:salary-transfer|salary-adjustment)/\\d{4}-\\d{2}|twice-monthly-writing/\\d{4}-\\d{2}-\\d{2})(?=；|\\s|$)");
    record Occurrence(String marker, LocalDateTime due, Map<String, String> fields) {}

    static List<Occurrence> due(ZonedDateTime now) {
        LocalDateTime local = now.withZoneSameInstant(LocalTasks.ZONE).toLocalDateTime();
        List<Occurrence> result = new ArrayList<>();
        for (LocalDateTime date = LocalDateTime.of(2026, 10, 10, 10, 0); !date.isAfter(local); date = date.plusMonths(1))
            result.add(monthly("salary-transfer", date));
        for (LocalDateTime date = LocalDateTime.of(2026, 9, 15, 10, 0); !date.isAfter(local); date = date.plusMonths(1))
            result.add(monthly("salary-adjustment", date));
        for (LocalDateTime date = LocalDateTime.of(2026, 10, 15, 10, 0); !date.isAfter(local);
             date = date.getDayOfMonth() == 1 ? date.withDayOfMonth(15) : date.plusMonths(1).withDayOfMonth(1)) {
            String period = date.toLocalDate().toString();
            result.add(new Occurrence("twice-monthly-writing/" + period, date, Map.of(
                "name", "半月写作（" + period + "）", "priority", "P1",
                "goal", "完成 " + period + " 对应的一次写作，形成可保存的草稿或修订稿。",
                "criteria", "明确选题并完成一次写作，保存稿件和本次进展。",
                "dependency", "需安排写作时间并确定本次选题或待修改稿件。",
                "note", "周期标识：twice-monthly-writing/" + period + "；计划生成时间：" + TIME.format(date)
                    + "；按近期优先级设为 P1；每月 1 日和 15 日各记录一次，不自动发布。")));
        }
        result.sort(Comparator.comparing(Occurrence::due).thenComparing(Occurrence::marker));
        return List.copyOf(result);
    }

    private static Occurrence monthly(String kind, LocalDateTime date) {
        String period = YearMonth.from(date).toString(); boolean transfer = kind.equals("salary-transfer");
        return new Occurrence(kind + "/" + period, date, Map.of(
            "name", (transfer ? "工资转移" : "工资调整") + "（" + period + "）", "priority", "P1",
            "goal", transfer ? "完成 " + period + " 对应的工资资金转移，并核对到账结果。"
                : "核对 " + period + " 的工资相关情况，并完成本人确认的调整事项。",
            "criteria", transfer ? "按本人确认的账户和金额完成转移，核对到账并记录结果。"
                : "明确本月需调整的事项，完成处理并记录结果；如无需调整，记录核对结论。",
            "dependency", transfer ? "需确认工资到账、转出及转入账户和金额。" : "待确认工资调整的具体内容、所需信息及处理方式。",
            "note", "周期标识：" + kind + "/" + period + "；计划生成时间：" + TIME.format(date)
                + (transfer ? "；按近期优先级设为 P1，仅生成待办，不自动执行资金转账。"
                    : "；按近期优先级设为 P1；与每月 10 日的工资转移任务独立，仅生成待办，不自动调整账户、薪资或执行转账。")));
    }

    /** 在同一次文件锁内判断缺失周期；改名后仍能按备注标识找到，归档也参与查重。 */
    static List<Occurrence> missing(TaskDocument todo, TaskDocument archive, ZonedDateTime now) {
        List<Occurrence> missing = new ArrayList<>();
        for (Occurrence occurrence : due(now)) {
            int matches = 0;
            for (TaskDocument document : List.of(todo, archive)) for (TaskDocument.Block block : document.blocks)
                if (TaskDuplicates.normalized(block.name()).equals(TaskDuplicates.normalized(occurrence.fields.get("name")))
                    || markers(block.field("备注")).contains(occurrence.marker)) matches++;
            Failure.require(matches <= 1, "DUPLICATE_TASK");
            if (matches == 0) missing.add(occurrence);
        }
        return List.copyOf(missing);
    }

    private static Set<String> markers(String note) {
        Set<String> result = new LinkedHashSet<>(); Matcher matcher = MARKER.matcher(note);
        while (matcher.find()) result.add(matcher.group(1)); return result;
    }

    /** 只保留已有周期任务的身份，不改变普通备注；前缀继续兼容旧脚本的查重方式。 */
    static String preserveMarker(String before, String requested) {
        Set<String> original = markers(before);
        Failure.require(original.size() <= 1, "TASK_FORMAT_INVALID");
        if (original.isEmpty()) return requested;
        String marker = original.iterator().next();
        Failure.require(markers(requested).stream().allMatch(marker::equals), "INVALID_REQUEST");
        String userNote = MARKER.matcher(requested).replaceAll("").replaceAll("^[；\\s]+|[；\\s]+$", "");
        return "周期标识：" + marker + (userNote.isBlank() || userNote.equals("无") ? "" : "；" + userNote);
    }
    private RecurringTasks() {}
}
