package com.drinkduel.realtime;

import com.drinkduel.game.*;
import com.drinkduel.room.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class WhoAmILifecycleRealtimeTests {
    RoomStore store;
    RoomService service;
    RoomRealtime realtime;
    Room room;
    GuestToken token;
    Peer gm, guest;
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
    }
    Room current() { return store.find(room.id()).orElseThrow(); }
    UUID session() { return current().currentSession().orElseThrow().id(); }
    UUID player() { return current().playerFor(token.identity()).id(); }
    WhoAmIState state() { return (WhoAmIState) current().currentSession().orElseThrow().state(); }
    RoomProtocol.Command command(String type) {
        return new RoomProtocol.Command(type, id(), room.id(), null, null, null,
                current().currentSession().map(s -> s.id().toString()).orElse(null), null, null);
    }
    void send(Peer peer, RoomProtocol.Command command) { realtime.command(peer.connection, command); }
    void run(String type) { send(gm, command(type)); assertTrue(gm.client.result().accepted(), gm.client.result().code()); }
    void reveal() {
        run("GM_END_GAME");
        if (state().phase() == WhoAmIState.Phase.ROAST) run("GM_CONTINUE_REVEAL");
    }
    String wire(Object value) { return new JsonMapper().writeValueAsString(value); }
    void safe(RoomProtocol.Snapshot snapshot) {
        String json = wire(snapshot);
        for (String forbidden : List.of(token.value(), token.identity().fingerprint(), ((PlayerIdentity.Google) room.owner()).subject(),
                "submitterPlayerId", "submitterNickname", "statusVersion", "fingerprint", "expectedSubmissionId"))
            assertFalse(json.contains(forbidden), forbidden);
        for (var submission : state().submissions().values()) assertFalse(json.contains(submission.id().toString()));
    }
    @Test void transitionsBroadcastRoastThenRevealToAllWithOnlyApprovedInformation() {
        service.markGotIt(room.id(), room.owner(), session(), player());
        run("GM_END_GAME");
        for (var p : List.of(gm, guest)) {
            var view = p.client.state();
            assertEquals("ROAST", view.game().phase());
            assertEquals("LAST_ONE", view.game().roast().kind());
            assertEquals(List.of(room.gmPlayerId()), view.game().roast().playingPlayerIds());
            assertTrue(view.game().cards().isEmpty());
            assertTrue(view.game().reveal().isEmpty());
            assertFalse(wire(view).contains("SECRET-FROM"));
            assertFalse(wire(view).contains("createdBy"));
            safe(view);
        }
        assertEquals(List.of("GET_STATE"), guest.client.state().allowedActions());
        assertTrue(gm.client.state().allowedActions().contains("GM_CONTINUE_REVEAL"));
        assertFalse(gm.client.state().allowedActions().contains("GM_END_GAME"));
        run("GM_CONTINUE_REVEAL");
        for (var p : List.of(gm, guest)) {
            var view = p.client.state();
            assertEquals("REVEAL", view.game().phase());
            assertNull(view.game().roast());
            assertTrue(view.game().cards().isEmpty());
            assertEquals(2, view.game().reveal().size());
            for (var card : view.game().reveal()) {
                var submission = state().assignments().get(card.playerId()).submission();
                assertEquals(submission.text(), card.assignedName());
                assertEquals(submission.submitterNickname(), card.createdBy());
            }
            safe(view);
        }
        assertEquals(gm.client.state().game(), guest.client.state().game());
        assertTrue(gm.client.state().allowedActions().containsAll(List.of("GM_PLAY_AGAIN", "GM_BACK_TO_ROOM", "GM_CLOSE_ROOM")));
    }
    @ParameterizedTest @ValueSource(strings = {"GM_END_GAME", "GM_CONTINUE_REVEAL", "GM_PLAY_AGAIN", "GM_BACK_TO_ROOM", "GM_CLOSE_ROOM"})
    void guestCannotPerformLifecycleOrForgePayload(String type) {
        var before = current();
        send(guest, command(type));
        assertEquals("NOT_AUTHORIZED", guest.client.result().code());
        var c = command(type);
        send(gm, new RoomProtocol.Command(c.type(), c.requestId(), room.id(), null, null,
                player().toString(), c.sessionId(), null, null));
        assertEquals("INVALID_INPUT", gm.client.result().code());
        send(gm, new RoomProtocol.Command(c.type(), id(), room.id(), null, null, null,
                c.sessionId(), "FORGED-SECRET", null));
        assertEquals("INVALID_INPUT", gm.client.result().code());
        assertSame(before, current());
    }
    @Test void repeatedAndStaleCommandsCannotReplayAcrossRoundsOrLobby() {
        service.markGotIt(room.id(), room.owner(), session(), player());
        var end = command("GM_END_GAME"); send(gm, end);
        var roast = current(); send(gm, end); assertSame(roast, current());
        var next = command("GM_CONTINUE_REVEAL"); send(gm, next);
        var revealed = current(); send(gm, next); assertSame(revealed, current());
        var oldClose = command("GM_CLOSE_ROOM");
        var oldKick = new RoomProtocol.Command("GM_KICK_PLAYER", id(), room.id(), null, null,
                player().toString(), session().toString(), null, null);
        var again = command("GM_PLAY_AGAIN"); send(gm, again);
        var fresh = current();
        for (var stale : List.of(end, next, again, oldClose, oldKick)) {
            send(gm, stale); assertEquals("STALE_COMMAND", gm.client.result().code());
            assertSame(fresh, current());
            assertFalse(wire(gm.client.state()).contains("SECRET-FROM"));
        }
        service.submitName(room.id(), room.owner(), session(), "New GM");
        service.submitName(room.id(), token.identity(), session(), "New Ken");
        service.shuffle(room.id(), room.owner(), session()); reveal();
        var back = command("GM_BACK_TO_ROOM"); run("GM_BACK_TO_ROOM");
        var lobby = current();
        for (var stale : List.of(back, oldClose, oldKick)) {
            send(gm, stale); assertEquals("STALE_COMMAND", gm.client.result().code()); assertSame(lobby, current());
        }
        assertNull(gm.client.state().game());
        assertTrue(guest.client.state().joinable());
        assertTrue(guest.client.state().allowedActions().contains("LEAVE_ROOM"));
    }
    @Test void freshRoundClearsClientSnapshotsAndRequiresEveryoneToSubmit() {
        reveal(); var players = current().players(); UUID old = session(); run("GM_PLAY_AGAIN");
        assertEquals(players, current().players()); assertNotEquals(old, session());
        for (var p : List.of(gm, guest)) {
            var view = p.client.state();
            assertEquals("SUBMIT_NAME", view.game().phase());
            assertFalse(view.joinable());
            assertTrue(view.allowedActions().contains("SUBMIT_NAME"));
            assertFalse(view.game().currentPlayerSubmitted());
            assertEquals(0, view.game().submittedCount());
            assertTrue(view.game().cards().isEmpty()); assertTrue(view.game().reveal().isEmpty()); assertNull(view.game().roast());
            assertFalse(wire(view).contains("SECRET-FROM"));
        }
    }
    @Test void duplicateNamesAndKickedCreatorRevealCorrectOriginalAttribution() {
        reveal(); run("GM_PLAY_AGAIN");
        service.submitName(room.id(), room.owner(), session(), "Doraemon");
        service.submitName(room.id(), token.identity(), session(), "Doraemon");
        service.shuffle(room.id(), room.owner(), session()); reveal();
        var cards = gm.client.state().game().reveal();
        assertEquals("Ken", cards.stream().filter(c -> c.playerId().equals(room.gmPlayerId())).findFirst().orElseThrow().createdBy());
        assertEquals("GM", cards.stream().filter(c -> c.playerId().equals(player())).findFirst().orElseThrow().createdBy());
        service.kickPlayer(room.id(), room.owner(), player());
        assertEquals("PLAYER_KICKED", guest.client.terminal().reason());
        var remaining = gm.client.state().game().reveal();
        assertEquals(1, remaining.size()); assertEquals("Ken", remaining.getFirst().createdBy());
        assertEquals("Doraemon", remaining.getFirst().assignedName());
        assertFalse(gm.client.state().allowedActions().contains("GM_PLAY_AGAIN"));
    }
    @ParameterizedTest @ValueSource(strings = {"ROAST", "REVEAL"})
    void reconnectRestoresWaitingPhaseAndGmActions(String phase) {
        if (phase.equals("ROAST")) service.markGotIt(room.id(), room.owner(), session(), player());
        run("GM_END_GAME");
        var before = state(); realtime.disconnected(gm.connection);
        assertEquals(phase, guest.client.state().game().phase());
        assertEquals(List.of("GET_STATE"), guest.client.state().allowedActions());
        assertEquals("DISCONNECTED", guest.client.state().players().getFirst().connectionStatus());
        var restored = peer(room.owner());
        send(restored, new RoomProtocol.Command("RESUME_ROOM", id(), room.id(), null, null, null));
        assertSame(before, state());
        assertTrue(restored.client.state().allowedActions().contains(phase.equals("ROAST") ? "GM_CONTINUE_REVEAL" : "GM_PLAY_AGAIN"));
        var returned = peer(null);
        send(returned, new RoomProtocol.Command("RESUME_ROOM", id(), room.id(), null, token.value(), null));
        assertEquals(phase, returned.client.state().game().phase()); safe(returned.client.state());
        if (phase.equals("ROAST")) assertFalse(wire(returned.client.state()).contains("SECRET-FROM"));
    }
    @ParameterizedTest @ValueSource(strings = {"LOBBY", "SUBMIT_NAME", "PLAYING", "ROAST", "REVEAL"})
    void closeRoomSendsTerminalAndCannotBeRejoined(String phase) {
        if (!phase.equals("PLAYING")) {
            if (phase.equals("ROAST")) service.markGotIt(room.id(), room.owner(), session(), player());
            run("GM_END_GAME");
            if (!phase.equals("ROAST")) {
                if (phase.equals("LOBBY")) run("GM_BACK_TO_ROOM");
                if (phase.equals("SUBMIT_NAME")) run("GM_PLAY_AGAIN");
            }
        }
        send(gm, command("GM_CLOSE_ROOM"));
        assertTrue(store.find(room.id()).isEmpty());
        for (var p : List.of(gm, guest)) {
            assertEquals("ROOM_ENDED", p.client.terminal().type());
            assertEquals("CLOSED", p.client.terminal().reason()); assertFalse(p.connection.attached());
        }
        var returned = peer(null);
        send(returned, new RoomProtocol.Command("RESUME_ROOM", id(), room.id(), null, token.value(), null));
        assertEquals("ROOM_NOT_FOUND", returned.client.result().code());
    }
    @Test void changeGameRemainsUnavailableInV1() {
        reveal(); var before = current();
        assertFalse(gm.client.state().allowedActions().contains("GM_CHANGE_GAME"));
        send(gm, command("GM_CHANGE_GAME"));
        assertEquals("INVALID_INPUT", gm.client.result().code()); assertSame(before, current());
        assertEquals(List.of(GameType.WHO_AM_I), List.of(GameType.values()));
    }
}
