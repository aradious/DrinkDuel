package com.drinkduel.realtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import jakarta.annotation.PreDestroy;

/** Native JSON WebSocket adapter. One bounded queue/writer per socket, never network I/O
 * on a room lock. Browser pong responses detect lost connections without gameplay timers.
 */
public final class RoomSocketHandler extends TextWebSocketHandler {
    static final int MAX_TEXT_BYTES = 8192;
    private final RoomRealtime realtime;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();

    public RoomSocketHandler(RoomRealtime realtime) { this.realtime = realtime; }

    @Override public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(MAX_TEXT_BYTES);
        session.setBinaryMessageSizeLimit(0);
        var peer = new Peer(session);
        var owner = session.getPrincipal() instanceof AuthenticatedOwnerPrincipal principal
                ? principal.identity() : null;
        peer.connection = realtime.open(peer, owner);
        peers.put(session.getId(), peer);
        writers.execute(peer::write);
    }

    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Peer peer = peers.get(session.getId());
        if (peer == null || peer.ending.get()) return;
        if (message.getPayloadLength() > MAX_TEXT_BYTES || !message.isLast()) {
            peer.fail();
            return;
        }
        try {
            RoomProtocol.Command command = json.readValue(message.getPayload(), RoomProtocol.Command.class);
            if (command == null) throw new IllegalArgumentException();
            realtime.command(peer.connection, command);
        } catch (RuntimeException exception) {
            // Do not echo parse errors: Jackson diagnostics may contain credentials.
            peer.enqueue(new RoomProtocol.Result(null, false, "INVALID_INPUT", null));
        }
    }

    @Override protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        Peer peer = peers.get(session.getId());
        if (peer != null) peer.lastPong = System.nanoTime();
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Peer peer = peers.remove(session.getId());
        if (peer != null) peer.fail();
    }

    @Override public void handleTransportError(WebSocketSession session, Throwable exception) {
        Peer peer = peers.get(session.getId());
        if (peer != null) peer.fail();
    }

    @Scheduled(fixedDelay = 10000)
    public void checkConnections() {
        long now = System.nanoTime();
        for (Peer peer : peers.values()) {
            if ((!peer.connection.attached() && now - peer.opened > Duration.ofSeconds(20).toNanos())
                    || now - peer.lastPong > Duration.ofSeconds(60).toNanos()
                    || (peer.sendingSince != 0 && now - peer.sendingSince > Duration.ofSeconds(10).toNanos()))
                peer.fail();
            else if (!peer.ending.get()) peer.offer(new PingMessage(ByteBuffer.allocate(0)));
        }
    }

    @PreDestroy public void shutdown() {
        peers.values().forEach(Peer::fail);
        writers.shutdown();
    }

    private final class Peer implements RoomRealtime.Client {
        private final WebSocketSession session;
        private final BlockingQueue<Object> outbound = new ArrayBlockingQueue<>(64);
        private final AtomicBoolean ending = new AtomicBoolean();
        private final AtomicBoolean failed = new AtomicBoolean();
        private final long opened = System.nanoTime();
        private volatile long lastPong = opened;
        private volatile long sendingSince;
        private RoomRealtime.Connection connection;

        Peer(WebSocketSession session) { this.session = session; }

        @Override public synchronized void enqueue(RoomProtocol.Message message) {
            if (!ending.get()) offer(message);
        }

        synchronized void offer(Object message) {
            if (failed.get()) return;
            if (!outbound.offer(message)) fail();
        }

        @Override public synchronized void terminate(RoomProtocol.Terminal message) {
            if (ending.compareAndSet(false, true)) {
                // Discard queued snapshots immediately on revocation; never leak later state.
                outbound.clear();
                offer(message);
            }
        }

        void write() {
            try {
                while (!failed.get()) {
                    Object next = outbound.take();
                    if (failed.get()) break;
                    if (ending.get() && !(next instanceof RoomProtocol.Terminal)) continue;
                    WebSocketMessage<?> message = next instanceof WebSocketMessage<?> frame ? frame
                            : new TextMessage(json.writeValueAsString(next));
                    sendingSince = System.nanoTime();
                    session.sendMessage(message);
                    sendingSince = 0;
                    if (next instanceof RoomProtocol.Terminal) break;
                }
            } catch (IOException | InterruptedException | RuntimeException ignored) {
                // Safe terminal handling; never log raw messages or transport exceptions.
            } finally { fail(); }
        }

        synchronized void fail() {
            ending.set(true);
            if (!failed.compareAndSet(false, true)) return;
            outbound.clear();
            outbound.offer(new RoomProtocol.Terminal("ACCESS_REVOKED", "CONNECTION_CLOSED"));
            peers.remove(session.getId(), this);
            // This can be requested under a room lock. Presence and network close run later.
            writers.execute(() -> {
                realtime.disconnected(connection);
                try { session.close(CloseStatus.NORMAL); }
                catch (IOException ignored) { }
            });
        }
    }
}
