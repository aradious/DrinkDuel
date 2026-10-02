package com.drinkduel.realtime;

import com.drinkduel.game.*;
import com.drinkduel.room.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class WhoAmIShuffleRealtimeTests {
    RoomStore store;
    RoomService service;
    RoomRealtime realtime;
    Room room;
    GuestToken token;
    Peer gm, guest;
    final JsonMapper json = new JsonMapper();

    record Peer(RoomRealtime.Connection connection, RoomRealtimeTests.Client client) {}
    Peer peer(PlayerIdentity.Owner owner) {
        var client = new RoomRealtimeTests.Client();
        return new Peer(realtime.open(client, owner), client);
    }
    @BeforeEach void setup() {
        var avatars = new AvatarCatalog();
        store = new InMemoryRoomStore(Clock.systemUTC(), avatars);
        service = new RoomService(store, avatars);
        realtime = new RoomRealtime(store, service, Clock.systemUTC());
        room = service.createRoom(new PlayerIdentity.Google("OWNER-CREDENTIAL"), "GM");
        token = GuestToken.generate();
        gm = peer(room.owner());
        guest = peer(null);
        send(gm, new RoomProtocol.Command("RESUME_ROOM", id(), room.id(), null, null, null));
        send(guest, new RoomProtocol.Command("JOIN_ROOM", id(), room.id(), "Ken", token.value(), null));
        send(gm, new RoomProtocol.Command("GM_START_GAME", id(), room.id(), null, null, null));
        send(gm, command("SUBMIT_NAME", null, "SECRET-FROM-GM", null));
        send(guest, command("SUBMIT_NAME", null, "SECRET-FROM-KEN", null));
        gm.client.messages.clear();
        guest.client.messages.clear();
    }
    String id() { return UUID.randomUUID().toString(); }
    Room current() { return store.find(room.id()).orElseThrow(); }
    UUID session() { return current().currentSession().orElseThrow().id(); }
    WhoAmIState state() { return (WhoAmIState) current().currentSession().orElseThrow().state(); }
    RoomProtocol.Command command(String type, UUID target, String secret, UUID expected) {
        return new RoomProtocol.Command(type, id(), room.id(), null, null,
                target == null ? null : target.toString(), session().toString(), secret,
                expected == null ? null : expected.toString());
    }
    void send(Peer peer, RoomProtocol.Command command) { realtime.command(peer.connection, command); }
    void shuffle() {
        send(gm, command("GM_SHUFFLE", null, null, null));
        assertTrue(gm.client.result().accepted());
    }
    void safe(Peer peer) {
        UUID recipient = peer.client.state().currentPlayerId();
        String own = state().assignments().get(recipient).submission().text();
        for (var message : peer.client.messages) {
            String wire = json.writeValueAsString(message);
            assertFalse(wire.contains(own), "Own assigned secret leaked");
            for (String forbidden : List.of(token.value(), token.identity().fingerprint(), ((PlayerIdentity.Google) room.owner()).subject(),
                    "submitterNickname", "submitterPlayerId", "resetSubmissionId", "submissionId", "Created by"))
                assertFalse(wire.contains(forbidden), "Forbidden metadata leaked: " + forbidden);
            for (var submission : state().submissions().values())
                assertFalse(wire.contains(submission.id().toString()), "Submission ID leaked");
            if (message instanceof RoomProtocol.State update) {
                var view = update.room();
                assertEquals(recipient, view.currentPlayerId());
                assertEquals("PLAYING", view.game().phase());
                assertTrue(view.game().participants().isEmpty());
                for (var card : view.game().cards()) {
                    assertEquals("PLAYING", card.gameStatus());
                    if (card.playerId().equals(recipient)) assertNull(card.assignedName());
                    else assertEquals(state().assignments().get(card.playerId()).submission().text(), card.assignedName());
                }
            } else {
                assertFalse(wire.contains("SECRET-FROM-"));
            }
        }
    }

    @Test void shuffleBroadcastsDifferentPersonalizedViewsWithNoAttributionIncludingGm() {
        shuffle();
        assertEquals(2, gm.client.state().game().cards().size());
        assertEquals(2, guest.client.state().game().cards().size());
        assertNotEquals(gm.client.state().game().cards(), guest.client.state().game().cards());
        for (var peer : List.of(gm, guest)) {
            safe(peer);
            assertFalse(peer.client.state().game().canShuffle());
            assertFalse(peer.client.state().allowedActions().contains("GM_SHUFFLE"));
            assertFalse(peer.client.state().allowedActions().contains("SUBMIT_NAME"));
            assertFalse(peer.client.state().allowedActions().contains("GM_RESET_SUBMISSION"));
        }
    }

    @Test void playerCannotShuffleAndForgedTargetCannotSelectAnotherView() {
        send(guest, command("GM_SHUFFLE", null, null, null));
        assertEquals("NOT_AUTHORIZED", guest.client.result().code());
        assertTrue(state().assignments().isEmpty());
        shuffle();
        send(guest, new RoomProtocol.Command("GET_STATE", id(), room.id(), null, null, room.gmPlayerId().toString()));
        assertEquals("INVALID_INPUT", guest.client.result().code());
        send(guest, new RoomProtocol.Command("GET_STATE", id(), room.id(), null, null, null));
        safe(guest);
    }

    @Test void duplicateAndNewShuffleRequestsDoNotReassignAndAllResponsesStaySafe() {
        var command = command("GM_SHUFFLE", null, null, null);
        send(gm, command);
        var before = current();
        send(gm, command);
        assertTrue(gm.client.result().accepted());
        assertSame(before, current());
        send(gm, command("GM_SHUFFLE", null, null, null));
        assertEquals("INVALID_GAME_PHASE", gm.client.result().code());
        send(gm, new RoomProtocol.Command("GM_SHUFFLE", id(), room.id(), null, null, null,
                UUID.randomUUID().toString(), null, null));
        assertEquals("STALE_COMMAND", gm.client.result().code());
        send(guest, command("SUBMIT_NAME", null, "SHOULD-NOT-ECHO", null));
        assertEquals("INVALID_GAME_PHASE", guest.client.result().code());
        send(gm, command("GM_RESET_SUBMISSION", room.gmPlayerId(), null,
                state().submissions().get(room.gmPlayerId()).id()));
        assertEquals("INVALID_GAME_PHASE", gm.client.result().code());
        assertSame(before, current());
        safe(gm);
        safe(guest);
    }

    @Test void reconnectAndGetStatePreserveAssignmentAndHideOwnSecretForGuestAndGm() {
        shuffle();
        var before = current();
        var assignments = state().assignments();
        realtime.disconnected(guest.connection);
        realtime.disconnected(gm.connection);
        var guestBack = peer(null);
        send(guestBack, new RoomProtocol.Command("RESUME_ROOM", id(), room.id(), null, token.value(), null));
        var gmBack = peer(room.owner());
        send(gmBack, new RoomProtocol.Command("RESUME_ROOM", id(), room.id(), null, null, null));
        send(guestBack, new RoomProtocol.Command("GET_STATE", id(), room.id(), null, null, null));
        send(gmBack, new RoomProtocol.Command("GET_STATE", id(), room.id(), null, null, null));
        assertEquals(before.players(), current().players());
        assertEquals(assignments, state().assignments());
        assertEquals(before.currentSession().orElseThrow().id(), session());
        safe(guestBack);
        safe(gmBack);
    }

    @Test void reconnectRacingShuffleGetsFinalPersonalizedState() throws Exception {
        var replacement = peer(null);
        var latch = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var shuffle = pool.submit(() -> { latch.await(); shuffle(); return null; });
            var resume = pool.submit(() -> {
                latch.await();
                send(replacement, new RoomProtocol.Command("RESUME_ROOM", id(), room.id(), null, token.value(), null));
                return null;
            });
            latch.countDown();
            shuffle.get(5, TimeUnit.SECONDS);
            resume.get(5, TimeUnit.SECONDS);
        }
        assertEquals(current().revision(), replacement.client.state().roomRevision());
        assertEquals("PLAYING", replacement.client.state().game().phase());
        // Earlier Submit Name snapshots are also safe; only inspect Playing snapshots here.
        replacement.client.messages.removeIf(m -> m instanceof RoomProtocol.State s
                && !s.room().game().phase().equals("PLAYING"));
        safe(replacement);
    }

    @Test void kickAfterShuffleRevokesGuestAndKeepsRemainingSecretHidden() {
        shuffle();
        UUID guestId = current().playerFor(token.identity()).id();
        var assignment = state().assignments().get(room.gmPlayerId());
        gm.client.messages.clear();
        send(gm, command("GM_KICK_PLAYER", guestId, null, null));
        assertEquals("PLAYER_KICKED", guest.client.terminal().reason());
        assertEquals(1, gm.client.state().game().cards().size());
        assertSame(assignment, state().assignments().get(room.gmPlayerId()));
        assertEquals("Ken", assignment.submission().submitterNickname());
        safe(gm);
        var back = peer(null);
        send(back, new RoomProtocol.Command("RESUME_ROOM", id(), room.id(), null, token.value(), null));
        assertEquals("PLAYER_KICKED", back.client.result().code());
        assertTrue(back.client.messages.stream().noneMatch(m -> m instanceof RoomProtocol.State));
    }

    @Test void kickRacingGetStateNeverSendsAnUnauthorizedOrUnfilteredView() throws Exception {
        shuffle();
        UUID guestId = current().playerFor(token.identity()).id();
        var latch = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var kick = pool.submit(() -> {
                latch.await(); send(gm, command("GM_KICK_PLAYER", guestId, null, null)); return null;
            });
            var get = pool.submit(() -> {
                latch.await(); send(guest, new RoomProtocol.Command("GET_STATE", id(), room.id(), null, null, null));
                return null;
            });
            latch.countDown();
            kick.get(5, TimeUnit.SECONDS);
            get.get(5, TimeUnit.SECONDS);
        }
        boolean revoked = false;
        for (var message : guest.client.messages) {
            assertFalse(json.writeValueAsString(message).contains("SECRET-FROM-GM"));
            if (message instanceof RoomProtocol.Terminal) revoked = true;
            else assertFalse(revoked, "Message sent after revocation");
        }
        assertTrue(revoked);
        assertEquals(1, state().assignments().size());
    }
}
