package com.drinkduel.realtime;

import com.drinkduel.game.*;
import com.drinkduel.room.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class WhoAmIStatusRealtimeTests {
    RoomStore store;
    RoomService service;
    RoomRealtime realtime;
    Room room;
    GuestToken token;
    Peer gm, guest;
    record Peer(RoomRealtime.Connection connection, RoomRealtimeTests.Client client) {}
    String id() { return UUID.randomUUID().toString(); }
    Peer peer(PlayerIdentity.Google owner) {
        var client = new RoomRealtimeTests.Client();
        return new Peer(realtime.open(client, owner), client);
    }
    @BeforeEach void setup() {
        var avatars = new AvatarCatalog();
        store = new InMemoryRoomStore(Clock.systemUTC(), avatars);
        service = new RoomService(store, avatars);
        realtime = new RoomRealtime(store, service, Clock.systemUTC());
        room = service.createRoom(new PlayerIdentity.Google("PRIVATE-OWNER"), "GM");
        gm = peer(room.owner());
        send(gm, new RoomProtocol.Command("RESUME_ROOM", id(), room.id(), null, null, null));
        token = GuestToken.generate();
        guest = peer(null);
        send(guest, new RoomProtocol.Command("JOIN_ROOM", id(), room.id(), "Ken", token.value(), null));
        service.startWhoAmI(room.id(), room.owner());
        service.submitName(room.id(), room.owner(), session(), "SECRET-FROM-GM");
        service.submitName(room.id(), token.identity(), session(), "SECRET-FROM-KEN");
        service.shuffle(room.id(), room.owner(), session());
        gm.client.messages.clear();
        guest.client.messages.clear();
        get(gm);
        get(guest);
    }
    Room current() { return store.find(room.id()).orElseThrow(); }
    UUID session() { return current().currentSession().orElseThrow().id(); }
    UUID player() { return current().playerFor(token.identity()).id(); }
    WhoAmIState state() { return (WhoAmIState) current().currentSession().orElseThrow().state(); }
    RoomProtocol.Command command(String type, UUID target, Long version) {
        return new RoomProtocol.Command(type, id(), room.id(), null, null,
                target == null ? null : target.toString(), session().toString(), null, null, version);
    }
    void send(Peer peer, RoomProtocol.Command command) { realtime.command(peer.connection, command); }
    void get(Peer peer) { send(peer, new RoomProtocol.Command("GET_STATE", id(), room.id(), null, null, null)); }
    void got(UUID player) { send(gm, command("GM_MARK_GOT_IT", player, null)); assertTrue(gm.client.result().accepted()); }
    void give(Peer peer) { send(peer, command("GIVE_UP", null, null)); assertTrue(peer.client.result().accepted()); }
    void reset(UUID player) {
        send(gm, command("GM_RESET_PLAYER_STATUS", player, state().results().get(player).version()));
        assertTrue(gm.client.result().accepted());
    }
    RoomProtocol.GameCard card(Peer peer, UUID player) {
        return peer.client.state().game().cards().stream().filter(c -> c.playerId().equals(player)).findFirst().orElseThrow();
    }
    void assertSafe(Peer peer) {
        UUID recipient = peer.client.state().currentPlayerId();
        String own = state().assignments().get(recipient).submission().text();
        var mapper = new JsonMapper();
        for (var message : peer.client.messages) {
            String wire = mapper.writeValueAsString(message);
            assertFalse(wire.contains(own));
            for (String forbidden : List.of("submitterPlayerId", "submitterNickname", "resetSubmissionId",
                    "clues", token.value(), token.identity().fingerprint(), room.owner().subject()))
                assertFalse(wire.contains(forbidden));
            for (var submission : state().submissions().values())
                assertFalse(wire.contains(submission.id().toString()));
            if (message instanceof RoomProtocol.State update) {
                assertEquals(recipient, update.room().currentPlayerId());
                for (var card : update.room().game().cards()) {
                    if (card.playerId().equals(recipient)) assertNull(card.assignedName());
                    else assertEquals(state().assignments().get(card.playerId()).submission().text(), card.assignedName());
                }
            } else assertFalse(wire.contains("SECRET-FROM-"));
        }
    }

    @Test void eachTransitionBroadcastsCorrectResultAndKeepsSecretsHiddenForBothRecipients() {
        var order = gm.client.state().game().cards().stream().map(RoomProtocol.GameCard::playerId).toList();
        var assignments = state().assignments();
        assertTrue(guest.client.state().allowedActions().contains("GIVE_UP"));
        assertFalse(guest.client.state().allowedActions().contains("GM_MARK_GOT_IT"));
        got(player());
        for (var p : List.of(gm, guest)) assertEquals("GOT_IT", card(p, player()).gameStatus());
        assertFalse(guest.client.state().allowedActions().contains("GIVE_UP"));
        reset(player());
        assertEquals("PLAYING", card(guest, player()).gameStatus());
        give(guest);
        for (var p : List.of(gm, guest)) assertEquals("GAVE_UP", card(p, player()).gameStatus());
        got(room.gmPlayerId());
        assertEquals("GOT_IT", card(gm, room.gmPlayerId()).gameStatus());
        assertEquals("PLAYING", gm.client.state().game().phase());
        assertFalse(gm.client.state().allowedActions().contains("GM_MARK_GOT_IT"));
        reset(room.gmPlayerId());
        give(gm);
        assertEquals("GAVE_UP", card(guest, room.gmPlayerId()).gameStatus());
        assertEquals(order, gm.client.state().game().cards().stream().map(RoomProtocol.GameCard::playerId).toList());
        assertEquals(assignments, state().assignments());
        get(gm); get(guest);
        assertSafe(gm); assertSafe(guest);
    }

    @Test void unauthorizedForgedAndMalformedCommandsCannotChangeResults() {
        var before = current();
        send(guest, command("GM_MARK_GOT_IT", player(), null));
        assertEquals("NOT_AUTHORIZED", guest.client.result().code());
        send(guest, command("GM_RESET_PLAYER_STATUS", player(), 0L));
        assertEquals("NOT_AUTHORIZED", guest.client.result().code());
        send(guest, command("GIVE_UP", room.gmPlayerId(), null));
        assertEquals("INVALID_INPUT", guest.client.result().code());
        send(gm, command("GM_RESET_PLAYER_STATUS", player(), null));
        assertEquals("INVALID_INPUT", gm.client.result().code());
        send(gm, command("GM_RESET_PLAYER_STATUS", player(), -1L));
        assertEquals("INVALID_INPUT", gm.client.result().code());
        send(gm, command("GM_MARK_GOT_IT", player(), 0L));
        assertEquals("INVALID_INPUT", gm.client.result().code());
        send(guest, new RoomProtocol.Command("GIVE_UP", id(), room.id(), null, null, null));
        assertEquals("INVALID_INPUT", guest.client.result().code());
        assertSame(before, current());
        assertSafe(gm); assertSafe(guest);
    }

    @Test void staleResetAndStaleSessionReturnFreshSafeState() {
        got(player());
        var old = command("GM_RESET_PLAYER_STATUS", player(), 1L);
        reset(player());
        got(player());
        var before = current();
        send(gm, old);
        assertEquals("STALE_COMMAND", gm.client.result().code());
        assertEquals(3, card(gm, player()).statusVersion());
        send(guest, new RoomProtocol.Command("GIVE_UP", id(), room.id(), null, null, null,
                UUID.randomUUID().toString(), null, null));
        assertEquals("STALE_COMMAND", guest.client.result().code());
        assertEquals(current().revision(), guest.client.state().roomRevision());
        assertSame(before, current());
        assertSafe(gm); assertSafe(guest);
    }

    @Test void cachedCommandsNeverReapplyAfterResetAndCacheIncludesResetVersion() {
        var mark = command("GM_MARK_GOT_IT", player(), null);
        send(gm, mark);
        var reset = command("GM_RESET_PLAYER_STATUS", player(), 1L);
        send(gm, reset);
        send(gm, mark);
        assertEquals("PLAYING", card(gm, player()).gameStatus());
        got(player());
        var before = current();
        send(gm, reset);
        assertTrue(gm.client.result().accepted());
        assertSame(before, current());
        send(gm, new RoomProtocol.Command(reset.type(), reset.requestId(), room.id(), null, null,
                player().toString(), session().toString(), null, null, 3L));
        assertEquals("INVALID_INPUT", gm.client.result().code());
        assertSame(before, current());
        assertSafe(gm); assertSafe(guest);
    }

    @Test void gmOfflineGiveUpAndReconnectPreserveResultsAndPersonalization() {
        got(room.gmPlayerId());
        realtime.disconnected(gm.connection);
        assertTrue(guest.client.state().allowedActions().contains("GIVE_UP"));
        give(guest);
        var game = state();
        var players = current().players();
        realtime.disconnected(guest.connection);
        var returnedGuest = peer(null);
        send(returnedGuest, new RoomProtocol.Command("RESUME_ROOM", id(), room.id(), null, token.value(), null));
        var returnedGm = peer(room.owner());
        send(returnedGm, new RoomProtocol.Command("RESUME_ROOM", id(), room.id(), null, null, null));
        assertSame(game, state());
        assertEquals(players.get(1).id(), returnedGuest.client.state().currentPlayerId());
        assertEquals(players.get(1).avatarId(), card(returnedGuest, player()).avatarId());
        assertEquals("GAVE_UP", card(returnedGuest, player()).gameStatus());
        assertEquals("GOT_IT", card(returnedGm, room.gmPlayerId()).gameStatus());
        assertTrue(returnedGm.client.state().allowedActions().contains("GM_RESET_PLAYER_STATUS"));
        get(returnedGuest); get(returnedGm);
        assertSafe(returnedGuest); assertSafe(returnedGm);
    }

    @Test void finishedPlayerKickRemovesCardAndRetainsRemainingAttribution() {
        give(guest);
        var retained = state().assignments().get(room.gmPlayerId());
        send(gm, command("GM_KICK_PLAYER", player(), null));
        assertEquals("PLAYER_KICKED", guest.client.terminal().reason());
        assertEquals(1, gm.client.state().game().cards().size());
        assertEquals(1, state().results().size());
        assertSame(retained, state().assignments().get(room.gmPlayerId()));
        assertEquals("Ken", retained.submission().submitterNickname());
        // Inspect only post-kick views; previous views legitimately included the former member.
        gm.client.messages.clear();
        get(gm);
        assertSafe(gm);
    }

    @Test void cluesCommandsAreNotAccepted() {
        var before = current();
        for (String type : List.of("UPDATE_MY_CLUES", "GET_MY_CLUES")) {
            send(guest, command(type, null, null));
            assertEquals("INVALID_INPUT", guest.client.result().code());
        }
        assertSame(before, current());
        assertSafe(gm); assertSafe(guest);
    }
}
