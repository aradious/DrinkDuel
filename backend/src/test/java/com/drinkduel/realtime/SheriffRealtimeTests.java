package com.drinkduel.realtime;

import com.drinkduel.game.AuthoritativeDiceRoller;
import com.drinkduel.game.SheriffRole;
import com.drinkduel.game.SheriffRoleShuffler;
import com.drinkduel.room.AvatarCatalog;
import com.drinkduel.room.ConnectionState;
import com.drinkduel.room.GuestToken;
import com.drinkduel.room.InMemoryRoomStore;
import com.drinkduel.room.PlayerIdentity;
import com.drinkduel.room.Room;
import com.drinkduel.room.RoomService;
import com.drinkduel.room.RoomStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SheriffRealtimeTests {
    private RoomStore store;
    private RoomService service;
    private RoomRealtime realtime;
    private Room room;
    private CountingIdentityShuffler shuffler;
    private Peer gm;
    private Peer a;
    private Peer b;
    private Peer c;
    private Peer d;
    private Peer excluded;
    private GuestToken aToken;
    private GuestToken excludedToken;

    record Peer(RoomRealtime.Connection connection, RoomRealtimeTests.Client client) {}

    @BeforeEach void setup() {
        var avatars = new AvatarCatalog();
        store = new InMemoryRoomStore(Clock.systemUTC(), avatars);
        shuffler = new CountingIdentityShuffler();
        service = new RoomService(store, avatars, new AuthoritativeDiceRoller(() -> 2), shuffler);
        realtime = new RoomRealtime(store, service, Clock.systemUTC());
        room = service.createRoom(new PlayerIdentity.Google("sheriff-realtime-owner"), "GM");
        gm = peer(room.owner());
        send(gm, resumeOwner());
        aToken = GuestToken.generate();
        a = join(aToken, "A");
        b = join(GuestToken.generate(), "B");
        c = join(GuestToken.generate(), "C");
        d = join(GuestToken.generate(), "D");
        excludedToken = GuestToken.generate();
        excluded = join(excludedToken, "Excluded");
    }

    @Test void onlyGmCanSelectSheriffAndStartActionsAreAuthoritative() {
        assertTrue(gm.client.state().allowedActions().contains("GM_SELECT_SHERIFF"));
        assertFalse(a.client.state().allowedActions().contains("GM_SELECT_SHERIFF"));

        send(a, command("GM_SELECT_SHERIFF", null, null, null));
        assertEquals("NOT_AUTHORIZED", a.client.result().code());
        UUID sessionId = select();

        assertEquals("START", gm.client.state().sheriff().phase());
        assertNull(gm.client.state().game());
        assertNull(gm.client.state().liarsDice());
        assertEquals(List.of("GET_STATE", "GM_KICK_PLAYER", "GM_BACK_TO_ROOM",
                "GM_START_SHERIFF", "GM_CLOSE_ROOM"), gm.client.state().allowedActions());
        assertEquals(List.of("GET_STATE"), a.client.state().allowedActions());
        assertTrue(gm.client.state().sheriff().participants().isEmpty());
        assertNull(gm.client.state().sheriff().sheriffPlayerId());
        assertNull(gm.client.state().sheriff().ownRole());
        assertTrue(gm.client.state().sheriff().publicRoles().isEmpty());
        assertTrue(gm.client.state().sheriff().roleStatus().isEmpty());
        assertNull(gm.client.state().sheriff().overallStatus());
        assertEquals(sessionId, gm.client.state().sessionId());
    }

    @Test void playingSnapshotsExposeOnlyOwnRoleAndPublicSheriff() {
        UUID sessionId = selectAndStartFive();
        var peers = List.of(gm, a, b, c, d);
        var expectedRoles = List.of("SHERIFF", "DEPUTY", "OUTLAW", "OUTLAW", "RENEGADE");
        UUID sheriffId = gm.client.state().currentPlayerId();

        for (int index = 0; index < peers.size(); index++) {
            var snapshot = peers.get(index).client.state();
            var view = snapshot.sheriff();
            assertEquals("PLAYING", view.phase());
            assertEquals(sheriffId, view.sheriffPlayerId());
            assertEquals(expectedRoles.get(index), view.ownRole());
            assertEquals(5, view.participants().size());
            assertTrue(view.participants().stream().allMatch(p -> p.lifeStatus().equals("ALIVE")));
            assertEquals(List.of(new RoomProtocol.SheriffPublicRoleView(sheriffId, "SHERIFF")),
                    view.publicRoles());
            assertFalse(snapshot.allowedActions().contains("GM_MARK_SHERIFF_DEAD")
                    && !snapshot.isGm());
        }

        for (UUID secretPlayer : List.of(a.client.state().currentPlayerId(),
                b.client.state().currentPlayerId(), c.client.state().currentPlayerId(),
                d.client.state().currentPlayerId())) {
            for (Peer peer : peers) {
                assertFalse(peer.client.state().sheriff().publicRoles().stream()
                        .anyMatch(publicRole -> publicRole.playerId().equals(secretPlayer)));
            }
        }
        assertTrue(gm.client.state().allowedActions().containsAll(
                List.of("GM_MARK_SHERIFF_DEAD", "GM_END_SHERIFF")));
        assertFalse(a.client.state().allowedActions().contains("GM_MARK_SHERIFF_DEAD"));
        assertEquals(1, shuffler.calls());
        assertEquals(sessionId, current().currentSession().orElseThrow().id());
    }

    @Test void roleStatusIsIdenticalPublicAndMaintainsEveryInvariant() {
        selectAndStartFive();
        var expected = List.of(
                new RoomProtocol.SheriffRoleStatusView("SHERIFF", 1, 1, 0),
                new RoomProtocol.SheriffRoleStatusView("DEPUTY", 1, 1, 0),
                new RoomProtocol.SheriffRoleStatusView("OUTLAW", 2, 2, 0),
                new RoomProtocol.SheriffRoleStatusView("RENEGADE", 1, 1, 0));

        for (Peer peer : List.of(gm, a, b, c, d)) {
            var view = peer.client.state().sheriff();
            assertEquals(expected, view.roleStatus());
            assertEquals(new RoomProtocol.SheriffOverallStatusView(5, 5, 0), view.overallStatus());
            assertCountInvariants(view);
        }
    }

    @Test void excludedReconnectGetsPublicStateWithoutAPrivateRole() {
        selectAndStartFive();

        excluded = peer(null);
        send(excluded, resume(excludedToken));
        var view = excluded.client.state().sheriff();

        assertEquals("PLAYING", view.phase());
        assertNull(view.ownRole());
        assertFalse(view.participants().stream()
                .anyMatch(participant -> participant.playerId().equals(excluded.client.state().currentPlayerId())));
        assertEquals(1, view.publicRoles().size());
        assertEquals("SHERIFF", view.publicRoles().getFirst().role());
        assertEquals(new RoomProtocol.SheriffOverallStatusView(5, 5, 0), view.overallStatus());
    }

    @Test void playerCannotForgeDeathEndOrRestartCommands() {
        UUID sessionId = selectAndStartFive();
        UUID target = b.client.state().currentPlayerId();
        long revision = current().revision();

        send(a, command("GM_MARK_SHERIFF_DEAD", sessionId, target, null));
        assertEquals("NOT_AUTHORIZED", a.client.result().code());
        send(a, command("GM_END_SHERIFF", sessionId, null, null));
        assertEquals("NOT_AUTHORIZED", a.client.result().code());
        assertEquals(revision, current().revision());

        send(gm, command("GM_END_SHERIFF", sessionId, null, null));
        send(a, command("GM_RESTART_SHERIFF", sessionId, null, null));
        assertEquals("NOT_AUTHORIZED", a.client.result().code());
        assertEquals("REVEAL", a.client.state().sheriff().phase());
    }

    @Test void deathAtomicallyBroadcastsStatusPublicRoleAndCounts() {
        UUID sessionId = selectAndStartFive();
        UUID target = a.client.state().currentPlayerId();
        clearMessages();

        send(gm, command("GM_MARK_SHERIFF_DEAD", sessionId, target, null));

        for (Peer peer : List.of(gm, a, b, c, d)) {
            var view = peer.client.state().sheriff();
            assertTrue(view.participants().stream().anyMatch(participant ->
                    participant.playerId().equals(target) && participant.lifeStatus().equals("DEAD")));
            assertTrue(view.publicRoles().contains(
                    new RoomProtocol.SheriffPublicRoleView(target, "DEPUTY")));
            assertEquals(new RoomProtocol.SheriffOverallStatusView(5, 4, 1), view.overallStatus());
            var deputy = roleStatus(view, "DEPUTY");
            assertEquals(new RoomProtocol.SheriffRoleStatusView("DEPUTY", 1, 0, 1), deputy);
            assertCountInvariants(view);
        }
    }

    @Test void duplicateDeathNeverChangesRevisionCountsOrBroadcastsAgain() {
        UUID sessionId = selectAndStartFive();
        UUID target = a.client.state().currentPlayerId();
        var death = command("GM_MARK_SHERIFF_DEAD", sessionId, target, id());
        send(gm, death);
        long revision = current().revision();
        int playerStates = b.client.states().size();

        send(gm, death);
        assertTrue(gm.client.result().accepted());
        assertEquals(revision, current().revision());
        assertEquals(playerStates, b.client.states().size());

        send(gm, command("GM_MARK_SHERIFF_DEAD", sessionId, target, null));
        assertTrue(gm.client.result().accepted());
        assertEquals(revision, current().revision());
        assertEquals(playerStates, b.client.states().size());
        assertEquals(new RoomProtocol.SheriffOverallStatusView(5, 4, 1),
                b.client.state().sheriff().overallStatus());
    }

    @Test void revealBroadcastsEveryRoleAndRestartClearsAllRoundSecrets() {
        UUID oldSession = selectAndStartFive();
        send(gm, command("GM_END_SHERIFF", oldSession, null, null));

        for (Peer peer : List.of(gm, a, b, c, d)) {
            var view = peer.client.state().sheriff();
            assertEquals("REVEAL", view.phase());
            assertEquals(5, view.publicRoles().size());
            assertEquals(List.of("SHERIFF", "DEPUTY", "OUTLAW", "OUTLAW", "RENEGADE"),
                    view.publicRoles().stream().map(RoomProtocol.SheriffPublicRoleView::role).toList());
        }
        assertTrue(gm.client.state().allowedActions().containsAll(
                List.of("GM_RESTART_SHERIFF", "GM_BACK_TO_ROOM")));

        send(gm, command("GM_RESTART_SHERIFF", oldSession, null, null));
        UUID nextSession = gm.client.state().sessionId();
        var start = gm.client.state().sheriff();
        assertNotEquals(oldSession, nextSession);
        assertEquals("START", start.phase());
        assertNull(start.ownRole());
        assertNull(start.sheriffPlayerId());
        assertTrue(start.participants().isEmpty());
        assertTrue(start.publicRoles().isEmpty());
        assertTrue(start.roleStatus().isEmpty());
        assertNull(start.overallStatus());
    }

    @Test void staleAndInvalidPhaseCommandsFailWithoutSecretData() {
        UUID sessionId = select();
        send(gm, command("GM_START_SHERIFF", UUID.randomUUID(), null, null));
        assertEquals("STALE_COMMAND", gm.client.result().code());
        assertEquals(0, shuffler.calls());

        send(gm, command("GM_MARK_SHERIFF_DEAD", sessionId,
                a.client.state().currentPlayerId(), null));
        assertEquals("INVALID_GAME_PHASE", gm.client.result().code());
        assertNull(gm.client.result().roomRevision());
        assertEquals("START", gm.client.state().sheriff().phase());

        selectCannotReplaceActiveSession();
        assertFalse(gm.client.result().accepted());
        assertEquals("GAME_IN_PROGRESS", gm.client.result().code());
        assertFalse(gm.client.result().toString().contains("SHERIFF"));
        assertFalse(gm.client.result().toString().contains("DEPUTY"));
    }

    @Test void getStateAndReconnectPreserveOwnRoleAndPublicDeath() {
        UUID sessionId = selectAndStartFive();
        UUID playerId = a.client.state().currentPlayerId();
        send(gm, command("GM_MARK_SHERIFF_DEAD", sessionId, playerId, null));
        int shuffles = shuffler.calls();

        a.client.messages.clear();
        send(a, command("GET_STATE", null, null, null));
        assertEquals("DEPUTY", a.client.state().sheriff().ownRole());
        assertTrue(a.client.state().sheriff().publicRoles().contains(
                new RoomProtocol.SheriffPublicRoleView(playerId, "DEPUTY")));

        realtime.disconnected(a.connection());
        var returned = peer(null);
        send(returned, resume(aToken));
        assertEquals("DEPUTY", returned.client.state().sheriff().ownRole());
        assertTrue(returned.client.state().sheriff().publicRoles().contains(
                new RoomProtocol.SheriffPublicRoleView(playerId, "DEPUTY")));
        assertEquals("DEAD", returned.client.state().sheriff().participants().stream()
                .filter(participant -> participant.playerId().equals(playerId))
                .findFirst().orElseThrow().lifeStatus());
        assertEquals(shuffles, shuffler.calls());
    }

    @Test void gmDisconnectRemovesSharedActionsAndReconnectRestoresThem() {
        selectAndStartFive();
        realtime.disconnected(gm.connection());

        assertEquals(ConnectionState.DISCONNECTED.name(), a.client.state().players().stream()
                .filter(player -> player.playerId().equals(room.gmPlayerId()))
                .findFirst().orElseThrow().connectionStatus());
        assertEquals(List.of("GET_STATE"), a.client.state().allowedActions());

        var returned = peer(room.owner());
        send(returned, resumeOwner());
        assertEquals("PLAYING", returned.client.state().sheriff().phase());
        assertEquals("SHERIFF", returned.client.state().sheriff().ownRole());
        assertTrue(returned.client.state().allowedActions().containsAll(
                List.of("GM_MARK_SHERIFF_DEAD", "GM_END_SHERIFF", "GM_CLOSE_ROOM")));
        assertEquals(1, shuffler.calls());
    }

    @Test void backToRoomBroadcastsAuthoritativeLobbyFromStartAndReveal() {
        UUID startSession = select();
        send(gm, command("GM_BACK_TO_ROOM", startSession, null, null));
        assertLobbyForEveryone();

        UUID playingSession = selectAndStartFive();
        send(gm, command("GM_BACK_TO_ROOM", playingSession, null, null));
        assertEquals("INVALID_GAME_PHASE", gm.client.result().code());
        send(gm, command("GM_END_SHERIFF", playingSession, null, null));
        send(gm, command("GM_BACK_TO_ROOM", playingSession, null, null));
        assertLobbyForEveryone();
    }

    @Test void serializedPlayingStateHasNoCompleteAssignmentMapOrDebugLeak() {
        selectAndStartFive();
        UUID deputy = a.client.state().currentPlayerId();
        UUID outlaw = b.client.state().currentPlayerId();
        UUID renegade = d.client.state().currentPlayerId();
        String gmJson = json(gm.client.states().getLast());

        assertFalse(gm.client.state().sheriff().publicRoles().stream()
                .anyMatch(role -> role.playerId().equals(deputy)
                        || role.playerId().equals(outlaw) || role.playerId().equals(renegade)));
        assertFalse(gmJson.contains("\"playerId\":\"" + deputy + "\",\"role\":\"DEPUTY\""));
        assertFalse(gmJson.contains("\"playerId\":\"" + outlaw + "\",\"role\":\"OUTLAW\""));
        assertFalse(gmJson.contains("\"playerId\":\"" + renegade + "\",\"role\":\"RENEGADE\""));
        assertFalse(gmJson.contains("assignments"));
        assertFalse(gmJson.contains("playerStatuses"));
        assertFalse(gm.client.states().getLast().toString().contains("DEPUTY"));
        assertFalse(gm.client.states().getLast().toString().contains("RENEGADE"));
    }

    private UUID select() {
        send(gm, command("GM_SELECT_SHERIFF", null, null, null));
        assertTrue(gm.client.result().accepted());
        return gm.client.state().sessionId();
    }

    private UUID selectAndStartFive() {
        realtime.disconnected(excluded.connection());
        UUID sessionId = select();
        send(gm, command("GM_START_SHERIFF", sessionId, null, null));
        assertTrue(gm.client.result().accepted());
        return sessionId;
    }

    private void selectCannotReplaceActiveSession() {
        send(gm, command("GM_SELECT_SHERIFF", null, null, null));
    }

    private void assertLobbyForEveryone() {
        for (Peer peer : List.of(gm, a, b, c, d)) {
            assertEquals("LOBBY", peer.client.state().lifecycle());
            assertNull(peer.client.state().sessionId());
            assertNull(peer.client.state().sheriff());
            assertTrue(peer.client.state().joinable());
        }
    }

    private static void assertCountInvariants(RoomProtocol.SheriffView view) {
        assertEquals(view.overallStatus().total(),
                view.roleStatus().stream().mapToInt(RoomProtocol.SheriffRoleStatusView::assigned).sum());
        assertEquals(view.overallStatus().alive(),
                view.roleStatus().stream().mapToInt(RoomProtocol.SheriffRoleStatusView::alive).sum());
        assertEquals(view.overallStatus().dead(),
                view.roleStatus().stream().mapToInt(RoomProtocol.SheriffRoleStatusView::dead).sum());
        assertEquals(view.overallStatus().total(),
                view.overallStatus().alive() + view.overallStatus().dead());
        view.roleStatus().forEach(status -> assertEquals(status.assigned(), status.alive() + status.dead()));
    }

    private static RoomProtocol.SheriffRoleStatusView roleStatus(RoomProtocol.SheriffView view,
                                                                  String role) {
        return view.roleStatus().stream().filter(status -> status.role().equals(role))
                .findFirst().orElseThrow();
    }

    private Peer join(GuestToken token, String nickname) {
        Peer peer = peer(null);
        send(peer, new RoomProtocol.Command("JOIN_ROOM", id(), room.id(), nickname,
                token.value(), null));
        return peer;
    }

    private Peer peer(PlayerIdentity.Owner owner) {
        var client = new RoomRealtimeTests.Client();
        return new Peer(realtime.open(client, owner), client);
    }

    private RoomProtocol.Command resume(GuestToken token) {
        return new RoomProtocol.Command("RESUME_ROOM", id(), room.id(), null, token.value(), null);
    }

    private RoomProtocol.Command resumeOwner() {
        return new RoomProtocol.Command("RESUME_ROOM", id(), room.id(), null, null, null);
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

    private void clearMessages() {
        for (Peer peer : List.of(gm, a, b, c, d)) peer.client.messages.clear();
    }

    private static final class CountingIdentityShuffler implements SheriffRoleShuffler {
        private final AtomicInteger calls = new AtomicInteger();
        @Override public List<SheriffRole> shuffle(List<SheriffRole> roles) {
            calls.incrementAndGet();
            return List.copyOf(roles);
        }
        int calls() { return calls.get(); }
    }
}
