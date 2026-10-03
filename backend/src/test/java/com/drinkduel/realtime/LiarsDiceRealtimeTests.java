package com.drinkduel.realtime;

import com.drinkduel.game.AuthoritativeDiceRoller;
import com.drinkduel.game.DiceRandomSource;
import com.drinkduel.game.LiarsDiceState;
import com.drinkduel.room.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LiarsDiceRealtimeTests {
    private static final List<Integer> GM_HAND = List.of(1, 1, 2, 3, 4);
    private static final List<Integer> A_HAND = List.of(2, 2, 3, 5, 6);
    private static final List<Integer> B_HAND = List.of(3, 3, 4, 5, 6);

    private RoomStore store;
    private RoomService service;
    private RoomRealtime realtime;
    private Room room;
    private SequenceRandom random;
    private Peer gm;
    private Peer a;
    private Peer b;
    private Peer excluded;
    private GuestToken aToken;
    private GuestToken bToken;
    private GuestToken excludedToken;

    record Peer(RoomRealtime.Connection connection, RoomRealtimeTests.Client client) {}

    @BeforeEach void setup() {
        var avatars = new AvatarCatalog();
        store = new InMemoryRoomStore(Clock.systemUTC(), avatars);
        random = new SequenceRandom(1, 1, 2, 3, 4, 2, 2, 3, 5, 6, 3, 3, 4, 5, 6);
        service = new RoomService(store, avatars, new AuthoritativeDiceRoller(random));
        realtime = new RoomRealtime(store, service, Clock.systemUTC());
        room = service.createRoom(new PlayerIdentity.Google("liars-realtime-owner"), "GM");
        gm = peer(room.owner());
        send(gm, command("RESUME_ROOM", null, null, null));
        aToken = GuestToken.generate();
        bToken = GuestToken.generate();
        excludedToken = GuestToken.generate();
        a = join(aToken, "A");
        b = join(bToken, "B");
        excluded = join(excludedToken, "Excluded");
    }

    @Test void gmSelectsAndOnlyGmMayUseLiarsDiceCommands() {
        assertTrue(gm.client.state().allowedActions().contains("GM_SELECT_LIARS_DICE"));
        assertFalse(a.client.state().allowedActions().contains("GM_SELECT_LIARS_DICE"));

        send(a, command("GM_SELECT_LIARS_DICE", null, null, null));
        assertEquals("NOT_AUTHORIZED", a.client.result().code());
        select();

        assertEquals("START", gm.client.state().liarsDice().phase());
        assertNull(gm.client.state().game());
        assertEquals(List.of("GET_STATE", "GM_KICK_PLAYER", "GM_BACK_TO_ROOM",
                "GM_START_LIARS_DICE", "GM_CLOSE_ROOM"), gm.client.state().allowedActions());
        assertEquals(List.of("GET_STATE"), a.client.state().allowedActions());
        assertTrue(gm.client.state().liarsDice().ownDice().isEmpty());
        assertTrue(gm.client.state().liarsDice().revealedHands().isEmpty());
        assertTrue(gm.client.state().liarsDice().revealCounts().isEmpty());
    }

    @Test void startRequiresTwoConnectedMembersAndFailureDoesNotRoll() {
        realtime.disconnected(a.connection());
        realtime.disconnected(b.connection());
        realtime.disconnected(excluded.connection());
        UUID session = select();

        send(gm, command("GM_START_LIARS_DICE", session, null, null));

        assertEquals("NOT_ENOUGH_PLAYERS", gm.client.result().code());
        assertEquals(0, random.calls());
        assertEquals("START", gm.client.state().liarsDice().phase());
    }

    @Test void guestCannotStartEndOrRestartThroughRealtimeCommands() {
        realtime.disconnected(excluded.connection());
        UUID session = select();

        send(a, command("GM_START_LIARS_DICE", session, null, null));
        assertEquals("NOT_AUTHORIZED", a.client.result().code());
        send(gm, command("GM_START_LIARS_DICE", session, null, null));

        send(a, command("GM_END_LIARS_DICE", session, null, null));
        assertEquals("NOT_AUTHORIZED", a.client.result().code());
        send(gm, command("GM_END_LIARS_DICE", session, null, null));

        send(a, command("GM_RESTART_LIARS_DICE", session, null, null));
        assertEquals("NOT_AUTHORIZED", a.client.result().code());
        assertEquals("REVEAL", a.client.state().liarsDice().phase());
    }

    @Test void playingPayloadIsPersonalizedForGmPlayersAndExcludedMember() {
        realtime.disconnected(excluded.connection());
        UUID session = select();
        clearMessages();
        send(gm, command("GM_START_LIARS_DICE", session, null, null));

        assertPlayingSecret(gm, GM_HAND, A_HAND, B_HAND);
        assertPlayingSecret(a, A_HAND, GM_HAND, B_HAND);
        assertPlayingSecret(b, B_HAND, GM_HAND, A_HAND);
        assertTrue(gm.client.state().allowedActions().contains("GM_END_LIARS_DICE"));
        assertFalse(a.client.state().allowedActions().contains("GM_END_LIARS_DICE"));

        excluded = peer(null);
        send(excluded, resume(excludedToken));
        var view = excluded.client.state().liarsDice();
        assertEquals("PLAYING", view.phase());
        assertTrue(view.ownDice().isEmpty());
        assertTrue(view.revealedHands().isEmpty());
        assertTrue(view.revealCounts().isEmpty());
        String json = json(excluded.client.state());
        assertFalse(json.contains(array(GM_HAND)));
        assertFalse(json.contains(array(A_HAND)));
        assertFalse(json.contains(array(B_HAND)));
    }

    @Test void getStateAndReconnectReturnSameOwnHandWithoutLeakOrReroll() {
        realtime.disconnected(excluded.connection());
        UUID session = select();
        send(gm, command("GM_START_LIARS_DICE", session, null, null));
        int calls = random.calls();

        a.client.messages.clear();
        send(a, command("GET_STATE", null, null, null));
        assertPlayingSecret(a, A_HAND, GM_HAND, B_HAND);
        assertEquals(calls, random.calls());

        realtime.disconnected(a.connection());
        var returned = peer(null);
        send(returned, resume(aToken));
        assertPlayingSecret(returned, A_HAND, GM_HAND, B_HAND);
        assertEquals(calls, random.calls());
        assertEquals(3, returned.client.state().liarsDice().participantIds().size());
    }

    @Test void duplicateAndReplayedStartCannotReroll() {
        realtime.disconnected(excluded.connection());
        UUID session = select();
        var start = command("GM_START_LIARS_DICE", session, null, null);
        send(gm, start);
        int calls = random.calls();
        long revision = current().revision();

        send(gm, start);
        assertTrue(gm.client.result().accepted());
        assertEquals(calls, random.calls());
        assertEquals(revision, current().revision());

        send(gm, command("GM_START_LIARS_DICE", session, null, null));
        assertEquals("INVALID_GAME_PHASE", gm.client.result().code());
        assertEquals(calls, random.calls());
    }

    @Test void staleAndCrossGameCommandsFailSafely() {
        UUID session = select();
        send(gm, command("GM_START_LIARS_DICE", UUID.randomUUID(), null, null));
        assertEquals("STALE_COMMAND", gm.client.result().code());
        assertEquals(0, random.calls());

        send(gm, command("GM_END_GAME", session, null, null));
        assertEquals("INVALID_GAME_PHASE", gm.client.result().code());

        setup();
        send(gm, command("GM_START_GAME", null, null, null));
        UUID whoSession = current().currentSession().orElseThrow().id();
        send(gm, command("GM_START_LIARS_DICE", whoSession, null, null));
        assertEquals("INVALID_GAME_PHASE", gm.client.result().code());
    }

    @Test void kickWorksOnlyInStart() {
        UUID session = select();
        send(gm, command("GM_KICK_PLAYER", session, a.client.state().currentPlayerId(), null));
        assertTrue(gm.client.result().accepted());
        assertEquals("PLAYER_KICKED", a.client.terminal().reason());

        send(gm, command("GM_START_LIARS_DICE", session, null, null));
        Room before = current();
        send(gm, command("GM_KICK_PLAYER", session, b.client.state().currentPlayerId(), null));
        assertEquals("INVALID_GAME_PHASE", gm.client.result().code());
        assertSame(before, current());

        send(gm, command("GM_END_LIARS_DICE", session, null, null));
        before = current();
        send(gm, command("GM_KICK_PLAYER", session, b.client.state().currentPlayerId(), null));
        assertEquals("INVALID_GAME_PHASE", gm.client.result().code());
        assertSame(before, current());
    }

    @Test void gmBackToRoomFromStartReturnsEveryClientToJoinableLobby() {
        UUID session = select();
        assertFalse(a.client.state().allowedActions().contains("GM_BACK_TO_ROOM"));

        send(gm, command("GM_BACK_TO_ROOM", session, null, null));

        assertTrue(gm.client.result().accepted());
        for (Peer peer : List.of(gm, a, b, excluded)) {
            assertEquals("LOBBY", peer.client.state().lifecycle());
            assertNull(peer.client.state().sessionId());
            assertNull(peer.client.state().liarsDice());
            assertTrue(peer.client.state().joinable());
        }
    }

    @Test void endRevealsEveryHandAndCorrectCountsToEveryClient() {
        realtime.disconnected(excluded.connection());
        UUID session = select();
        send(gm, command("GM_START_LIARS_DICE", session, null, null));
        send(gm, command("GM_END_LIARS_DICE", session, null, null));

        for (Peer peer : List.of(gm, a, b)) {
            var view = peer.client.state().liarsDice();
            assertEquals("REVEAL", view.phase());
            assertTrue(view.ownDice().isEmpty());
            assertEquals(List.of(GM_HAND, A_HAND, B_HAND),
                    view.revealedHands().stream().map(RoomProtocol.LiarsDiceHandView::dice).toList());
            assertEquals(List.of(2, 3, 4, 2, 2, 2),
                    view.revealCounts().stream().map(RoomProtocol.LiarsDiceFaceCountView::rawCount).toList());
            assertEquals(List.of(2, 5, 6, 4, 4, 4),
                    view.revealCounts().stream().map(RoomProtocol.LiarsDiceFaceCountView::effectiveCount).toList());
            String wire = json(peer.client.state());
            assertTrue(wire.contains(array(GM_HAND)));
            assertTrue(wire.contains(array(A_HAND)));
            assertTrue(wire.contains(array(B_HAND)));
            assertTrue(wire.contains("\"rawCount\""));
            assertTrue(wire.contains("\"effectiveCount\""));
        }
        assertTrue(gm.client.state().allowedActions().contains("GM_RESTART_LIARS_DICE"));
        assertFalse(a.client.state().allowedActions().contains("GM_RESTART_LIARS_DICE"));
    }

    @Test void restartCreatesNewCleanSessionAndOldCommandsBecomeStale() {
        realtime.disconnected(excluded.connection());
        UUID oldSession = select();
        send(gm, command("GM_START_LIARS_DICE", oldSession, null, null));
        send(gm, command("GM_END_LIARS_DICE", oldSession, null, null));
        int calls = random.calls();

        send(gm, command("GM_RESTART_LIARS_DICE", oldSession, null, null));
        UUID newSession = gm.client.state().sessionId();
        var view = gm.client.state().liarsDice();

        assertNotEquals(oldSession, newSession);
        assertEquals("START", view.phase());
        assertTrue(view.participantIds().isEmpty());
        assertTrue(view.ownDice().isEmpty());
        assertTrue(view.revealedHands().isEmpty());
        assertTrue(view.revealCounts().isEmpty());
        assertEquals(calls, random.calls());

        send(gm, command("GM_START_LIARS_DICE", oldSession, null, null));
        assertEquals("STALE_COMMAND", gm.client.result().code());
        assertEquals(calls, random.calls());
    }

    @Test void protocolDoesNotAdvertiseUnsupportedGameActionsOrLeakThroughToString() {
        realtime.disconnected(excluded.connection());
        UUID session = select();
        send(gm, command("GM_START_LIARS_DICE", session, null, null));
        String actions = String.join(" ", gm.client.state().allowedActions());
        for (String forbidden : List.of("BID", "CHALLENGE", "TURN", "SCORE", "WINNER", "GIVE_UP", "GAVE_UP"))
            assertFalse(actions.contains(forbidden));
        assertFalse(gm.client.state().liarsDice().toString().contains(GM_HAND.toString()));
    }

    private void assertPlayingSecret(Peer peer, List<Integer> own, List<Integer> firstOther,
                                     List<Integer> secondOther) {
        var view = peer.client.state().liarsDice();
        assertEquals("PLAYING", view.phase());
        assertEquals(own, view.ownDice());
        assertTrue(view.revealedHands().isEmpty());
        assertTrue(view.revealCounts().isEmpty());
        String wire = json(peer.client.state());
        assertTrue(wire.contains(array(own)));
        assertFalse(wire.contains(array(firstOther)));
        assertFalse(wire.contains(array(secondOther)));
        assertFalse(wire.contains("\"rawCount\""));
        assertFalse(wire.contains("\"effectiveCount\""));
        assertFalse(wire.contains("\"revealedHands\":[{"));
    }

    private UUID select() {
        send(gm, command("GM_SELECT_LIARS_DICE", null, null, null));
        assertTrue(gm.client.result().accepted());
        return gm.client.state().sessionId();
    }

    private Peer join(GuestToken token, String nickname) {
        Peer peer = peer(null);
        send(peer, new RoomProtocol.Command("JOIN_ROOM", id(), room.id(), nickname, token.value(), null));
        return peer;
    }

    private Peer peer(PlayerIdentity.Owner owner) {
        var client = new RoomRealtimeTests.Client();
        return new Peer(realtime.open(client, owner), client);
    }

    private RoomProtocol.Command resume(GuestToken token) {
        return new RoomProtocol.Command("RESUME_ROOM", id(), room.id(), null, token.value(), null);
    }

    private RoomProtocol.Command command(String type, UUID session, UUID target, String requestId) {
        return new RoomProtocol.Command(type, requestId == null ? id() : requestId, room.id(), null, null,
                target == null ? null : target.toString(), session == null ? null : session.toString(), null, null);
    }

    private void send(Peer peer, RoomProtocol.Command command) {
        realtime.command(peer.connection(), command);
    }

    private Room current() { return store.find(room.id()).orElseThrow(); }
    private String id() { return UUID.randomUUID().toString(); }
    private String json(Object value) { return new JsonMapper().writeValueAsString(value); }
    private String array(List<Integer> values) { return values.toString().replace(" ", ""); }

    private void clearMessages() {
        gm.client.messages.clear();
        a.client.messages.clear();
        b.client.messages.clear();
    }

    private static final class SequenceRandom implements DiceRandomSource {
        private final ArrayDeque<Integer> values;
        private final AtomicInteger calls = new AtomicInteger();
        private SequenceRandom(Integer... values) { this.values = new ArrayDeque<>(List.of(values)); }
        @Override public synchronized int nextDieValue() { calls.incrementAndGet(); return values.removeFirst(); }
        int calls() { return calls.get(); }
    }
}
