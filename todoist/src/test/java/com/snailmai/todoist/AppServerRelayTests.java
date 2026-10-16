package com.snailmai.todoist;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Unix socket 上的合成桌面协议；绝不连接用户桌面或原会话。 */
class AppServerRelayTests {
    static final JsonMapper json = JsonMapper.builder().build();
    interface Scenario { void run(Peer peer) throws Exception; }
    static final class Desktop implements AutoCloseable {
        final Path directory = Files.createTempDirectory(Path.of("/tmp"), "pw-relay-");
        final Path path = directory.resolve("s");
        final ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        final CompletableFuture<Void> done = new CompletableFuture<>();
        volatile SocketChannel accepted;
        Desktop(Scenario scenario) throws IOException {
            server.bind(UnixDomainSocketAddress.of(path));
            Thread.ofVirtual().start(() -> {
                try (SocketChannel channel = server.accept()) {
                    accepted = channel; scenario.run(new Peer(channel)); done.complete(null);
                } catch (Throwable failure) { done.completeExceptionally(failure); }
            });
        }
        void await() throws Exception { done.get(3, TimeUnit.SECONDS); }
        public void close() throws IOException {
            if (accepted != null) accepted.close();
            server.close(); Files.deleteIfExists(path); Files.deleteIfExists(directory);
        }
    }
    static final class Peer {
        final SocketChannel channel;
        String clientId = "website-client";
        long revision = 1;
        Peer(SocketChannel channel) { this.channel = channel; }
        JsonNode read() throws IOException {
            ByteBuffer header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            while (header.hasRemaining()) if (channel.read(header) < 0) throw new IOException("Synthetic EOF");
            int size = header.flip().getInt();
            assertTrue(size > 0 && size < 1024 * 1024);
            ByteBuffer body = ByteBuffer.allocate(size);
            while (body.hasRemaining()) if (channel.read(body) < 0) throw new IOException("Synthetic EOF");
            return json.readTree(body.array());
        }
        void send(Object message) throws IOException {
            byte[] body = json.writeValueAsBytes(message);
            ByteBuffer frame = ByteBuffer.allocate(body.length + 4).order(ByteOrder.LITTLE_ENDIAN);
            frame.putInt(body.length).put(body).flip();
            while (frame.hasRemaining()) channel.write(frame);
        }
        void reply(JsonNode request, Object result) throws IOException {
            send(Map.of("type", "response", "requestId", request.path("requestId").asText(),
                "resultType", "success", "handledByClientId", "desktop-owner", "result", result));
        }
        void error(JsonNode request) throws IOException {
            send(Map.of("type", "response", "requestId", request.path("requestId").asText(),
                "resultType", "error", "error", "synthetic-private-value"));
        }
        void initialize() throws Exception {
            JsonNode init = read();
            assertEquals("initialize", init.path("method").asText());
            reply(init, Map.of("clientId", clientId));
        }
        void handshake() throws Exception {
            initialize();
            JsonNode discovery = read();
            assertEquals("thread-owner-discovery", discovery.path("method").asText());
            assertEquals(AppServerRelay.THREAD_ID, discovery.path("params").path("conversationId").asText());
            reply(discovery, Map.of("supportsUntrustedAppInput", true));
            JsonNode following = read();
            assertEquals("thread-stream-following-changed", following.path("method").asText());
            assertTrue(following.path("params").path("following").asBoolean());
            snapshot();
        }
        void snapshot() throws IOException {
            broadcast("desktop-owner", AppServerRelay.THREAD_ID, Map.of("type", "snapshot", "revision", revision,
                "conversationState", Map.of("id", AppServerRelay.THREAD_ID, "turnHistory",
                    Map.of("kind", "canonical", "history", Map.of("entitiesByKey", Map.of())))));
        }
        JsonNode start(String expected) throws Exception {
            JsonNode request = read();
            assertEquals("thread-follower-start-turn", request.path("method").asText());
            assertEquals("desktop-owner", request.path("targetClientId").asText());
            assertEquals(2, request.path("version").asInt());
            JsonNode turnStart = request.path("params").path("turnStart");
            assertEquals(1, turnStart.size());
            JsonNode command = turnStart.path("request");
            assertEquals(3, command.size()); // 只含会话 ID、消息 ID、原文，不覆盖目录／模型／权限。
            assertEquals(AppServerRelay.THREAD_ID, command.path("threadId").asText());
            assertDoesNotThrow(() -> UUID.fromString(command.path("clientUserMessageId").asText()));
            assertEquals(1, command.path("input").size());
            assertEquals(expected, command.path("input").get(0).path("text").asText());
            return request;
        }
        void started(JsonNode request) throws IOException {
            reply(request, Map.of("result", Map.of("turn", Map.of("id", "synthetic-turn", "status", "inProgress"))));
        }
        void broadcast(String owner, String thread, Object change) throws IOException {
            send(Map.of("type", "broadcast", "method", "thread-stream-state-changed", "version", 11,
                "sourceClientId", owner, "params", Map.of("hostId", "local", "conversationId", thread, "change", change)));
        }
        List<Object> path(String... tail) {
            var path = new ArrayList<Object>(List.of("turnHistory", "history", "entitiesByKey", "turn:synthetic-turn"));
            path.addAll(List.of(tail)); return path;
        }
        void patch(List<Object> path, Object value, String op) throws IOException {
            broadcast("desktop-owner", AppServerRelay.THREAD_ID, Map.of("type", "patches",
                "baseRevision", revision, "revision", ++revision, "patches",
                List.of(Map.of("op", op, "path", path, "value", value))));
        }
        void turn(String status, String text) throws IOException {
            patch(path(), Map.of("turnId", "synthetic-turn", "status", status, "items",
                List.of(Map.of("type", "agentMessage", "phase", "final_answer", "text", text))), "add");
        }
        void disconnected() throws Exception {
            JsonNode stopped = read();
            assertEquals("thread-stream-following-changed", stopped.path("method").asText());
            assertFalse(stopped.path("params").path("following").asBoolean());
            assertEquals(-1, channel.read(ByteBuffer.allocate(1))); // 返回前已断开，不保留网页占用。
        }
    }
    @Test void allFiveCommandsAreForwardedExactlyAndDisconnectedBeforeReturn() throws Exception {
        for (String input : List.of("输出全部待办", "添加任务：合成", "完成任务：合成", "删除任务：合成",
                "修改任务：  合成备注\n保留引号：\"原文\"  \n")) {
            try (var desktop = new Desktop(peer -> {
                peer.handshake(); JsonNode request = peer.start(input);
                peer.turn("inProgress", "不能提前显示");
                peer.started(request); // 状态可能早于提交响应，不能丢失。
                peer.patch(peer.path("items"), List.of(Map.of("type", "agentMessage", "phase", "commentary", "text", "不要显示"),
                    Map.of("type", "agentMessage", "phase", "final_answer", "text", "原样结果\n")), "replace");
                peer.patch(peer.path("status"), "completed", "replace");
                peer.disconnected();
            })) {
                assertEquals("原样结果\n", new AppServerRelay(desktop.path).run(input)); desktop.await();
            }
        }
    }
    @Test void finalTextDoesNotFinishUntilTheSameTurnCompletes() throws Exception {
        var textPublished = new CountDownLatch(1); var finish = new CountDownLatch(1);
        try (var desktop = new Desktop(peer -> {
            peer.handshake(); var request = peer.start("输出全部待办"); peer.started(request);
            peer.turn("inProgress", "合成结果"); textPublished.countDown();
            assertTrue(finish.await(3, TimeUnit.SECONDS));
            peer.patch(peer.path("status"), "completed", "replace"); peer.disconnected();
        })) {
            var result = CompletableFuture.supplyAsync(() -> {
                try { return new AppServerRelay(desktop.path).run("输出全部待办"); }
                catch (Exception failure) { throw new CompletionException(failure); }
            });
            assertTrue(textPublished.await(3, TimeUnit.SECONDS)); assertFalse(result.isDone());
            finish.countDown(); assertEquals("合成结果", result.get(3, TimeUnit.SECONDS)); desktop.await();
        } finally { finish.countDown(); }
    }
    @Test void unrelatedClientOrThreadCannotSupplyTheReply() throws Exception {
        try (var desktop = new Desktop(peer -> {
            peer.handshake(); var request = peer.start("合成输入"); peer.started(request);
            var wrong = Map.of("type", "snapshot", "revision", 99, "conversationState", Map.of());
            peer.broadcast("other-client", AppServerRelay.THREAD_ID, wrong);
            peer.broadcast("desktop-owner", "other-thread", wrong);
            peer.turn("completed", "正确结果"); peer.disconnected();
        })) {
            assertEquals("正确结果", new AppServerRelay(desktop.path).run("合成输入")); desktop.await();
        }
    }
    @Test void failedInterruptedOrUnconfirmedSubmissionNeverClaimsNoExecution() throws Exception {
        for (String status : List.of("failed", "interrupted", "rpc-error")) {
            try (var desktop = new Desktop(peer -> {
                peer.handshake(); var request = peer.start("合成输入");
                if (status.equals("rpc-error")) peer.error(request);
                else { peer.started(request); peer.turn(status, "不能当成功"); }
                peer.disconnected();
            })) {
                var failure = assertThrows(Failure.class, () -> new AppServerRelay(desktop.path).run("合成输入"));
                assertEquals("RESULT_UNKNOWN", failure.code); assertFalse(failure.toString().contains("synthetic-private-value"));
                desktop.await();
            }
        }
    }
    @Test void missingDesktopAndOwnerFailBeforeSending() throws Exception {
        var relay = new AppServerRelay(Path.of("/synthetic/socket"), path -> { throw new IOException("synthetic-private-value"); });
        assertEquals("NOT_CONNECTED", assertThrows(Failure.class, () -> relay.run("合成输入")).code);
        try (var desktop = new Desktop(peer -> {
            peer.initialize(); var discovery = peer.read(); peer.error(discovery);
            assertEquals(-1, peer.channel.read(ByteBuffer.allocate(1)));
        })) {
            assertEquals("SESSION_RESUME_FAILED", assertThrows(Failure.class, () -> new AppServerRelay(desktop.path).run("合成输入")).code);
            desktop.await();
        }
    }
    @Test void missingPatchRevisionCannotProduceAFalseSuccess() throws Exception {
        try (var desktop = new Desktop(peer -> {
            peer.handshake(); var request = peer.start("合成输入"); peer.started(request);
            peer.broadcast("desktop-owner", AppServerRelay.THREAD_ID, Map.of("type", "patches",
                "baseRevision", 50, "revision", 51, "patches", List.of()));
            peer.disconnected();
        })) {
            assertEquals("RESULT_UNKNOWN", assertThrows(Failure.class, () -> new AppServerRelay(desktop.path).run("合成输入")).code);
            desktop.await();
        }
    }
}
