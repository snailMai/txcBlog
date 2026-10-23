package com.snailmai.todoist;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** 确定性判等与候选匹配，不把字符相似度说成 AI 语义判断。 */
final class TaskDuplicates {
    record Candidate(String name, String priority, String source) {}
    record Check(boolean exact, List<Candidate> candidates) {}
    static String normalized(String title) {
        return Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Z}]+", "");
    }
    static String equivalent(String title) {
        // 仅执行已约定的“测试任务＋编号”别名，不对所有名称删除“任务”。
        return normalized(title).replaceFirst("^测试任务(?=\\d)", "测试");
    }
    static Check check(String name, String excludedKey, TaskDocument todo, TaskDocument archive) {
        String wanted = equivalent(name);
        List<Candidate> exact = new ArrayList<>(), similar = new ArrayList<>();
        for (TaskDocument document : List.of(todo, archive)) for (TaskDocument.Block block : document.blocks) {
            if (!document.archive && block.key().equals(excludedKey)) continue;
            String existing = equivalent(block.name());
            Candidate candidate = new Candidate(block.name(), block.priority(), document.archive ? "archive" : "todo");
            if (wanted.equals(existing)) { if (exact.size() < 8) exact.add(candidate); }
            else if (similar.size() < 8 && similar(wanted, existing)) similar.add(candidate);
        }
        return new Check(!exact.isEmpty(), exact.isEmpty() ? similar : exact);
    }
    static boolean similar(String left, String right) {
        if (Math.min(left.length(), right.length()) < 4) return false;
        List<String> a = numbers(left), b = numbers(right);
        // 编号、日期或周期不同，不能按同一个模糊任务拦截。
        if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) return false;
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j < previous.length; j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1]; current[0] = i;
            for (int j = 1; j < current.length; j++) current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            previous = current;
        }
        return 1.0 - (double) previous[right.length()] / Math.max(left.length(), right.length()) >= .72;
    }
    private static List<String> numbers(String name) {
        Matcher matcher = Pattern.compile("\\d+").matcher(name); List<String> values = new ArrayList<>();
        while (matcher.find()) values.add(matcher.group()); return values;
    }
    private TaskDuplicates() {}
}
