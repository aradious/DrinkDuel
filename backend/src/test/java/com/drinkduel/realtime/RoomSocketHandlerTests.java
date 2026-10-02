package com.drinkduel.realtime;

import com.drinkduel.room.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.security.Principal;
import org.junit.jupiter.api.*;
import org.springframework.web.socket.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomSocketHandlerTests {
    private final JsonMapper json = new JsonMapper();
    private RoomStore store;
    private RoomService service;
    private RoomSocketHandler handler;
    private Room room;

    @BeforeEach void setup() {
        var avatars = new AvatarCatalog();
        store = new InMemoryRoomStore(Clock.systemUTC(), avatars);
        service = new RoomService(store, avatars);
        room = service.createRoom(new PlayerIdentity.Google("private-owner"), "GM");
        handler = new RoomSocketHandler(new RoomRealtime(store, service, Clock.systemUTC()));
    }

    @AfterEach void cleanup() { handler.shutdown(); }

    private Socket socket(Principal principal) throws Exception {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        when(session.getPrincipal()).thenReturn(principal);
        var messages = new LinkedBlockingQueue<String>();
        doAnswer(call -> {
            if (call.getArgument(0) instanceof TextMessage message) messages.add(message.getPayload());
            return null;
        }).when(session).sendMessage(any());
        handler.afterConnectionEstablished(session);
        return new Socket(session, messages);
    }

    private void send(Socket socket, String type, GuestToken token, String nickname) {
        var command = new RoomProtocol.Command(type, UUID.randomUUID().toString(), room.id(), nickname,
                token == null ? null : token.value(), null);
        handler.handleTextMessage(socket.session, new TextMessage(json.writeValueAsString(command)));
    }

    @Test void trustedPrincipalResumesGmButArbitraryPrincipalCannot() throws Exception {
        var trusted = socket(new AuthenticatedOwnerPrincipal(room.owner()));
        send(trusted, "RESUME_ROOM", null, null);
        assertTrue(trusted.next().get("room").get("isGm").asBoolean());
        assertTrue(trusted.next().get("accepted").asBoolean());
        var untrusted = socket(() -> ((PlayerIdentity.Google) room.owner()).subject());
        send(untrusted, "RESUME_ROOM", null, null);
        assertEquals("NOT_AUTHORIZED", untrusted.next().get("code").asString());
    }

    @Test void unknownIdentityClaimsMalformedJsonAndTrailingPayloadsAreSafe() throws Exception {
        var socket = socket(null);
        for (String payload : List.of("{\"isGM\":true,\"owner\":\"private-owner\"}",
                "{\"guestToken\":\"secret-in-parser-error", "null", "{} {}")) {
            handler.handleTextMessage(socket.session, new TextMessage(payload));
            JsonNode result = socket.next();
            assertEquals("INVALID_INPUT", result.get("code").asString());
            assertFalse(result.toString().contains("private-owner"));
            assertFalse(result.toString().contains("secret-in-parser-error"));
        }
        assertEquals(1, store.find(room.id()).orElseThrow().players().size());
    }

    @Test void socketLossAndRepeatedClosePreserveGuestAndMarkDisconnected() throws Exception {
        var socket = socket(null);
        var token = GuestToken.generate();
        send(socket, "JOIN_ROOM", token, "Ken");
        socket.next();
        socket.next();
        Player before = store.find(room.id()).orElseThrow().playerFor(token.identity());
        handler.afterConnectionClosed(socket.session, CloseStatus.NORMAL);
        handler.afterConnectionClosed(socket.session, CloseStatus.NORMAL);
        verify(socket.session, timeout(3000).times(1)).close(CloseStatus.NORMAL);
        var after = store.find(room.id()).orElseThrow().playerFor(token.identity());
        assertEquals(before.id(), after.id());
        assertEquals(before.avatarId(), after.avatarId());
        assertEquals(ConnectionState.DISCONNECTED, after.connectionState());
    }

    @Test void oversizedInputClosesWithoutApplyingCommands() throws Exception {
        var socket = socket(null);
        handler.handleTextMessage(socket.session, new TextMessage("x".repeat(RoomSocketHandler.MAX_TEXT_BYTES + 1)));
        verify(socket.session, timeout(3000)).close(CloseStatus.NORMAL);
        assertEquals(1, store.find(room.id()).orElseThrow().players().size());
    }

    @Test void slowSocketNeverHoldsRoomLockAndOverflowDisconnectsIt() throws Exception {
        var socket = socket(null);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(socket.session).sendMessage(any());
        var token = GuestToken.generate();
        try {
            send(socket, "JOIN_ROOM", token, "Slow");
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
                    service.joinRoom(room.id(), "Other", GuestToken.generate()));
            for (int i = 0; i < 80; i++) send(socket, "GET_STATE", null, null);
            verify(socket.session, timeout(3000)).close(CloseStatus.NORMAL);
            assertEquals(ConnectionState.DISCONNECTED,
                    store.find(room.id()).orElseThrow().playerFor(token.identity()).connectionState());
        } finally { release.countDown(); }
    }

    @Test void revocationDropsQueuedSnapshotsAndOnlyFinishesTheInFlightSend() throws Exception {
        var socket = socket(null);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sent = new CopyOnWriteArrayList<String>();
        doAnswer(call -> {
            if (call.getArgument(0) instanceof TextMessage message) {
                if (sent.isEmpty()) {
                    started.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }
                sent.add(message.getPayload());
            }
            return null;
        }).when(socket.session).sendMessage(any());
        var token = GuestToken.generate();
        try {
            send(socket, "JOIN_ROOM", token, "Ken");
            assertTrue(started.await(3, TimeUnit.SECONDS));
            service.joinRoom(room.id(), "Before kick", GuestToken.generate());
            UUID playerId = store.find(room.id()).orElseThrow().playerFor(token.identity()).id();
            service.kickPlayer(room.id(), room.owner(), playerId);
            service.joinRoom(room.id(), "After kick", GuestToken.generate());
            release.countDown();
            verify(socket.session, timeout(3000)).close(CloseStatus.NORMAL);
            assertEquals(2, sent.size());
            assertEquals("STATE", json.readTree(sent.getFirst()).get("type").asString());
            assertEquals("PLAYER_KICKED", json.readTree(sent.getLast()).get("reason").asString());
        } finally { release.countDown(); }
    }

    private record Socket(WebSocketSession session, BlockingQueue<String> messages) {
        JsonNode next() throws Exception {
            var message = messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(message, "Expected a protocol message");
            return new JsonMapper().readTree(message);
        }
    }
}
