package com.snailmai.todoist;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 使用桌面已有的会话持有者转发，不创建会话、不覆盖设置、不直接操作任务文件。 */
final class AppServerRelay implements RelayRequests.Runner {
    static final String THREAD_ID = "01a13607-d04d-7800-b5f4-59891d7e7da5";
    private final Path socketPath;
    interface Connector { SocketChannel connect(Path path) throws IOException; }
    private final Connector connector;
    private final JsonMapper json = JsonMapper.builder().build();

    AppServerRelay(Path socketPath) {
        this(socketPath, path -> {
            SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX);
            try { socket.connect(UnixDomainSocketAddress.of(path)); return socket; }
            catch (IOException failure) { socket.close(); throw failure; }
        });
    }
    AppServerRelay(Path socketPath, Connector connector) { this.socketPath = socketPath; this.connector = connector; }

    @Override public String run(String input) throws Exception {
        SocketChannel socket;
        try { socket = connector.connect(socketPath); }
        catch (IOException ignored) { throw new Failure("NOT_CONNECTED"); }
        boolean submitted = false;
        String stage = "CONNECT_INIT_FAILED";
        try (Connection connection = new Connection(socket)) {
            JsonNode initialized = connection.rpc("initialize", 0, Map.of("clientType", "personweb-todoist"), null);
            connection.clientId = initialized.path("result").path("clientId").asText();
            Failure.require(!connection.clientId.isBlank(), stage);
            stage = "SESSION_RESUME_FAILED";
            JsonNode discovery = connection.rpc("thread-owner-discovery", 1,
                Map.of("hostId", "local", "conversationId", THREAD_ID), null);
            connection.owner = discovery.path("handledByClientId").asText();
            Failure.require(!connection.owner.isBlank(), stage);
            connection.follow(true);
            // 先取得当前协议的快照再发送，协议不兼容时不能冒险提交。
            connection.awaitSnapshot();
            submitted = true;
            JsonNode started = connection.rpc("thread-follower-start-turn", 2, Map.of(
                "conversationId", THREAD_ID,
                "turnStart", Map.of("request", Map.of("threadId", THREAD_ID,
                    "clientUserMessageId", UUID.randomUUID().toString(),
                    "input", List.of(Map.of("type", "text", "text", input, "text_elements", List.of()))))), connection.owner);
            String turnId = started.path("result").path("result").path("turn").path("id").asText();
            Failure.require(!turnId.isBlank(), "RESULT_UNKNOWN");
            return connection.finish(turnId);
        } catch (Failure failure) {
            throw new Failure(submitted ? "RESULT_UNKNOWN" : stage);
        } catch (Exception ignored) {
            throw new Failure(submitted ? "RESULT_UNKNOWN" : stage);
        }
        // try-with-resources 在结果返回前取消本次订阅并关闭 socket，不影响桌面持有者。
    }

    private final class Connection implements AutoCloseable {
        final SocketChannel socket;
        final BlockingQueue<JsonNode> incoming = new ArrayBlockingQueue<>(32);
        final long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
        final Thread reader;
        volatile boolean ended;
        String clientId = "", owner = "";
        boolean following;
        JsonNode state;
        long revision;

        Connection(SocketChannel socket) {
            this.socket = socket;
            reader = Thread.ofVirtual().start(() -> {
                try {
                    while (socket.isOpen()) {
                        ByteBuffer header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
                        readFully(header);
                        int size = header.flip().getInt();
                        if (size <= 0 || size > 8 * 1024 * 1024) throw new IOException();
                        ByteBuffer body = ByteBuffer.allocate(size);
                        readFully(body);
                        incoming.put(json.readTree(body.array()));
                    }
                } catch (Exception ignored) { /* 不输出可能含任务正文的协议异常。 */ }
                finally { ended = true; }
            });
        }
        void readFully(ByteBuffer buffer) throws IOException {
            while (buffer.hasRemaining()) if (socket.read(buffer) < 0) throw new IOException();
        }
        void send(Object message) throws IOException {
            byte[] body = json.writeValueAsBytes(message);
            ByteBuffer frame = ByteBuffer.allocate(4 + body.length).order(ByteOrder.LITTLE_ENDIAN);
            frame.putInt(body.length).put(body).flip();
            while (frame.hasRemaining()) socket.write(frame);
        }
        JsonNode next(long until) throws Exception {
            while (System.nanoTime() < Math.min(until, deadline)) {
                JsonNode message = incoming.poll(100, TimeUnit.MILLISECONDS);
                if (message != null) return message;
                if (ended) throw new Failure("RESULT_UNKNOWN");
            }
            throw new Failure("RESULT_UNKNOWN");
        }
        JsonNode rpc(String method, int version, Object params, String target) throws Exception {
            String id = UUID.randomUUID().toString();
            var message = new HashMap<String, Object>();
            message.put("type", "request"); message.put("requestId", id); message.put("sourceClientId", clientId);
            message.put("method", method); message.put("version", version); message.put("params", params);
            message.put("timeoutMs", 10000);
            if (target != null) message.put("targetClientId", target);
            send(message);
            long until = System.nanoTime() + Duration.ofSeconds(12).toNanos();
            while (true) {
                JsonNode received = next(until);
                if (received.path("type").asText().equals("response") && received.path("requestId").asText().equals(id)) {
                    Failure.require(received.path("resultType").asText().equals("success"), "RESULT_UNKNOWN");
                    return received;
                }
                observe(received);
            }
        }
        void follow(boolean value) throws IOException {
            send(Map.of("type", "broadcast", "method", "thread-stream-following-changed", "version", 1,
                "sourceClientId", clientId, "targetClientIds", List.of(owner),
                "params", Map.of("hostId", "local", "conversationId", THREAD_ID, "following", value)));
            following = value;
        }
        void awaitSnapshot() throws Exception {
            long until = System.nanoTime() + Duration.ofSeconds(12).toNanos();
            while (state == null) observe(next(until));
        }
        /** 只接收本次发现的持有者、目标会话和本地 host 的状态，其他会话全部忽略。 */
        void observe(JsonNode message) {
            JsonNode params = message.path("params");
            if (!message.path("type").asText().equals("broadcast")
                || !message.path("method").asText().equals("thread-stream-state-changed")
                || !message.path("sourceClientId").asText().equals(owner)
                || !params.path("conversationId").asText().equals(THREAD_ID)
                || !params.path("hostId").asText().equals("local")) return;
            Failure.require(message.path("version").asInt() == 11, "RESULT_UNKNOWN");
            JsonNode change = params.path("change");
            switch (change.path("type").asText()) {
                case "snapshot" -> {
                    JsonNode snapshot = change.path("conversationState");
                    Failure.require(snapshot.path("id").asText().equals(THREAD_ID), "RESULT_UNKNOWN");
                    state = snapshot; revision = change.path("revision").asLong();
                }
                case "patches" -> {
                    Failure.require(state != null && change.path("baseRevision").asLong(-1) == revision, "RESULT_UNKNOWN");
                    for (JsonNode patch : change.path("patches")) apply(patch);
                    revision = change.path("revision").asLong();
                }
                default -> throw new Failure("RESULT_UNKNOWN");
            }
        }
        /** 桌面使用 Immer 路径数组；按顺序应用，版本缺口不得当作已完成。 */
        void apply(JsonNode patch) {
            JsonNode path = patch.path("path"), parent = state;
            Failure.require(path.isArray() && !path.isEmpty(), "RESULT_UNKNOWN");
            for (int i = 0; i < path.size() - 1; i++)
                parent = parent.isArray() ? parent.path(path.get(i).asInt()) : parent.path(path.get(i).asText());
            JsonNode key = path.get(path.size() - 1), value = patch.path("value");
            String op = patch.path("op").asText();
            Failure.require(Set.of("add", "replace", "remove").contains(op), "RESULT_UNKNOWN");
            if (parent instanceof ObjectNode object) {
                if (op.equals("remove")) object.remove(key.asText()); else object.set(key.asText(), value);
            } else if (parent instanceof ArrayNode array) {
                int index = key.asInt(-1);
                Failure.require(index >= 0 && index <= array.size(), "RESULT_UNKNOWN");
                Failure.require(op.equals("add") || index < array.size(), "RESULT_UNKNOWN");
                if (op.equals("add")) array.insert(index, value);
                else if (op.equals("remove")) array.remove(index);
                else array.set(index, value);
            } else throw new Failure("RESULT_UNKNOWN");
        }
        String finish(String turnId) throws Exception {
            while (true) {
                JsonNode turn = null;
                JsonNode entities = state.path("turnHistory").path("history").path("entitiesByKey");
                if (entities.isObject()) for (JsonNode candidate : entities)
                    if (candidate.path("turnId").asText().equals(turnId)) turn = candidate;
                if (turn == null) for (JsonNode candidate : state.path("turns"))
                    if (candidate.path("turnId").asText().equals(turnId)) turn = candidate;
                if (turn != null) {
                    String status = turn.path("status").asText();
                    Failure.require(!Set.of("failed", "interrupted").contains(status), "RESULT_UNKNOWN");
                    if (status.equals("completed")) {
                        String answer = null;
                        for (JsonNode item : turn.path("items"))
                            if (item.path("type").asText().equals("agentMessage")
                                && !item.path("phase").asText().equals("commentary") && item.path("text").isTextual())
                                answer = item.path("text").asText();
                        Failure.require(answer != null, "RESULT_UNKNOWN");
                        return answer;
                    }
                }
                observe(next(deadline));
            }
        }
        @Override public void close() {
            // 只解除本客户端的跟随关系，不调用 turn/interrupt 或释放桌面会话。
            try { if (following) follow(false); } catch (Exception ignored) {}
            try { socket.close(); } catch (IOException ignored) {}
            reader.interrupt();
        }
    }
}
