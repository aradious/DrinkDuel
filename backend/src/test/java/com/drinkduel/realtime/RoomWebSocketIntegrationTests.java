package com.drinkduel.realtime;

import com.drinkduel.room.*;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomWebSocketIntegrationTests {
    @Value("${local.server.port}") int port;
    @Autowired RoomService service;

    @Test void realEndpointJoinsBroadcastsAndReplacesOldSocketWithFullResync() throws Exception {
        var room = service.createRoom(new PlayerIdentity.Google("integration-owner"), "GM");
        var token = GuestToken.generate();
        try (var http = HttpClient.newHttpClient()) {
            var first = new Listener();
            var socket = http.newWebSocketBuilder().buildAsync(uri(), first).get(5, TimeUnit.SECONDS);
            WebSocket replacement = null;
            try {
                send(socket, "JOIN_ROOM", room.id(), token, "Ken");
                JsonNode initial = first.next("STATE").get("room");
                assertEquals(2, initial.get("players").size());
                assertFalse(initial.toString().contains(token.value()));
                service.joinRoom(room.id(), "New friend", GuestToken.generate());
                assertEquals(3, first.next("STATE").get("room").get("players").size());
                var second = new Listener();
                replacement = http.newWebSocketBuilder().buildAsync(uri(), second).get(5, TimeUnit.SECONDS);
                send(replacement, "RESUME_ROOM", room.id(), token, null);
                JsonNode resumed = second.next("STATE").get("room");
                assertEquals(initial.get("currentPlayerId"), resumed.get("currentPlayerId"));
                assertEquals(3, resumed.get("players").size());
                assertEquals("REPLACED", first.next("ACCESS_REVOKED").get("reason").asString());
                send(replacement, "LEAVE_ROOM", room.id(), null, null);
                assertEquals("LEFT", second.next("ACCESS_REVOKED").get("reason").asString());
            } finally {
                socket.abort();
                if (replacement != null) replacement.abort();
            }
        }
    }

    @Test void foreignBrowserOriginIsRejectedAtHandshake() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var error = assertThrows(ExecutionException.class, () -> http.newWebSocketBuilder()
                    .header("Origin", "https://untrusted.example")
                    .buildAsync(uri(), new Listener()).get(5, TimeUnit.SECONDS));
            var handshake = assertInstanceOf(WebSocketHandshakeException.class, error.getCause());
            assertEquals(403, handshake.getResponse().statusCode());
        }
    }

    private URI uri() { return URI.create("ws://localhost:" + port + "/ws/rooms"); }
    private void send(WebSocket socket, String type, String roomId, GuestToken token, String nickname) throws Exception {
        var command = new RoomProtocol.Command(type, UUID.randomUUID().toString(), roomId, nickname,
                token == null ? null : token.value(), null);
        socket.sendText(new JsonMapper().writeValueAsString(command), true).get(5, TimeUnit.SECONDS);
    }

    private static final class Listener implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                messages.add(buffer.toString());
                buffer.setLength(0);
            }
            socket.request(1);
            return null;
        }
        JsonNode next(String type) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                String message = messages.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertNotNull(message, "Expected " + type);
                JsonNode node = new JsonMapper().readTree(message);
                if (type.equals(node.get("type").asString())) return node;
            }
        }
    }
}
