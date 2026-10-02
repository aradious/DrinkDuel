package com.drinkduel.transport;

import com.drinkduel.room.GuestToken;
import java.net.*;
import java.net.http.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"drinkduel.dev-identity.enabled=true", "server.servlet.session.cookie.secure=false"})
class DefaultIdentityTransportTests {
    @Value("${local.server.port}") int port;
    final JsonMapper json = new JsonMapper();
    String base() { return "http://localhost:" + port; }
    HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    HttpResponse<String> post(HttpClient client, String path, String body, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + path)).header("Content-Type", "application/json");
        if (origin != null) request.header("Origin", origin);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test void productionHostSessionCreatesRoomAndDevEndpointIsUnavailable() throws Exception {
        try (var client = browser()) {
            // Browser same-origin GET requests are not guaranteed to carry Origin. This probe is read-only.
            assertEquals(204, get(client, "/api/host/session").statusCode());
            assertEquals(404, post(client, "/api/dev/session", "", base()).statusCode());
            assertEquals(403, post(client, "/api/host/session", "", "https://evil.example").statusCode());
            assertEquals(401, post(client, "/api/rooms", "{\"nickname\":\"GM\"}", base()).statusCode());

            var identity = post(client, "/api/host/session", "", base());
            assertEquals(204, identity.statusCode());
            String cookie = identity.headers().firstValue("set-cookie").orElseThrow();
            assertTrue(cookie.contains("HttpOnly"));
            assertTrue(cookie.contains("SameSite=Strict"));

            var created = post(client, "/api/rooms", "{\"nickname\":\"Host\"}", base());
            assertEquals(201, created.statusCode());
            assertTrue(json.readTree(created.body()).get("roomId").asString().matches("[A-HJ-NP-Z2-9]{6}"));
        }
    }

    @Test void trustedForwardedHttpsOriginPassesProductionOriginValidation() throws Exception {
        try (var client = browser()) {
            var request = HttpRequest.newBuilder(URI.create(base() + "/api/host/session"))
                    .header("Origin", "https://play.example")
                    .header("X-Forwarded-Host", "play.example")
                    .header("X-Forwarded-Proto", "https")
                    .header("X-Forwarded-Port", "443")
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(204, response.statusCode());
            assertTrue(response.headers().firstValue("set-cookie").orElseThrow().contains("HttpOnly"));
        }
    }

    @Test void forgedHeadersAndDifferentBrowserCannotClaimHostOwnership() throws Exception {
        try (var owner = browser(); var attacker = browser()) {
            post(owner, "/api/host/session", "", base());
            String room = json.readTree(post(owner, "/api/rooms", "{\"nickname\":\"Host\"}", base()).body())
                    .get("roomId").asString();

            var forged = HttpRequest.newBuilder(URI.create(base() + "/api/rooms"))
                    .header("Origin", base()).header("X-Google-Subject", "fake-owner")
                    .header("X-Player-Id", UUID.randomUUID().toString()).header("X-GM", "true")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"nickname\":\"Fake\"}")).build();
            assertEquals(401, attacker.send(forged, HttpResponse.BodyHandlers.ofString()).statusCode());

            post(attacker, "/api/host/session", "", base());
            var messages = new Messages();
            var socket = connect(attacker, messages);
            try {
                send(socket, "RESUME_ROOM", room, "");
                assertEquals("NOT_AUTHORIZED", messages.next("COMMAND_RESULT").get("code").asString());
            } finally { socket.abort(); }
        }
    }

    @Test void hostRefreshAndGuestFlowShareAuthoritativeWebSocketIdentity() throws Exception {
        try (var owner = browser(); var guestBrowser = browser()) {
            post(owner, "/api/host/session", "", base());
            String room = json.readTree(post(owner, "/api/rooms", "{\"nickname\":\"Host\"}", base()).body())
                    .get("roomId").asString();
            var firstMessages = new Messages();
            var first = connect(owner, firstMessages);
            var guestMessages = new Messages();
            var guestSocket = connect(guestBrowser, guestMessages);
            WebSocket returned = null;
            try {
                send(first, "RESUME_ROOM", room, "");
                var initial = firstMessages.next("STATE").get("room");
                firstMessages.next("COMMAND_RESULT");
                assertTrue(initial.get("isGm").asBoolean());

                var guest = GuestToken.generate();
                send(guestSocket, "JOIN_ROOM", room,
                        ",\"nickname\":\"Ken\",\"guestToken\":\"" + guest.value() + "\"");
                var joined = guestMessages.next("STATE").get("room");
                guestMessages.next("COMMAND_RESULT");
                assertFalse(joined.get("isGm").asBoolean());
                send(guestSocket, "GM_CLOSE_ROOM", room, "");
                assertEquals("NOT_AUTHORIZED", guestMessages.next("COMMAND_RESULT").get("code").asString());

                var returnedMessages = new Messages();
                returned = connect(owner, returnedMessages);
                send(returned, "RESUME_ROOM", room, "");
                var resumed = returnedMessages.next("STATE").get("room");
                returnedMessages.next("COMMAND_RESULT");
                assertTrue(resumed.get("isGm").asBoolean());
                assertEquals(initial.get("currentPlayerId"), resumed.get("currentPlayerId"));
                assertEquals(initial.get("players").get(0).get("avatarId"),
                        resumed.get("players").get(0).get("avatarId"));
            } finally {
                first.abort();
                guestSocket.abort();
                if (returned != null) returned.abort();
            }
        }
    }

    WebSocket connect(HttpClient client, Messages messages) throws Exception {
        return client.newWebSocketBuilder().header("Origin", base())
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/rooms"), messages)
                .get(5, TimeUnit.SECONDS);
    }
    void send(WebSocket socket, String type, String room, String fields) throws Exception {
        socket.sendText("{\"type\":\"" + type + "\",\"requestId\":\"" + UUID.randomUUID()
                + "\",\"roomId\":\"" + room + "\"" + fields + "}", true).get(5, TimeUnit.SECONDS);
    }
    static final class Messages implements WebSocket.Listener {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        final StringBuilder buffer = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            buffer.append(text);
            if (last) { queue.add(buffer.toString()); buffer.setLength(0); }
            socket.request(1);
            return null;
        }
        JsonNode next(String type) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                String raw = queue.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertNotNull(raw, type);
                var value = new JsonMapper().readTree(raw);
                if (value.get("type").asString().equals(type)) return value;
            }
        }
    }
}
