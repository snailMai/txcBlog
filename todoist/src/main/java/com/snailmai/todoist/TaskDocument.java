package com.snailmai.todoist;

import java.nio.charset.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** 保存任务原文及边界；修改目标块，不通过重新序列化丢弃历史字段或空白。 */
final class TaskDocument {
    static final List<String> FIELDS = List.of("状态", "创建时间", "截止时间", "结束时间", "任务目标", "完成标准", "依赖", "备注");
    static final Pattern SECTIONS = Pattern.compile("(?m)^# (P[0-3])\\r?$");
    static final Pattern NUMBERS = Pattern.compile("(?m)^\\d+\\. (?=\\[P[0-3]\\] )");
    final String text, eol;
    final boolean bom, archive;
    final List<Block> blocks = new ArrayList<>();

    record Block(int start, int end, int number, String priority, String name, String raw,
                 Map<String, String> fields) {
        String field(String name) { return fields.getOrDefault(name, ""); }
        String key() { return hash(name + "\n" + field("创建时间")); }
        String version() { return hash(withoutNumber(raw)); }
        String indent() { return Pattern.compile("(?m)^   - 状态：").matcher(raw).find() ? "   " : ""; }
    }

    TaskDocument(byte[] bytes, boolean archive) {
        this.archive = archive;
        Failure.require(bytes.length <= 1024 * 1024, "INPUT_TOO_LARGE");
        bom = bytes.length >= 3 && bytes[0] == (byte)239 && bytes[1] == (byte)187 && bytes[2] == (byte)191;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, bom ? 3 : 0, bytes.length - (bom ? 3 : 0))).toString();
        } catch (CharacterCodingException ignored) { throw new Failure("TASK_FORMAT_INVALID"); }
        eol = text.contains("\r\n") ? "\r\n" : "\n";
        Pattern header = Pattern.compile(archive ? "(?m)^### \\[(P[0-3])\\] ([^\\r\\n]+)\\r?(?:\\n|$)"
            : "(?m)^(\\d+)\\. \\[(P[0-3])\\] ([^\\r\\n]+)\\r?(?:\\n|$)");
        Matcher matcher = header.matcher(text);
        Pattern boundary = Pattern.compile("(?m)^(?:\\d+\\. \\[P[0-3]\\] |#{1,3} )");
        while (matcher.find()) {
            Matcher next = boundary.matcher(text);
            int end = next.find(matcher.end()) ? next.start() : text.length();
            String raw = text.substring(matcher.start(), end);
            Map<String, String> fields = new LinkedHashMap<>();
            // 原会话曾生成无缩进字段；读取并保留原样，不要求整理真实文档。
            String indent = archive || Pattern.compile("(?m)^- 状态：").matcher(raw).find() ? "" : "   ";
            Matcher field = Pattern.compile("(?m)^" + indent + "- ([^：\\r\\n]+)：([^\\r\\n]*(?:\\r?\\n" + indent + "   [^\\r\\n]*)*)\\r?$").matcher(raw);
            while (field.find()) {
                String value = field.group(2).replace("\r", "").replace("\n" + indent + "   ", "\n");
                if (fields.put(field.group(1), value) != null) throw new Failure("TASK_FORMAT_INVALID");
            }
            int number;
            try { number = archive ? 0 : Integer.parseInt(matcher.group(1)); }
            catch (NumberFormatException ignored) { throw new Failure("TASK_FORMAT_INVALID"); }
            blocks.add(new Block(matcher.start(), end, number, matcher.group(archive ? 1 : 2),
                matcher.group(archive ? 2 : 3), raw, fields));
        }
    }

    void validateForWrite() {
        if (archive) {
            Matcher possible = Pattern.compile("(?m)^### \\[").matcher(text);
            int count = 0; while (possible.find()) count++;
            Failure.require(count == blocks.size(), "TASK_FORMAT_INVALID");
            Set<String> identities = new HashSet<>();
            for (Block block : blocks) {
                Failure.require(identities.add(block.key()) && Set.of("已完成", "已移除", "已放弃").contains(block.field("状态"))
                    && !block.field("创建时间").isBlank() && !block.field("结束时间").isBlank(), "TASK_FORMAT_INVALID");
            }
            return;
        }
        List<String> sections = new ArrayList<>();
        Matcher groups = SECTIONS.matcher(text);
        while (groups.find()) sections.add(groups.group(1));
        Failure.require(sections.equals(List.of("P0", "P1", "P2", "P3")), "TASK_FORMAT_INVALID");
        Matcher possible = Pattern.compile("(?m)^\\d+\\.").matcher(text);
        int count = 0; while (possible.find()) count++;
        Failure.require(count == blocks.size(), "TASK_FORMAT_INVALID");
        Set<String> identities = new HashSet<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            Matcher section = SECTIONS.matcher(text.substring(0, block.start));
            String priority = ""; while (section.find()) priority = section.group(1);
            Failure.require(priority.equals(block.priority), "TASK_FORMAT_INVALID");
            Failure.require(block.number == i + 1 && identities.add(block.key()), "TASK_FORMAT_INVALID");
            Failure.require(block.fields.keySet().containsAll(FIELDS), "TASK_FORMAT_INVALID");
            for (String field : FIELDS) if (!field.equals("结束时间"))
                Failure.require(!block.field(field).isBlank(), "TASK_FORMAT_INVALID");
            Failure.require(Set.of("待办", "进行中").contains(block.field("状态")), "TASK_FORMAT_INVALID");
        }
    }

    String replace(Map<Block, String> replacements) {
        StringBuilder result = new StringBuilder(text);
        replacements.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<Block, String> entry) -> entry.getKey().start).reversed())
            .forEach(entry -> result.replace(entry.getKey().start, entry.getKey().end, entry.getValue()));
        return result.toString();
    }

    static String insert(String text, String priority, String block, String eol) {
        Matcher matcher = SECTIONS.matcher(text);
        while (matcher.find()) if (matcher.group(1).equals(priority)) {
            int index = matcher.end();
            while (index < text.length() && (text.charAt(index) == '\r' || text.charAt(index) == '\n')) index++;
            String prefix = text.substring(0, index);
            if (!prefix.endsWith(eol)) prefix += eol;
            return prefix + block + text.substring(index);
        }
        throw new Failure("TASK_FORMAT_INVALID");
    }

    static String renumber(String text) {
        int[] counter = {0}; return NUMBERS.matcher(text).replaceAll(match -> ++counter[0] + ". ");
    }
    static String fieldLine(String indent, String name, String value, String eol) {
        return indent + "- " + name + "：" + value.replace("\r\n", "\n").replace("\r", "\n").replace("\n", eol + indent + "   ") + eol;
    }
    static String changeField(String raw, String name, String value, String indent, String eol) {
        Pattern pattern = Pattern.compile("(?m)^" + indent + "- " + Pattern.quote(name)
            + "：[^\\r\\n]*(?:\\r?\\n" + indent + "   [^\\r\\n]*)*\\r?(?:\\n|$)");
        Matcher matcher = pattern.matcher(raw);
        Failure.require(matcher.find(), "TASK_FORMAT_INVALID");
        return matcher.replaceFirst(Matcher.quoteReplacement(fieldLine(indent, name, value, eol)));
    }
    byte[] encode(String value) {
        return ((bom ? "\uFEFF" : "") + value).getBytes(StandardCharsets.UTF_8);
    }
    static String withoutNumber(String raw) { return raw.replaceFirst("^\\d+\\. ", "").replaceFirst("[\\r\\n]+$", ""); }
    static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception ignored) { throw new Failure("STORAGE_UNAVAILABLE"); }
    }
}
