package com.snailmai.todoist;

import java.io.IOException;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 固定两文件的本地执行器；私有回执支持幂等及归档之后的中断恢复。 */
final class LocalTasks implements RelayRequests.Runner {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final Path todoPath, archivePath, state;
    private final String binding;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    interface Checkpoint { void afterArchive() throws IOException; }
    private final Checkpoint checkpoint;

    LocalTasks(Path todo, Path archive, Path state) { this(todo, archive, state, Clock.system(ZONE), () -> {}); }
    LocalTasks(Path todo, Path archive, Path state, Clock clock, Checkpoint checkpoint) {
        Failure.require(todo.isAbsolute() && archive.isAbsolute() && state.isAbsolute(), "STORAGE_UNAVAILABLE");
        todoPath = todo.normalize(); archivePath = archive.normalize(); this.state = state.normalize();
        Failure.require(!todoPath.equals(archivePath) && !this.state.startsWith(todoPath) && !this.state.startsWith(archivePath), "STORAGE_UNAVAILABLE");
        binding = TaskDocument.hash(todoPath + "\n" + archivePath); this.clock = clock; this.checkpoint = checkpoint;
    }

    synchronized List<Map<String, Object>> list() { return TaskFile.read(todoPath); }
    /** 本机启用前预览：只读取两文件，不创建回执目录、不写入或投递队列。 */
    synchronized Map<String, Object> previewRecurring() {
        try {
            byte[] todo, archive;
            try (var input = Files.newInputStream(todoPath)) { todo = input.readNBytes(1024 * 1024 + 1); }
            try (var input = Files.newInputStream(archivePath)) { archive = input.readNBytes(1024 * 1024 + 1); }
            TaskDocument active = new TaskDocument(todo, false), history = new TaskDocument(archive, true);
            active.validateForWrite(); history.validateForWrite();
            var missing = RecurringTasks.missing(active, history, ZonedDateTime.now(clock));
            return Map.of("timezone", "Asia/Shanghai", "missingCount", missing.size(), "tasks", missing.stream().map(item ->
                Map.of("marker", item.marker(), "scheduledAt", item.due().toString(), "fields", item.fields())).toList());
        } catch (IOException ignored) { throw new Failure("STORAGE_UNAVAILABLE"); }
    }
    boolean ready() { return Files.isRegularFile(todoPath) && Files.isReadable(todoPath)
        && Files.isWritable(todoPath) && Files.isRegularFile(archivePath) && Files.isWritable(archivePath); }

    @Override public synchronized String run(String input) throws Exception {
        TaskOperation command = TaskOperation.parseInternal(json.readTree(input));
        String digest = TaskDocument.hash(input);
        try (Locked files = lock()) {
            recover(files);
            JsonNode previous = receipt(command.requestId());
            if (previous != null) {
                Failure.require(previous.path("digest").asText().equals(digest), "REQUEST_MISMATCH");
                return replay(previous.path("result"));
            }
            byte[] beforeTodo = files.read(files.todo), beforeArchive = files.read(files.archive);
            TaskDocument todo = new TaskDocument(beforeTodo, false), archive = new TaskDocument(beforeArchive, true);
            todo.validateForWrite(); archive.validateForWrite();
            try {
                Change change = plan(command, todo, archive);
                // 每分钟的空检查不写任务或回执；真正有新增仍复用原事务与中断恢复。
                if (command.operation().equals("recurring") && change.todo.equals(todo.text) && change.archive.equals(archive.text))
                    return change.output;
                Map<String, Object> result = Map.of("requestId", command.requestId(), "status", "completed", "output", change.output);
                Map<String, Object> prepared = new LinkedHashMap<>();
                prepared.put("binding", binding); prepared.put("digest", digest); prepared.put("phase", "prepared");
                prepared.put("result", result);
                prepared.put("beforeTodo", Base64.getEncoder().encodeToString(beforeTodo));
                prepared.put("beforeArchive", Base64.getEncoder().encodeToString(beforeArchive));
                prepared.put("afterTodo", Base64.getEncoder().encodeToString(todo.encode(change.todo)));
                prepared.put("afterArchive", Base64.getEncoder().encodeToString(archive.encode(change.archive)));
                writeReceipt(command.requestId(), prepared);
                // 日志中不保存正文；这些字节仅存在于 0700 目录下的当前事务恢复记录。
                try {
                    finish(files, command.requestId(), json.valueToTree(prepared), true);
                } catch (Exception ignored) { throw new Failure("RECOVERY_REQUIRED"); }
                return change.output;
            } catch (Failure failure) {
                if (!failure.code.equals("RECOVERY_REQUIRED") && !command.operation().equals("recurring")) {
                    Map<String, Object> result = new LinkedHashMap<>(failure.details);
                    result.put("requestId", command.requestId()); result.put("status", "failed"); result.put("code", failure.code);
                    writeReceipt(command.requestId(), Map.of("binding", binding, "digest", digest, "phase", "done", "result", result));
                }
                throw failure;
            }
        } catch (OverlappingFileLockException ignored) { throw new Failure("FILE_LOCKED"); }
        catch (IOException ignored) { throw new Failure("STORAGE_UNAVAILABLE"); }
    }

    /** GET 只取回执；若上次操作中断，按该操作记录恢复，不重新解析或执行新任务。 */
    synchronized Map<String, Object> lookup(UUID id) {
        if (!Files.isDirectory(state)) return null;
        try {
            JsonNode record = receipt(id.toString());
            if (record == null) return null;
            if (record.path("phase").asText().equals("prepared")) {
                try (Locked files = lock()) { recover(files); }
                record = receipt(id.toString());
            }
            return json.convertValue(record.path("result"), Map.class);
        } catch (Exception ignored) {
            return Map.of("requestId", id.toString(), "status", "unknown", "code", "RECOVERY_REQUIRED");
        }
    }

    record Change(String todo, String archive, String output) {}
    private Change plan(TaskOperation command, TaskDocument todo, TaskDocument archive) {
        List<TaskDocument.Block> targets = new ArrayList<>();
        for (TaskOperation.Target ref : command.targets()) {
            List<TaskDocument.Block> matches = todo.blocks.stream().filter(block -> block.key().equals(ref.key())).toList();
            Failure.require(matches.size() == 1 && matches.getFirst().version().equals(ref.version()), "TASK_CHANGED");
            targets.add(matches.getFirst());
        }
        Map<String, String> fields = command.fields();
        String time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(ZonedDateTime.now(clock).withZoneSameInstant(ZONE));
        String nextTodo, nextArchive = archive.text;
        int added = 0;
        switch (command.operation()) {
            case "add" -> {
                checkDuplicates(command, "", todo, archive);
                nextTodo = TaskDocument.insert(todo.text, fields.get("priority"), newTask(fields, time, todo.eol), todo.eol);
                added = 1;
            }
            case "recurring" -> {
                var missing = RecurringTasks.missing(todo, archive, ZonedDateTime.now(clock));
                added = missing.size(); nextTodo = todo.text;
                for (var occurrence : missing) nextTodo = TaskDocument.insert(nextTodo, "P1", newTask(occurrence.fields(), time, todo.eol), todo.eol);
            }
            case "edit" -> {
                TaskDocument.Block target = targets.getFirst();
                if (fields.containsKey("name") && !fields.get("name").equals(target.name())) checkDuplicates(command, target.key(), todo, archive);
                String name = fields.getOrDefault("name", target.name()), priority = fields.getOrDefault("priority", target.priority());
                String raw = target.raw().replaceFirst("^\\d+\\. \\[P[0-3]\\] [^\\r\\n]+", java.util.regex.Matcher.quoteReplacement(target.number() + ". [" + priority + "] " + name));
                if (fields.containsKey("note")) raw = TaskDocument.changeField(raw, "备注",
                    RecurringTasks.preserveMarker(target.field("备注"), fallback(fields, "note", "无")), target.indent(), todo.eol);
                nextTodo = todo.replace(Map.of(target, priority.equals(target.priority()) ? raw : ""));
                if (!priority.equals(target.priority())) nextTodo = TaskDocument.insert(nextTodo, priority, raw.replaceFirst("[\\r\\n]*$", "") + todo.eol + todo.eol, todo.eol);
            }
            case "complete", "remove" -> {
                String status = command.operation().equals("complete") ? "已完成" : "已移除";
                Map<TaskDocument.Block, String> removed = new HashMap<>();
                int year = ZonedDateTime.now(clock).withZoneSameInstant(ZONE).getYear();
                for (TaskDocument.Block target : targets) {
                    Failure.require(archive.blocks.stream().noneMatch(block -> block.key().equals(target.key())), "ARCHIVE_CONFLICT");
                    String raw = target.raw().replaceFirst("^\\d+\\. ", "### ");
                    if (!target.indent().isEmpty()) raw = raw.replaceAll("(?m)^   ", "");
                    raw = TaskDocument.changeField(raw, "状态", status, "", todo.eol);
                    raw = TaskDocument.changeField(raw, "结束时间", time, "", todo.eol);
                    nextArchive = appendArchive(nextArchive, raw.replace("\r\n", "\n").replace("\n", archive.eol), year, archive.eol);
                    removed.put(target, "");
                }
                nextTodo = todo.replace(removed);
            }
            default -> throw new Failure("INVALID_REQUEST");
        }
        nextTodo = TaskDocument.renumber(nextTodo);
        TaskDocument after = new TaskDocument(todo.encode(nextTodo), false); after.validateForWrite();
        int expected = todo.blocks.size() + added - (Set.of("complete", "remove").contains(command.operation()) ? targets.size() : 0);
        Failure.require(after.blocks.size() == expected, "TASK_FORMAT_INVALID");
        // 未指定的任务仅允许序号变化，字段和历史附加内容必须保持。
        Set<String> changed = new HashSet<>(); targets.forEach(target -> changed.add(target.key()));
        for (TaskDocument.Block block : todo.blocks) if (!changed.contains(block.key())) {
            Failure.require(after.blocks.stream().anyMatch(other -> other.key().equals(block.key())
                && TaskDocument.withoutNumber(other.raw()).equals(TaskDocument.withoutNumber(block.raw()))), "TASK_FORMAT_INVALID");
        }
        if (Set.of("complete", "remove").contains(command.operation())) {
            TaskDocument archived = new TaskDocument(archive.encode(nextArchive), true);
            archived.validateForWrite();
            Failure.require(archived.blocks.size() == archive.blocks.size() + targets.size(), "TASK_FORMAT_INVALID");
            for (TaskDocument.Block target : targets) Failure.require(archived.blocks.stream().filter(block -> block.key().equals(target.key())).count() == 1, "TASK_FORMAT_INVALID");
        }
        if (command.operation().equals("recurring")) return new Change(nextTodo, nextArchive, "周期检查完成，新增 " + added + " 项任务，验证通过。");
        String verb = switch (command.operation()) { case "add" -> "添加"; case "edit" -> "修改"; case "complete" -> "完成并归档"; default -> "移除并归档"; };
        return new Change(nextTodo, nextArchive, "已" + verb + " " + (targets.isEmpty() ? 1 : targets.size()) + " 项任务，验证通过。");
    }

    private static String newTask(Map<String, String> fields, String time, String eol) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("状态", "待办"); values.put("创建时间", time); values.put("截止时间", fallback(fields, "due", "未设置"));
        values.put("结束时间", ""); values.put("任务目标", fields.get("goal")); values.put("完成标准", fields.get("criteria"));
        values.put("依赖", fallback(fields, "dependency", "无")); values.put("备注", fallback(fields, "note", "无"));
        StringBuilder raw = new StringBuilder("0. [" + fields.get("priority") + "] " + fields.get("name") + eol);
        for (var field : values.entrySet()) raw.append(TaskDocument.fieldLine("   ", field.getKey(), field.getValue(), eol));
        return raw.append(eol).toString();
    }

    private void checkDuplicates(TaskOperation command, String excluded, TaskDocument todo, TaskDocument archive) {
        TaskDuplicates.Check result = TaskDuplicates.check(command.fields().get("name"), excluded, todo, archive);
        if (result.candidates().isEmpty()) return;
        List<Map<String, String>> matches = result.candidates().stream().map(candidate -> Map.of("name", candidate.name(), "priority", candidate.priority(), "source", candidate.source())).toList();
        String token = TaskDocument.hash(command.fields().get("name") + "\n" + todo.text + "\n" + archive.text);
        if (result.exact()) throw new Failure("DUPLICATE_TASK", Map.of("duplicates", matches));
        if (!token.equals(command.confirmation())) throw new Failure("SIMILAR_TASK", Map.of("duplicates", matches, "confirmation", token));
    }
    private static String fallback(Map<String, String> fields, String name, String defaultValue) {
        return fields.getOrDefault(name, "").isBlank() ? defaultValue : fields.get(name);
    }
    private static String append(String original, String block, String eol) {
        return original + (original.isEmpty() || original.endsWith(eol + eol) ? "" : original.endsWith(eol) ? eol : eol + eol)
            + block + (block.endsWith(eol + eol) ? "" : block.endsWith(eol) ? eol : eol + eol);
    }
    private static String appendArchive(String text, String block, int year, String eol) {
        var heading = java.util.regex.Pattern.compile("(?m)^## " + year + "\\r?$").matcher(text);
        if (!heading.find()) return append(append(text, "## " + year + eol, eol), block, eol);
        var next = java.util.regex.Pattern.compile("(?m)^## \\d{4}\\r?$").matcher(text);
        int end = next.find(heading.end()) ? next.start() : text.length();
        return append(text.substring(0, end), block, eol) + text.substring(end);
    }

    private String replay(JsonNode result) {
        if (result.path("status").asText().equals("completed")) return result.path("output").asText();
        Map<String, Object> details = json.convertValue(result, Map.class);
        details.remove("requestId"); details.remove("status"); details.remove("code");
        throw new Failure(result.path("code").asText(), details);
    }
    private JsonNode receipt(String id) throws IOException {
        Path path = state.resolve(id + ".json");
        if (!Files.exists(path)) return null;
        Failure.require(!Files.isSymbolicLink(path) && Files.size(path) <= 8 * 1024 * 1024, "RECOVERY_REQUIRED");
        JsonNode record = json.readTree(Files.readAllBytes(path));
        Failure.require(record.path("binding").asText().equals(binding), "RECOVERY_REQUIRED"); return record;
    }
    private void writeReceipt(String id, Object record) throws IOException {
        Path next = state.resolve(id + ".next"), target = state.resolve(id + ".json");
        Failure.require(!Files.isSymbolicLink(next), "STORAGE_UNAVAILABLE");
        try (FileChannel channel = FileChannel.open(next, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            Files.setPosixFilePermissions(next, PosixFilePermissions.fromString("rw-------"));
            ByteBuffer data = ByteBuffer.wrap(json.writeValueAsBytes(record)); while (data.hasRemaining()) channel.write(data); channel.force(true);
        }
        Files.move(next, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private void recover(Locked files) throws IOException {
        try (var paths = Files.list(state)) {
            for (Path path : paths.filter(path -> path.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).sorted().toList()) {
                String id = path.getFileName().toString().replace(".json", ""); JsonNode record = receipt(id);
                if (record.path("phase").asText().equals("prepared")) {
                    try { finish(files, id, record, false); }
                    catch (Exception ignored) { throw new Failure("RECOVERY_REQUIRED"); }
                }
            }
        }
    }
    private void finish(Locked files, String id, JsonNode record, boolean inject) throws IOException {
        byte[] beforeTodo = Base64.getDecoder().decode(record.path("beforeTodo").asText()), afterTodo = Base64.getDecoder().decode(record.path("afterTodo").asText());
        byte[] beforeArchive = Base64.getDecoder().decode(record.path("beforeArchive").asText()), afterArchive = Base64.getDecoder().decode(record.path("afterArchive").asText());
        byte[] currentTodo = files.read(files.todo), currentArchive = files.read(files.archive);
        Failure.require((Arrays.equals(currentTodo, beforeTodo) || Arrays.equals(currentTodo, afterTodo))
            && (Arrays.equals(currentArchive, beforeArchive) || Arrays.equals(currentArchive, afterArchive)), "RECOVERY_REQUIRED");
        if (!Arrays.equals(currentArchive, afterArchive)) files.write(files.archive, archivePath, currentArchive, afterArchive);
        if (inject) checkpoint.afterArchive();
        if (!Arrays.equals(currentTodo, afterTodo)) files.write(files.todo, todoPath, currentTodo, afterTodo);
        Failure.require(Arrays.equals(files.read(files.archive), afterArchive) && Arrays.equals(files.read(files.todo), afterTodo), "RECOVERY_REQUIRED");
        // 成功后移除回执中的文件快照，只保留幂等摘要与结果，不长期复制任务数据。
        writeReceipt(id, Map.of("binding", binding, "digest", record.path("digest").asText(), "phase", "done", "result", record.path("result")));
    }

    private Locked lock() throws IOException {
        Failure.require(!Files.isSymbolicLink(state), "STORAGE_UNAVAILABLE");
        Files.createDirectories(state); Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
        return new Locked();
    }
    private final class Locked implements AutoCloseable {
        FileChannel gate, archive, todo;
        Locked() throws IOException {
            try {
                gate = FileChannel.open(state.resolve("operations.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                take(gate);
                Failure.require(!Files.isSymbolicLink(todoPath) && !Files.isSymbolicLink(archivePath), "STORAGE_UNAVAILABLE");
                archive = FileChannel.open(archivePath, StandardOpenOption.READ, StandardOpenOption.WRITE); take(archive);
                todo = FileChannel.open(todoPath, StandardOpenOption.READ, StandardOpenOption.WRITE); take(todo);
            } catch (IOException | RuntimeException failure) { close(); throw failure; }
        }
        void take(FileChannel channel) throws IOException { Failure.require(channel.tryLock() != null, "FILE_LOCKED"); }
        byte[] read(FileChannel channel) throws IOException {
            Failure.require(channel.size() <= 1024 * 1024, "INPUT_TOO_LARGE");
            ByteBuffer data = ByteBuffer.allocate((int) channel.size()); channel.position(0);
            while (data.hasRemaining()) if (channel.read(data) < 0) throw new IOException();
            return data.array();
        }
        void write(FileChannel channel, Path path, byte[] expected, byte[] value) throws IOException {
            Failure.require(Arrays.equals(read(channel), expected) && Arrays.equals(Files.readAllBytes(path), expected), "RECOVERY_REQUIRED");
            channel.position(0); ByteBuffer data = ByteBuffer.wrap(value);
            while (data.hasRemaining()) channel.write(data); channel.truncate(value.length); channel.force(true);
            Failure.require(Arrays.equals(read(channel), value) && Arrays.equals(Files.readAllBytes(path), value), "RECOVERY_REQUIRED");
        }
        @Override public void close() {
            for (FileChannel channel : new FileChannel[]{todo, archive, gate}) if (channel != null) try { channel.close(); } catch (IOException ignored) {}
        }
    }
}
