package com.drinkduel.room;

import com.drinkduel.game.AuthoritativeDiceRoller;
import com.drinkduel.game.GameSession;
import com.drinkduel.game.GameType;
import com.drinkduel.game.SheriffPlayerStatus;
import com.drinkduel.game.SheriffRole;
import com.drinkduel.game.SheriffRoleShuffler;
import com.drinkduel.game.SheriffState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.drinkduel.room.DomainException.Code.GAME_IN_PROGRESS;
import static com.drinkduel.room.DomainException.Code.INVALID_GAME_PHASE;
import static com.drinkduel.room.DomainException.Code.NOT_AUTHORIZED;
import static com.drinkduel.room.DomainException.Code.NOT_ENOUGH_PLAYERS;
import static com.drinkduel.room.DomainException.Code.PLAYER_NOT_IN_GAME;
import static com.drinkduel.room.DomainException.Code.STALE_COMMAND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SheriffServiceTests {
    private AvatarCatalog avatars;
    private RoomStore store;
    private AlternatingShuffler shuffler;
    private RoomService service;
    private Room room;
    private List<GuestToken> guests;

    @BeforeEach void setup() {
        avatars = new AvatarCatalog();
        store = new InMemoryRoomStore(Clock.systemUTC(), avatars);
        shuffler = new AlternatingShuffler();
        service = new RoomService(store, avatars, new AuthoritativeDiceRoller(() -> 2), shuffler);
        room = service.createRoom(new PlayerIdentity.Google("sheriff-owner"), "GM");
        guests = new ArrayList<>();
        addGuests(3);
    }

    @Test void gmSelectsSheriffWithoutAssigningRoles() {
        Room selected = service.selectSheriff(room.id(), room.owner());
        GameSession session = selected.currentSession().orElseThrow();
        var state = (SheriffState) session.state();

        assertEquals(GameType.SHERIFF, session.gameType());
        assertEquals(SheriffState.Phase.START, state.phase());
        assertTrue(state.participantIds().isEmpty());
        assertEquals(0, shuffler.calls());
    }

    @Test void everySheriffOperationRequiresTheConnectedGm() {
        PlayerIdentity guest = guests.getFirst().identity();
        assertError(NOT_AUTHORIZED, () -> service.selectSheriff(room.id(), guest));

        UUID sessionId = select();
        assertError(NOT_AUTHORIZED, () -> service.startSheriff(room.id(), guest, sessionId));
        service.startSheriff(room.id(), room.owner(), sessionId);
        UUID target = state().participantIds().getLast();
        assertError(NOT_AUTHORIZED,
                () -> service.markSheriffDead(room.id(), guest, sessionId, target));
        assertError(NOT_AUTHORIZED, () -> service.endSheriff(room.id(), guest, sessionId));
        service.endSheriff(room.id(), room.owner(), sessionId);
        assertError(NOT_AUTHORIZED, () -> service.restartSheriff(room.id(), guest, sessionId));
    }

    @Test void startRequiresFourConnectedParticipantsAndAcceptsBoundary() {
        UUID sessionId = select();
        service.disconnectPlayer(room.id(), guests.getLast().identity());
        Room before = current();

        assertError(NOT_ENOUGH_PLAYERS,
                () -> service.startSheriff(room.id(), room.owner(), sessionId));
        assertSame(before, current());
        assertEquals(0, shuffler.calls());

        service.reconnectPlayer(room.id(), guests.getLast());
        service.startSheriff(room.id(), room.owner(), sessionId);
        assertEquals(4, state().totalPlayers());
        assertEquals(SheriffState.Phase.PLAYING, state().phase());
    }

    @Test void twentyConnectedParticipantsCanStartWithExactDistribution() {
        addGuests(16);
        UUID sessionId = select();

        service.startSheriff(room.id(), room.owner(), sessionId);

        assertEquals(20, state().totalPlayers());
        assertEquals(1, state().assignedCount(SheriffRole.SHERIFF));
        assertEquals(9, state().assignedCount(SheriffRole.DEPUTY));
        assertEquals(10, state().assignedCount(SheriffRole.OUTLAW));
        assertEquals(0, state().assignedCount(SheriffRole.RENEGADE));
    }

    @Test void startLocksOnlyConnectedMembersInAuthoritativeRoomOrder() {
        var excluded = GuestToken.generate();
        Room joined = service.joinRoom(room.id(), "Excluded", excluded);
        UUID excludedId = joined.playerFor(excluded.identity()).id();
        service.disconnectPlayer(room.id(), excluded.identity());
        UUID sessionId = select();

        service.startSheriff(room.id(), room.owner(), sessionId);
        var expected = current().players().stream()
                .filter(player -> player.connectionState() == ConnectionState.CONNECTED)
                .map(Player::id)
                .toList();

        assertEquals(expected, state().participantIds());
        assertError(PLAYER_NOT_IN_GAME, () -> state().roleForParticipant(excludedId));
    }

    @Test void assignmentFailureLeavesTheStartSessionUntouched() {
        service = new RoomService(store, avatars, new AuthoritativeDiceRoller(() -> 2), roles -> {
            throw new IllegalStateException("simulated shuffle failure");
        });
        UUID sessionId = select();
        Room before = current();

        assertThrows(IllegalStateException.class,
                () -> service.startSheriff(room.id(), room.owner(), sessionId));

        assertSame(before, current());
        assertEquals(SheriffState.Phase.START, state().phase());
        assertTrue(state().participantIds().isEmpty());
    }

    @Test void gmCanMarkAnotherPlayerAndThemselvesDead() {
        UUID sessionId = selectAndStart();
        UUID guestId = current().playerFor(guests.getFirst().identity()).id();
        UUID gmId = current().gmPlayerId();

        service.markSheriffDead(room.id(), room.owner(), sessionId, guestId);
        service.markSheriffDead(room.id(), room.owner(), sessionId, gmId);

        assertEquals(SheriffPlayerStatus.DEAD, state().statusForParticipant(guestId));
        assertEquals(SheriffPlayerStatus.DEAD, state().statusForParticipant(gmId));
        assertEquals(2, state().deadPlayers());
        assertTrue(state().publicRoleForParticipant(guestId).isPresent());
        assertTrue(state().publicRoleForParticipant(gmId).isPresent());
    }

    @Test void duplicateDeathIsANoOpAndCannotChangeCountsOrRevision() {
        UUID sessionId = selectAndStart();
        UUID target = state().participantIds().getLast();
        service.markSheriffDead(room.id(), room.owner(), sessionId, target);
        Room afterFirst = current();
        long revision = afterFirst.revision();
        SheriffRole role = state().roleForParticipant(target);

        Room duplicate = service.markSheriffDead(room.id(), room.owner(), sessionId, target);

        assertSame(afterFirst, duplicate);
        assertEquals(revision, duplicate.revision());
        assertEquals(1, state().deadPlayers());
        assertEquals(1, state().deadCount(role));
        assertEquals(SheriffPlayerStatus.DEAD, state().statusForParticipant(target));
    }

    @Test void reconnectPreservesLockedAssignmentDeathAndCounts() {
        UUID sessionId = selectAndStart();
        GuestToken reconnecting = guests.getFirst();
        UUID playerId = current().playerFor(reconnecting.identity()).id();
        SheriffRole role = state().roleForParticipant(playerId);
        var roster = state().participantIds();
        service.markSheriffDead(room.id(), room.owner(), sessionId, playerId);

        service.disconnectPlayer(room.id(), reconnecting.identity());
        assertEquals(roster, state().participantIds());
        assertEquals(1, state().deadPlayers());
        service.reconnectPlayer(room.id(), reconnecting);

        assertEquals(roster, state().participantIds());
        assertEquals(role, state().roleForParticipant(playerId));
        assertEquals(SheriffPlayerStatus.DEAD, state().statusForParticipant(playerId));
        assertEquals(1, state().deadPlayers());
    }

    @Test void disconnectedGmCannotProgressButCanResumeSameRound() {
        UUID sessionId = selectAndStart();
        UUID target = state().participantIds().getLast();
        SheriffRole role = state().roleForParticipant(target);
        var roster = state().participantIds();

        service.disconnectPlayer(room.id(), room.owner());
        assertError(NOT_AUTHORIZED,
                () -> service.markSheriffDead(room.id(), room.owner(), sessionId, target));
        assertError(NOT_AUTHORIZED,
                () -> service.endSheriff(room.id(), room.owner(), sessionId));
        assertEquals(roster, state().participantIds());
        assertEquals(role, state().roleForParticipant(target));

        service.reconnectGm(room.id(), room.owner());
        service.markSheriffDead(room.id(), room.owner(), sessionId, target);
        assertEquals(SheriffPlayerStatus.DEAD, state().statusForParticipant(target));
    }

    @Test void endRevealsEveryRoleWithoutChangingAssignmentsOrLife() {
        UUID sessionId = selectAndStart();
        UUID dead = state().participantIds().get(1);
        service.markSheriffDead(room.id(), room.owner(), sessionId, dead);
        var playing = state();
        var roles = playing.participantIds().stream().map(playing::roleForParticipant).toList();

        service.endSheriff(room.id(), room.owner(), sessionId);
        var reveal = state();

        assertEquals(SheriffState.Phase.REVEAL, reveal.phase());
        assertEquals(SheriffPlayerStatus.DEAD, reveal.statusForParticipant(dead));
        assertEquals(roles,
                reveal.participantIds().stream().map(reveal::roleForParticipant).toList());
        reveal.participantIds().forEach(playerId ->
                assertTrue(reveal.publicRoleForParticipant(playerId).isPresent()));
    }

    @Test void playAgainCreatesUnassignedStartThenNextStartUsesFreshAssignment() {
        UUID oldSessionId = selectAndStart();
        UUID oldSheriff = state().sheriffPlayerId();
        var players = current().players();
        service.markSheriffDead(room.id(), room.owner(), oldSessionId, oldSheriff);
        service.endSheriff(room.id(), room.owner(), oldSessionId);

        Room restarted = service.restartSheriff(room.id(), room.owner(), oldSessionId);
        UUID newSessionId = restarted.currentSession().orElseThrow().id();

        assertNotEquals(oldSessionId, newSessionId);
        assertEquals(SheriffState.Phase.START, state().phase());
        assertTrue(state().participantIds().isEmpty());
        assertEquals(players, restarted.players());
        assertEquals(1, shuffler.calls());

        service.startSheriff(room.id(), room.owner(), newSessionId);
        assertEquals(2, shuffler.calls());
        assertNotEquals(oldSheriff, state().sheriffPlayerId());
        assertEquals(4, state().alivePlayers());
        assertEquals(0, state().deadPlayers());
        state().participantIds().forEach(playerId ->
                assertEquals(SheriffPlayerStatus.ALIVE, state().statusForParticipant(playerId)));
    }

    @Test void kickWorksOnlyInStartAndNeverChangesLockedRoster() {
        UUID target = current().playerFor(guests.getLast().identity()).id();
        select();
        service.kickPlayer(room.id(), room.owner(), target);
        assertFalse(current().players().stream().anyMatch(player -> player.id().equals(target)));

        setup();
        UUID sessionId = selectAndStart();
        UUID lockedTarget = state().participantIds().getLast();
        var roster = state().participantIds();
        assertError(INVALID_GAME_PHASE,
                () -> service.kickPlayer(room.id(), room.owner(), lockedTarget));
        assertEquals(roster, state().participantIds());

        service.endSheriff(room.id(), room.owner(), sessionId);
        assertError(INVALID_GAME_PHASE,
                () -> service.kickPlayer(room.id(), room.owner(), lockedTarget));
        assertEquals(roster, state().participantIds());
    }

    @Test void joinsRemainClosedForEverySheriffSessionPhase() {
        UUID sessionId = select();
        assertError(GAME_IN_PROGRESS,
                () -> service.joinRoom(room.id(), "Late Start", GuestToken.generate()));
        service.startSheriff(room.id(), room.owner(), sessionId);
        assertError(GAME_IN_PROGRESS,
                () -> service.joinRoom(room.id(), "Late Playing", GuestToken.generate()));
        service.endSheriff(room.id(), room.owner(), sessionId);
        assertError(GAME_IN_PROGRESS,
                () -> service.joinRoom(room.id(), "Late Reveal", GuestToken.generate()));
    }

    @Test void backToRoomSupportsStartAndRevealButNotPlaying() {
        UUID startSession = select();
        assertError(NOT_AUTHORIZED,
                () -> service.backToRoom(room.id(), guests.getFirst().identity(), startSession));
        Room lobby = service.backToRoom(room.id(), room.owner(), startSession);
        assertTrue(lobby.isLobby());
        assertEquals(4, lobby.players().size());

        UUID playingSession = selectAndStart();
        assertError(INVALID_GAME_PHASE,
                () -> service.backToRoom(room.id(), room.owner(), playingSession));
        service.endSheriff(room.id(), room.owner(), playingSession);
        Room returned = service.backToRoom(room.id(), room.owner(), playingSession);
        assertTrue(returned.isLobby());
        assertEquals(4, returned.players().size());
    }

    @Test void staleSessionsWrongPhasesAndUnknownTargetsAreRejected() {
        UUID sessionId = select();
        assertError(STALE_COMMAND,
                () -> service.startSheriff(room.id(), room.owner(), UUID.randomUUID()));
        assertError(INVALID_GAME_PHASE,
                () -> service.markSheriffDead(room.id(), room.owner(), sessionId, UUID.randomUUID()));
        assertError(INVALID_GAME_PHASE,
                () -> service.endSheriff(room.id(), room.owner(), sessionId));
        assertError(INVALID_GAME_PHASE,
                () -> service.restartSheriff(room.id(), room.owner(), sessionId));

        service.startSheriff(room.id(), room.owner(), sessionId);
        assertError(PLAYER_NOT_IN_GAME,
                () -> service.markSheriffDead(room.id(), room.owner(), sessionId, UUID.randomUUID()));
        assertError(INVALID_GAME_PHASE,
                () -> service.startSheriff(room.id(), room.owner(), sessionId));
        assertError(INVALID_GAME_PHASE,
                () -> service.restartSheriff(room.id(), room.owner(), sessionId));
    }

    @Test void crossGameOperationsRemainRejected() {
        service.startWhoAmI(room.id(), room.owner());
        UUID whoAmISession = current().currentSession().orElseThrow().id();
        assertError(INVALID_GAME_PHASE,
                () -> service.startSheriff(room.id(), room.owner(), whoAmISession));
        assertError(INVALID_GAME_PHASE,
                () -> service.endSheriff(room.id(), room.owner(), whoAmISession));

        setup();
        UUID sheriffSession = select();
        assertError(INVALID_GAME_PHASE,
                () -> service.startLiarsDice(room.id(), room.owner(), sheriffSession));
        assertError(INVALID_GAME_PHASE,
                () -> service.submitName(room.id(), room.owner(), sheriffSession, "secret"));
    }

    private UUID select() {
        return service.selectSheriff(room.id(), room.owner()).currentSession().orElseThrow().id();
    }

    private UUID selectAndStart() {
        UUID sessionId = select();
        service.startSheriff(room.id(), room.owner(), sessionId);
        return sessionId;
    }

    private void addGuests(int count) {
        int start = guests.size();
        for (int index = 0; index < count; index++) {
            var token = GuestToken.generate();
            service.joinRoom(room.id(), "Guest " + (start + index + 1), token);
            guests.add(token);
        }
    }

    private Room current() {
        return store.find(room.id()).orElseThrow();
    }

    private SheriffState state() {
        return (SheriffState) current().currentSession().orElseThrow().state();
    }

    private static void assertError(DomainException.Code code,
                                    org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(DomainException.class, action).code());
    }

    private static final class AlternatingShuffler implements SheriffRoleShuffler {
        private final AtomicInteger calls = new AtomicInteger();

        @Override public List<SheriffRole> shuffle(List<SheriffRole> roles) {
            var shuffled = new ArrayList<>(roles);
            if (calls.incrementAndGet() % 2 == 0) Collections.rotate(shuffled, -1);
            return List.copyOf(shuffled);
        }

        int calls() { return calls.get(); }
    }
}
