package com.drinkduel.transport;

import com.drinkduel.room.GuestToken;
import java.net.*;
import java.net.http.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "drinkduel.dev-identity.enabled=true")
@ActiveProfiles("local-preview")
class LocalIdentityTransportTests {
    @Value("${local.server.port}") int port;
    final JsonMapper json = new JsonMapper();
    String base() { return "http://localhost:" + port; }
    HttpClient browser() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); }
    HttpResponse<String> post(HttpClient client, String path, String body, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + path)).header("Content-Type", "application/json");
        if (origin != null) request.header("Origin", origin);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void loginRequiresSameOriginAndCreationRequiresSession() throws Exception {
        try (var client = browser()) {
            assertEquals(403, post(client, "/api/dev/session", "", "https://evil.example").statusCode());
            assertEquals(403, post(client, "/api/dev/session", "", null).statusCode());
            assertEquals(401, post(client, "/api/rooms", "{\"nickname\":\"GM\"}", base()).statusCode());
            var login = post(client, "/api/dev/session", "", base());
            assertEquals(204, login.statusCode());
            var cookie = login.headers().firstValue("set-cookie").orElseThrow();
            assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Strict"));
            assertEquals(403, post(client, "/api/rooms", "{\"nickname\":\"GM\"}", "https://evil.example").statusCode());
            assertEquals(400, post(client, "/api/rooms", "{\"nickname\":\" \"}", base()).statusCode());
        }
    }
    @Test void browserGmCanCreateResumeKickAndCloseButOtherIdentitiesCannot() throws Exception {
        try (var gm = browser(); var other = browser(); var guest = browser()) {
            post(gm, "/api/dev/session", "", base());
            var created = post(gm, "/api/rooms", "{\"nickname\":\"Host\"}", base());
            assertEquals(201, created.statusCode()); assertEquals(1, json.readTree(created.body()).size());
            String room = json.readTree(created.body()).get("roomId").asString();
            var hostMessages = new Messages(); var host = connect(gm, hostMessages);
            var guestMessages = new Messages(); var player = connect(guest, guestMessages);
            var wrongMessages = new Messages();
            post(other, "/api/dev/session", "", base()); var wrong = connect(other, wrongMessages);
            WebSocket returned = null;
            try {
                send(host, "RESUME_ROOM", room, ""); var initial = hostMessages.next("STATE").get("room"); hostMessages.next("COMMAND_RESULT");
                assertTrue(initial.get("isGm").asBoolean());
                send(wrong, "RESUME_ROOM", room, ""); assertEquals("NOT_AUTHORIZED", wrongMessages.next("COMMAND_RESULT").get("code").asString());
                var token = GuestToken.generate();
                send(player, "JOIN_ROOM", room, ",\"nickname\":\"Ken\",\"guestToken\":\"" + token.value() + "\"");
                var member = guestMessages.next("STATE").get("room"); guestMessages.next("COMMAND_RESULT");
                assertFalse(member.get("isGm").asBoolean());
                send(player, "GM_CLOSE_ROOM", room, ""); assertEquals("NOT_AUTHORIZED", guestMessages.next("COMMAND_RESULT").get("code").asString());
                // Re-login is idempotent, and another socket using the same HttpOnly cookie restores the owner.
                post(gm, "/api/dev/session", "", base()); var returnedMessages = new Messages(); returned = connect(gm, returnedMessages);
                send(returned, "RESUME_ROOM", room, ""); var resumed = returnedMessages.next("STATE").get("room"); returnedMessages.next("COMMAND_RESULT");
                assertEquals(initial.get("currentPlayerId"), resumed.get("currentPlayerId"));
                assertEquals(initial.get("players").get(0).get("avatarId"), resumed.get("players").get(0).get("avatarId"));
                send(returned, "GM_KICK_PLAYER", room, ",\"targetPlayerId\":\"" + member.get("currentPlayerId").asString() + "\"");
                assertEquals("PLAYER_KICKED", guestMessages.next("ACCESS_REVOKED").get("reason").asString());
                send(returned, "GM_CLOSE_ROOM", room, "");
                assertEquals("CLOSED", returnedMessages.next("ROOM_ENDED").get("reason").asString());
            } finally { host.abort(); player.abort(); wrong.abort(); if (returned != null) returned.abort(); }
        }
    }
    @Test void websocketStillRejectsForeignOriginInDevMode() throws Exception {
        try (var client = browser()) {
            post(client, "/api/dev/session", "", base());
            var error = assertThrows(ExecutionException.class, () -> client.newWebSocketBuilder()
                    .header("Origin", "https://evil.example").buildAsync(URI.create("ws://localhost:" + port + "/ws/rooms"), new Messages()).get(5, TimeUnit.SECONDS));
            assertEquals(403, assertInstanceOf(WebSocketHandshakeException.class, error.getCause()).getResponse().statusCode());
        }
    }
    WebSocket connect(HttpClient client, Messages messages) throws Exception {
        return client.newWebSocketBuilder().header("Origin", base())
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/rooms"), messages).get(5, TimeUnit.SECONDS);
    }
    void send(WebSocket socket, String type, String room, String fields) throws Exception {
        socket.sendText("{\"type\":\"" + type + "\",\"requestId\":\"" + UUID.randomUUID() + "\",\"roomId\":\"" + room + "\"" + fields + "}", true).get(5, TimeUnit.SECONDS);
    }
    static final class Messages implements WebSocket.Listener {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(); final StringBuilder buffer = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            buffer.append(text); if (last) { queue.add(buffer.toString()); buffer.setLength(0); } socket.request(1); return null;
        }
        JsonNode next(String type) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                String raw = queue.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); assertNotNull(raw, type);
                var value = new JsonMapper().readTree(raw); if (value.get("type").asString().equals(type)) return value;
            }
        }
    }
}
