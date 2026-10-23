package com.snailmai.todoist;

import java.time.LocalDate;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** 明确的本地操作契约；不把自然语言、任务编号或浏览器路径作为执行指令。 */
record TaskOperation(String requestId, String operation, Map<String, String> fields,
                     List<Target> targets, String confirmation) {
    record Target(String key, String version) {}
    static TaskOperation parse(JsonNode body) {
        return parse(body, false);
    }
    static TaskOperation parseInternal(JsonNode body) {
        return parse(body, true);
    }
    private static TaskOperation parse(JsonNode body, boolean internal) {
        Failure.require(body != null && body.isObject(), "INVALID_REQUEST");
        if (body.has("input")) throw new Failure("CLIENT_UPDATE_REQUIRED");
        Set<String> allowed = Set.of("requestId", "operation", "fields", "targets", "confirmation");
        body.properties().forEach(entry -> Failure.require(allowed.contains(entry.getKey()), "INVALID_REQUEST"));
        String id = text(body, "requestId"), operation = text(body, "operation");
        try { Failure.require(UUID.fromString(id).toString().equals(id), "INVALID_REQUEST"); }
        catch (IllegalArgumentException ignored) { throw new Failure("INVALID_REQUEST"); }
        boolean recurring = internal && operation.equals("recurring");
        Failure.require(recurring || Set.of("add", "edit", "complete", "remove").contains(operation), "INVALID_REQUEST");
        Map<String, String> fields = new TreeMap<>();
        JsonNode values = body.path("fields");
        Failure.require(values.isMissingNode() || values.isObject(), "INVALID_REQUEST");
        if (values.isObject()) values.properties().forEach(entry -> {
            Failure.require(entry.getValue().isTextual(), "INVALID_REQUEST");
            String value = entry.getValue().asText().strip().replace("\r\n", "\n").replace("\r", "\n");
            Failure.require(value.length() <= 8192 && !value.contains("\u0000"), "INPUT_TOO_LARGE");
            fields.put(entry.getKey(), value);
        });
        Set<String> fieldNames = operation.equals("add") ? Set.of("name", "priority", "due", "goal", "criteria", "dependency", "note")
            : operation.equals("edit") ? Set.of("name", "priority", "note") : Set.of();
        Failure.require(fieldNames.containsAll(fields.keySet()), "INVALID_REQUEST");
        if (operation.equals("add")) {
            Failure.require(!fields.getOrDefault("name", "").isBlank(), "INVALID_REQUEST");
            if (fields.getOrDefault("goal", "").isBlank()) fields.put("goal", "完成「" + fields.get("name") + "」。");
            if (fields.getOrDefault("criteria", "").isBlank()) fields.put("criteria", "完成任务所述事项，并核对结果。");
            fields.putIfAbsent("priority", "P2");
        }
        if (fields.containsKey("name")) {
            Failure.require(fields.get("name").length() <= 512, "INPUT_TOO_LARGE");
            Failure.require(!fields.get("name").isBlank() && !fields.get("name").contains("\n"), "INVALID_REQUEST");
        }
        if (fields.containsKey("priority")) Failure.require(Set.of("P0", "P1", "P2", "P3").contains(fields.get("priority")), "INVALID_REQUEST");
        String due = fields.getOrDefault("due", "");
        if (!due.isBlank() && !due.equals("未设置")) {
            try { Failure.require(LocalDate.parse(due).toString().equals(due), "INVALID_REQUEST"); }
            catch (java.time.DateTimeException ignored) { throw new Failure("INVALID_REQUEST"); }
        }
        List<Target> targets = new ArrayList<>();
        JsonNode references = body.path("targets");
        Failure.require(references.isMissingNode() || references.isArray(), "INVALID_REQUEST");
        if (references.isArray()) for (JsonNode ref : references) {
            Failure.require(ref.isObject() && ref.size() == 2, "INVALID_REQUEST");
            String key = text(ref, "key"), version = text(ref, "version");
            Failure.require(key.matches("[a-f0-9]{64}") && version.matches("[a-f0-9]{64}"), "INVALID_REQUEST");
            targets.add(new Target(key, version));
        }
        Failure.require(targets.size() <= 100 && targets.stream().map(Target::key).distinct().count() == targets.size(), "INVALID_REQUEST");
        Failure.require(operation.equals("add") || recurring ? targets.isEmpty() : !targets.isEmpty(), "INVALID_REQUEST");
        Failure.require(!operation.equals("edit") || (targets.size() == 1 && !fields.isEmpty()), "INVALID_REQUEST");
        String confirmation = body.has("confirmation") ? text(body, "confirmation") : "";
        Failure.require(confirmation.isEmpty() || (Set.of("add", "edit").contains(operation) && confirmation.matches("[a-f0-9]{64}")), "INVALID_REQUEST");
        return new TaskOperation(id, operation, Collections.unmodifiableMap(fields), List.copyOf(targets), confirmation);
    }
    private static String text(JsonNode node, String key) {
        Failure.require(node.path(key).isTextual(), "INVALID_REQUEST"); return node.path(key).asText();
    }
}
