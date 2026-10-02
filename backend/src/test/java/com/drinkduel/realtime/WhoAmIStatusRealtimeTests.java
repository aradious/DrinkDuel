package com.drinkduel.realtime;

import com.drinkduel.game.*;
import com.drinkduel.room.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class WhoAmIStatusRealtimeTests {
    RoomStore store; RoomService service; RoomRealtime realtime; Room room; GuestToken token; Peer gm, guest;
    record Peer(RoomRealtime.Connection connection, RoomRealtimeTests.Client client) {}
    String id() { return UUID.randomUUID().toString(); }
    Peer peer(PlayerIdentity.Owner owner) {
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
        token = GuestToken.generate(); guest = peer(null);
        send(guest, new RoomProtocol.Command("JOIN_ROOM", id(), room.id(), "Ken", token.value(), null));
        service.startWhoAmI(room.id(), room.owner());
        service.submitName(room.id(), room.owner(), session(), "SECRET-FROM-GM");
        service.submitName(room.id(), token.identity(), session(), "SECRET-FROM-KEN");
        service.shuffle(room.id(), room.owner(), session());
        gm.client.messages.clear(); guest.client.messages.clear(); get(gm); get(guest);
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
    void got(UUID target) { send(gm, command("GM_MARK_GOT_IT", target, null)); assertTrue(gm.client.result().accepted()); }
    void reset(UUID target) {
        send(gm, command("GM_RESET_PLAYER_STATUS", target, state().results().get(target).version()));
        assertTrue(gm.client.result().accepted());
    }
    RoomProtocol.GameCard card(Peer peer, UUID target) {
        return peer.client.state().game().cards().stream().filter(c -> c.playerId().equals(target)).findFirst().orElseThrow();
    }
    void assertSafe(Peer peer) {
        UUID recipient = peer.client.state().currentPlayerId();
        String own = state().assignments().get(recipient).submission().text();
        var mapper = new JsonMapper();
        for (var message : peer.client.messages) {
            String wire = mapper.writeValueAsString(message);
            assertFalse(wire.contains(own));
            for (String forbidden : List.of("submitterPlayerId", "submitterNickname", "resetSubmissionId",
                    "clues", token.value(), token.identity().fingerprint(), ((PlayerIdentity.Google) room.owner()).subject()))
                assertFalse(wire.contains(forbidden));
            if (message instanceof RoomProtocol.State update) {
                for (var gameCard : update.room().game().cards()) {
                    if (gameCard.playerId().equals(recipient)) assertNull(gameCard.assignedName());
                }
            }
        }
    }

    @Test void gotItAndResetBroadcastToBothAndKeepSecretsHidden() {
        assertFalse(gm.client.state().allowedActions().contains("GIVE_UP"));
        assertFalse(guest.client.state().allowedActions().contains("GIVE_UP"));
        assertFalse(guest.client.state().allowedActions().contains("GM_MARK_GOT_IT"));
        got(player());
        for (var peer : List.of(gm, guest)) assertEquals("GOT_IT", card(peer, player()).gameStatus());
        reset(player());
        for (var peer : List.of(gm, guest)) assertEquals("PLAYING", card(peer, player()).gameStatus());
        assertSafe(gm); assertSafe(guest);
    }

    @Test void removedGiveUpCommandIsRejectedWithoutMutation() {
        var before = current();
        send(guest, new RoomProtocol.Command("GIVE_UP", id(), room.id(), null, null, null,
                session().toString(), null, null));
        assertEquals("INVALID_INPUT", guest.client.result().code());
        assertSame(before, current());
    }

    @Test void unauthorizedForgedAndMalformedCommandsCannotChangeResults() {
        var before = current();
        send(guest, command("GM_MARK_GOT_IT", player(), null));
        assertEquals("NOT_AUTHORIZED", guest.client.result().code());
        send(guest, command("GM_RESET_PLAYER_STATUS", player(), 0L));
        assertEquals("NOT_AUTHORIZED", guest.client.result().code());
        send(gm, command("GM_RESET_PLAYER_STATUS", player(), null));
        assertEquals("INVALID_INPUT", gm.client.result().code());
        send(gm, command("GM_RESET_PLAYER_STATUS", player(), -1L));
        assertEquals("INVALID_INPUT", gm.client.result().code());
        assertSame(before, current());
    }

    @Test void staleResetReturnsFreshSafeState() {
        got(player());
        var old = command("GM_RESET_PLAYER_STATUS", player(), 1L);
        reset(player()); got(player());
        var before = current();
        send(gm, old);
        assertEquals("STALE_COMMAND", gm.client.result().code());
        assertEquals(3, card(gm, player()).statusVersion());
        assertSame(before, current());
        assertSafe(gm); assertSafe(guest);
    }

    @Test void gmOfflineBlocksResultCommandsAndReconnectPreservesState() {
        got(player());
        var before = state();
        realtime.disconnected(gm.connection);
        assertFalse(guest.client.state().allowedActions().contains("GM_RESET_PLAYER_STATUS"));
        assertFalse(guest.client.state().allowedActions().contains("GIVE_UP"));
        var returned = peer(room.owner());
        send(returned, new RoomProtocol.Command("RESUME_ROOM", id(), room.id(), null, null, null));
        assertSame(before, state());
        assertEquals("GOT_IT", card(returned, player()).gameStatus());
        assertTrue(returned.client.state().allowedActions().contains("GM_RESET_PLAYER_STATUS"));
        assertSafe(returned);
    }
}
