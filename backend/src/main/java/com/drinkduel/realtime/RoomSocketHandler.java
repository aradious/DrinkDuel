package com.drinkduel.realtime;

import com.drinkduel.logging.SafeLog;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import jakarta.annotation.PreDestroy;

/** Native JSON WebSocket adapter. One bounded queue/writer per socket, never network I/O
 * on a room lock. Browser pong responses detect lost connections without gameplay timers.
 */
public final class RoomSocketHandler extends TextWebSocketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(RoomSocketHandler.class);
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
        Map<String, Object> attributes = session.getAttributes();
        Object attribute = attributes == null ? null
                : attributes.get(LoggingOriginHandshakeInterceptor.CORRELATION_ATTRIBUTE);
        String correlationId = attribute instanceof String value ? value : SafeLog.correlationId();
        var peer = new Peer(session, correlationId);
        try {
            var owner = session.getPrincipal() instanceof AuthenticatedOwnerPrincipal principal
                    ? principal.identity() : null;
            peer.connection = realtime.open(peer, owner);
            peers.put(session.getId(), peer);
            writers.execute(peer::write);
            LOG.info("event=ws.connection.open correlationId={} authenticatedHost={}",
                    correlationId, owner != null);
        } catch (RuntimeException exception) {
            SafeLog.unexpected(LOG, "ws.connection.open-failed", correlationId, exception);
            throw exception;
        }
    }

    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Peer peer = peers.get(session.getId());
        if (peer == null || peer.ending.get()) return;
        if (message.getPayloadLength() > MAX_TEXT_BYTES || !message.isLast()) {
            peer.fail("INVALID_FRAME", null, false);
            return;
        }
        try {
            RoomProtocol.Command command = json.readValue(message.getPayload(), RoomProtocol.Command.class);
            if (command == null) throw new IllegalArgumentException();
            realtime.command(peer.connection, command);
        } catch (RuntimeException exception) {
            // Do not echo parse errors: Jackson diagnostics may contain credentials.
            LOG.warn("event=ws.message.rejected correlationId={} reason=INVALID_INPUT exceptionType={}",
                    peer.correlationId, exception.getClass().getName());
            peer.enqueue(new RoomProtocol.Result(null, false, "INVALID_INPUT", null));
        }
    }

    @Override protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        Peer peer = peers.get(session.getId());
        if (peer != null) peer.lastPong = System.nanoTime();
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Peer peer = peers.remove(session.getId());
        if (peer != null) peer.fail("REMOTE_CLOSE", null, false);
    }

    @Override public void handleTransportError(WebSocketSession session, Throwable exception) {
        Peer peer = peers.get(session.getId());
        if (peer != null) peer.fail("TRANSPORT_ERROR", exception, false);
    }

    @Scheduled(fixedDelay = 10000)
    public void checkConnections() {
        long now = System.nanoTime();
        for (Peer peer : peers.values()) {
            if (!peer.connection.attached() && now - peer.opened > Duration.ofSeconds(20).toNanos())
                peer.fail("ATTACH_TIMEOUT", null, false);
            else if (now - peer.lastPong > Duration.ofSeconds(60).toNanos())
                peer.fail("PONG_TIMEOUT", null, false);
            else if (peer.sendingSince != 0 && now - peer.sendingSince > Duration.ofSeconds(10).toNanos())
                peer.fail("WRITE_TIMEOUT", null, false);
            else if (!peer.ending.get()) peer.offer(new PingMessage(ByteBuffer.allocate(0)));
        }
    }

    @PreDestroy public void shutdown() {
        peers.values().forEach(peer -> peer.fail("SERVER_SHUTDOWN", null, false));
        writers.shutdown();
    }

    private final class Peer implements RoomRealtime.Client {
        private final WebSocketSession session;
        private final String correlationId;
        private final BlockingQueue<Object> outbound = new ArrayBlockingQueue<>(64);
        private final AtomicBoolean ending = new AtomicBoolean();
        private final AtomicBoolean failed = new AtomicBoolean();
        private final long opened = System.nanoTime();
        private volatile long lastPong = opened;
        private volatile long sendingSince;
        private RoomRealtime.Connection connection;

        Peer(WebSocketSession session, String correlationId) {
            this.session = session;
            this.correlationId = correlationId;
        }

        @Override public synchronized void enqueue(RoomProtocol.Message message) {
            if (!ending.get()) offer(message);
        }

        synchronized void offer(Object message) {
            if (failed.get()) return;
            if (!outbound.offer(message)) fail("QUEUE_OVERFLOW", null, false);
        }

        @Override public synchronized void terminate(RoomProtocol.Terminal message) {
            if (ending.compareAndSet(false, true)) {
                // Discard queued snapshots immediately on revocation; never leak later state.
                outbound.clear();
                offer(message);
            }
        }

        void write() {
            String endingReason = "WRITER_ENDED";
            Throwable failure = null;
            boolean unexpected = false;
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
                    if (next instanceof RoomProtocol.Terminal) {
                        endingReason = "SERVER_TERMINAL";
                        break;
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                endingReason = "WRITER_INTERRUPTED";
                failure = exception;
            } catch (IOException exception) {
                endingReason = "WRITE_ERROR";
                failure = exception;
            } catch (RuntimeException exception) {
                endingReason = "WRITE_UNEXPECTED";
                failure = exception;
                unexpected = true;
            } finally { fail(endingReason, failure, unexpected); }
        }

        synchronized void fail(String reason, Throwable exception, boolean unexpected) {
            ending.set(true);
            if (!failed.compareAndSet(false, true)) return;
            outbound.clear();
            outbound.offer(new RoomProtocol.Terminal("ACCESS_REVOKED", "CONNECTION_CLOSED"));
            peers.remove(session.getId(), this);
            // This can be requested under a room lock. Presence and network close run later.
            writers.execute(() -> {
                if (unexpected && exception != null)
                    SafeLog.unexpected(LOG, "ws.connection.failed", correlationId, exception);
                else if (Set.of("REMOTE_CLOSE", "SERVER_TERMINAL", "SERVER_SHUTDOWN").contains(reason))
                    LOG.info("event=ws.connection.close correlationId={} reason={}", correlationId, reason);
                else
                    LOG.warn("event=ws.connection.close correlationId={} reason={} exceptionType={}",
                            correlationId, reason, exception == null ? "NONE" : exception.getClass().getName());
                realtime.disconnected(connection);
                try { session.close(CloseStatus.NORMAL); }
                catch (IOException closeException) {
                    LOG.warn("event=ws.connection.close-failed correlationId={} reason=CLOSE_IO_ERROR exceptionType={}",
                            correlationId, closeException.getClass().getName());
                }
            });
        }
    }
}
