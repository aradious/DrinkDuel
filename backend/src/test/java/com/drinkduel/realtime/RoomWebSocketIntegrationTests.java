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
    @Autowired RoomStore store;

    @Test void submitAndResetOverRealTransportNeverSendSecretText() throws Exception {
        var room = service.createRoom(new PlayerIdentity.Google("game-owner"), "GM");
        var token = GuestToken.generate();
        try (var http = HttpClient.newHttpClient()) {
            var messages = new Listener();
            var socket = http.newWebSocketBuilder().buildAsync(uri(), messages).get(5, TimeUnit.SECONDS);
            try {
                send(socket, "JOIN_ROOM", room.id(), token, "Ken");
                messages.next("STATE");
                var opened = service.startWhoAmI(room.id(), room.owner());
                String sessionId = opened.currentSession().orElseThrow().id().toString();
                assertEquals("SUBMIT_NAME", messages.next("STATE").get("room").get("game").get("phase").asString());
                var command = new RoomProtocol.Command("SUBMIT_NAME", UUID.randomUUID().toString(), room.id(),
                        null, null, null, sessionId, "WIRE-SECRET-SENTINEL", null);
                socket.sendText(new JsonMapper().writeValueAsString(command), true).get(5, TimeUnit.SECONDS);
                JsonNode submitted = messages.next("STATE");
                assertTrue(submitted.get("room").get("game").get("currentPlayerSubmitted").asBoolean());
                assertFalse(submitted.toString().contains("WIRE-SECRET-SENTINEL"));
                assertFalse(messages.next("COMMAND_RESULT").toString().contains("WIRE-SECRET-SENTINEL"));
                var current = store.find(room.id()).orElseThrow();
                UUID playerId = current.playerFor(token.identity()).id();
                var state = (com.drinkduel.game.WhoAmIState) current.currentSession().orElseThrow().state();
                service.resetSubmission(room.id(), room.owner(), UUID.fromString(sessionId), playerId,
                        state.submissions().get(playerId).id());
                JsonNode reset = messages.next("STATE");
                assertEquals(0, reset.get("room").get("game").get("submittedCount").asInt());
                assertFalse(reset.toString().contains("WIRE-SECRET-SENTINEL"));
            } finally { socket.abort(); }
        }
    }

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


    @Test void playingWireViewsHideOwnSecretOnShuffleGetStateAndReconnect() throws Exception {
        var room = service.createRoom(new PlayerIdentity.Google("wire-shuffle-owner"), "GM");
        var token = GuestToken.generate();
        try (var http = HttpClient.newHttpClient()) {
            var messages = new Listener();
            var socket = http.newWebSocketBuilder().buildAsync(uri(), messages).get(5, TimeUnit.SECONDS);
            WebSocket back = null;
            try {
                send(socket, "JOIN_ROOM", room.id(), token, "Ken");
                var initial = messages.next("STATE").get("room");
                messages.next("COMMAND_RESULT");
                UUID guestId = UUID.fromString(initial.get("currentPlayerId").asString());
                var opened = service.startWhoAmI(room.id(), room.owner());
                UUID session = opened.currentSession().orElseThrow().id();
                messages.next("STATE");
                service.submitName(room.id(), room.owner(), session, "WIRE-OWN-HIDDEN");
                messages.next("STATE");
                service.submitName(room.id(), token.identity(), session, "WIRE-OTHER-VISIBLE");
                messages.next("STATE");
                service.shuffle(room.id(), room.owner(), session);
                assertPlayingWire(messages.next("STATE"), guestId, token, room);
                send(socket, "GET_STATE", room.id(), null, null);
                assertPlayingWire(messages.next("STATE"), guestId, token, room);
                assertFalse(messages.next("COMMAND_RESULT").toString().contains("WIRE-"));
                // Both the known target field and an unknown recipient override must be rejected.
                var forged = new RoomProtocol.Command("GET_STATE", UUID.randomUUID().toString(), room.id(),
                        null, null, room.gmPlayerId().toString());
                socket.sendText(new JsonMapper().writeValueAsString(forged), true).get(5, TimeUnit.SECONDS);
                var error = messages.next("COMMAND_RESULT");
                assertEquals("INVALID_INPUT", error.get("code").asString());
                assertFalse(error.toString().contains("WIRE-"));
                String unknown = new JsonMapper().writeValueAsString(
                        new RoomProtocol.Command("GET_STATE", UUID.randomUUID().toString(), room.id(), null, null, null));
                unknown = unknown.substring(0, unknown.length() - 1) + ",\"playerId\":\"" + room.gmPlayerId() + "\"}";
                socket.sendText(unknown, true).get(5, TimeUnit.SECONDS);
                error = messages.next("COMMAND_RESULT");
                assertEquals("INVALID_INPUT", error.get("code").asString());
                assertFalse(error.toString().contains("WIRE-"));
                var before = store.find(room.id()).orElseThrow().currentSession().orElseThrow();
                var resumed = new Listener();
                back = http.newWebSocketBuilder().buildAsync(uri(), resumed).get(5, TimeUnit.SECONDS);
                send(back, "RESUME_ROOM", room.id(), token, null);
                var restored = resumed.next("STATE");
                assertPlayingWire(restored, guestId, token, room);
                assertEquals(initial.get("players"), restored.get("room").get("players"));
                assertSame(before, store.find(room.id()).orElseThrow().currentSession().orElseThrow());
            } finally {
                socket.abort();
                if (back != null) back.abort();
            }
        }
    }


    @Test void statusCommandsOverWebSocketEnforceAuthorityAndNeverRevealOwnAnswer() throws Exception {
        var room = service.createRoom(new PlayerIdentity.Google("status-wire-owner"), "GM");
        var token = GuestToken.generate();
        try (var http = HttpClient.newHttpClient()) {
            var messages = new Listener();
            var socket = http.newWebSocketBuilder().buildAsync(uri(), messages).get(5, TimeUnit.SECONDS);
            try {
                send(socket, "JOIN_ROOM", room.id(), token, "Ken");
                UUID guestId = UUID.fromString(messages.next("STATE").get("room").get("currentPlayerId").asString());
                messages.next("COMMAND_RESULT");
                UUID session = service.startWhoAmI(room.id(), room.owner()).currentSession().orElseThrow().id();
                messages.next("STATE");
                service.submitName(room.id(), room.owner(), session, "WIRE-OWN-HIDDEN");
                messages.next("STATE");
                service.submitName(room.id(), token.identity(), session, "WIRE-OTHER-VISIBLE");
                messages.next("STATE");
                service.shuffle(room.id(), room.owner(), session);
                messages.next("STATE");
                var forged = new RoomProtocol.Command("GM_MARK_GOT_IT", UUID.randomUUID().toString(), room.id(),
                        null, null, guestId.toString(), session.toString(), null, null);
                socket.sendText(new JsonMapper().writeValueAsString(forged), true).get(5, TimeUnit.SECONDS);
                var denied = messages.next("COMMAND_RESULT");
                assertEquals("NOT_AUTHORIZED", denied.get("code").asString());
                assertFalse(denied.toString().contains("WIRE-"));
                var give = new RoomProtocol.Command("GIVE_UP", UUID.randomUUID().toString(), room.id(),
                        null, null, null, session.toString(), null, null);
                socket.sendText(new JsonMapper().writeValueAsString(give), true).get(5, TimeUnit.SECONDS);
                var gaveUp = messages.next("STATE");
                assertPlayingWire(gaveUp, guestId, token, room);
                assertEquals("GAVE_UP", gaveUp.get("room").get("game").get("cards").get(1).get("gameStatus").asString());
                assertTrue(messages.next("COMMAND_RESULT").get("accepted").asBoolean());
                service.resetPlayerStatus(room.id(), room.owner(), session, guestId, 1);
                var reset = messages.next("STATE");
                assertPlayingWire(reset, guestId, token, room);
                assertEquals("PLAYING", reset.get("room").get("game").get("cards").get(1).get("gameStatus").asString());
                service.markGotIt(room.id(), room.owner(), session, guestId);
                var gotIt = messages.next("STATE");
                assertPlayingWire(gotIt, guestId, token, room);
                assertEquals("GOT_IT", gotIt.get("room").get("game").get("cards").get(1).get("gameStatus").asString());
                send(socket, "GET_STATE", room.id(), null, null);
                assertPlayingWire(messages.next("STATE"), guestId, token, room);
                messages.next("COMMAND_RESULT");
                socket.sendText(new JsonMapper().writeValueAsString(new RoomProtocol.Command("GIVE_UP",
                        UUID.randomUUID().toString(), room.id(), null, null, null, session.toString(), null, null)),
                        true).get(5, TimeUnit.SECONDS);
                var invalid = messages.next("COMMAND_RESULT");
                assertEquals("INVALID_TRANSITION", invalid.get("code").asString());
                assertFalse(invalid.toString().contains("WIRE-"));
            } finally { socket.abort(); }
        }
    }

    private void assertPlayingWire(JsonNode message, UUID self, GuestToken token, Room room) {
        String wire = message.toString();
        assertFalse(wire.contains("WIRE-OWN-HIDDEN"));
        assertTrue(wire.contains("WIRE-OTHER-VISIBLE"));
        for (String forbidden : java.util.List.of("submitterPlayerId", "submitterNickname", "resetSubmissionId",
                token.value(), token.identity().fingerprint(), room.owner().subject()))
            assertFalse(wire.contains(forbidden));
        var view = message.get("room");
        assertEquals(self.toString(), view.get("currentPlayerId").asString());
        assertEquals("PLAYING", view.get("game").get("phase").asString());
        for (JsonNode card : view.get("game").get("cards")) {
            if (card.get("playerId").asString().equals(self.toString())) assertTrue(card.get("assignedName").isNull());
            else assertEquals("WIRE-OTHER-VISIBLE", card.get("assignedName").asString());
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
