package com.drinkduel.room;

import com.drinkduel.game.AuthoritativeDiceRoller;
import com.drinkduel.game.DiceRandomSource;
import com.drinkduel.game.GameSession;
import com.drinkduel.game.GameType;
import com.drinkduel.game.LiarsDiceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.drinkduel.room.DomainException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class LiarsDiceServiceTests {
    private AvatarCatalog avatars;
    private RoomStore store;
    private CountingRandom random;
    private RoomService service;
    private Room room;
    private GuestToken guest;

    @BeforeEach void setup() {
        avatars = new AvatarCatalog();
        store = new InMemoryRoomStore(Clock.systemUTC(), avatars);
        random = new CountingRandom(2);
        service = new RoomService(store, avatars, new AuthoritativeDiceRoller(random));
        room = service.createRoom(new PlayerIdentity.Google("liars-owner"), "GM");
        guest = GuestToken.generate();
        service.joinRoom(room.id(), "Guest", guest);
    }

    @Test void gmSelectsLiarsDiceWithoutRollingOrCapturingRoster() {
        Room selected = service.selectLiarsDice(room.id(), room.owner());
        GameSession session = selected.currentSession().orElseThrow();
        var state = (LiarsDiceState) session.state();

        assertEquals(GameType.LIARS_DICE, session.gameType());
        assertEquals(LiarsDiceState.Phase.START, state.phase());
        assertTrue(state.participantIds().isEmpty());
        assertEquals(0, random.calls());
    }

    @Test void nonGmCannotSelectStartEndOrRestart() {
        assertError(NOT_AUTHORIZED, () -> service.selectLiarsDice(room.id(), guest.identity()));
        UUID session = select();
        assertError(NOT_AUTHORIZED, () -> service.startLiarsDice(room.id(), guest.identity(), session));
        service.startLiarsDice(room.id(), room.owner(), session);
        assertError(NOT_AUTHORIZED, () -> service.endLiarsDice(room.id(), guest.identity(), session));
        service.endLiarsDice(room.id(), room.owner(), session);
        assertError(NOT_AUTHORIZED, () -> service.restartLiarsDice(room.id(), guest.identity(), session));
    }

    @Test void startRequiresTwoConnectedMembersAndFailureIsAtomic() {
        UUID session = select();
        service.disconnectPlayer(room.id(), guest.identity());
        Room before = current();

        assertError(NOT_ENOUGH_PLAYERS,
                () -> service.startLiarsDice(room.id(), room.owner(), session));

        Room after = current();
        assertSame(before, after);
        assertEquals(before.revision(), after.revision());
        assertEquals(LiarsDiceState.Phase.START, state().phase());
        assertTrue(state().participantIds().isEmpty());
        assertEquals(0, random.calls());
    }

    @Test void startCapturesOnlyConnectedMembersInRoomOrder() {
        var excluded = GuestToken.generate();
        Room joined = service.joinRoom(room.id(), "Excluded", excluded);
        UUID excludedId = joined.playerFor(excluded.identity()).id();
        service.disconnectPlayer(room.id(), excluded.identity());
        UUID session = select();

        service.startLiarsDice(room.id(), room.owner(), session);
        var state = state();
        var expected = current().players().stream()
                .filter(player -> player.connectionState() == ConnectionState.CONNECTED)
                .map(Player::id).toList();

        assertEquals(expected, state.participantIds());
        assertEquals(2, state.participantIds().stream().distinct().count());
        state.participantIds().forEach(id -> assertEquals(5, state.handForParticipant(id).values().size()));
        assertError(PLAYER_NOT_IN_GAME, () -> state.handForParticipant(excludedId));
    }

    @Test void repeatedStartIsRejectedWithoutRerolling() {
        UUID session = select();
        service.startLiarsDice(room.id(), room.owner(), session);
        var hands = state().participantIds().stream().map(state()::handForParticipant).toList();
        int calls = random.calls();

        assertError(INVALID_GAME_PHASE,
                () -> service.startLiarsDice(room.id(), room.owner(), session));

        assertEquals(calls, random.calls());
        assertEquals(hands, state().participantIds().stream().map(state()::handForParticipant).toList());
    }

    @Test void rollerFailureCannotCommitAPartialRosterOrAnyHands() {
        var calls = new AtomicInteger();
        service = new RoomService(store, avatars, new AuthoritativeDiceRoller(() -> {
            if (calls.incrementAndGet() > 5) throw new IllegalStateException("simulated random failure");
            return 2;
        }));
        UUID session = select();
        Room before = current();

        assertThrows(IllegalStateException.class,
                () -> service.startLiarsDice(room.id(), room.owner(), session));

        assertSame(before, current());
        assertEquals(LiarsDiceState.Phase.START, state().phase());
        assertTrue(state().participantIds().isEmpty());
    }

    @Test void reconnectPreservesExistingHandAndDoesNotAlterLockedRoster() {
        UUID session = select();
        service.startLiarsDice(room.id(), room.owner(), session);
        UUID guestId = current().playerFor(guest.identity()).id();
        var roster = state().participantIds();
        var hand = state().handForParticipant(guestId);
        int calls = random.calls();

        service.disconnectPlayer(room.id(), guest.identity());
        service.reconnectPlayer(room.id(), guest);

        assertEquals(roster, state().participantIds());
        assertSame(hand, state().handForParticipant(guestId));
        assertEquals(calls, random.calls());
    }

    @Test void reconnectingExcludedMemberDoesNotJoinRoundOrReceiveHand() {
        var excluded = GuestToken.generate();
        Room joined = service.joinRoom(room.id(), "Excluded", excluded);
        UUID excludedId = joined.playerFor(excluded.identity()).id();
        service.disconnectPlayer(room.id(), excluded.identity());
        UUID session = select();
        service.startLiarsDice(room.id(), room.owner(), session);
        var roster = state().participantIds();

        service.reconnectPlayer(room.id(), excluded);

        assertEquals(roster, state().participantIds());
        assertError(PLAYER_NOT_IN_GAME, () -> state().handForParticipant(excludedId));
    }

    @Test void endTransitionsToRevealPreservingExactHandsAndCounts() {
        var sequence = new SequenceRandom(
                1, 1, 2, 3, 4,
                2, 2, 4, 5, 6);
        service = new RoomService(store, avatars, new AuthoritativeDiceRoller(sequence));
        UUID session = select();
        service.startLiarsDice(room.id(), room.owner(), session);
        var playing = state();
        var roster = playing.participantIds();
        var hands = roster.stream().map(playing::handForParticipant).toList();

        service.endLiarsDice(room.id(), room.owner(), session);
        var reveal = state();

        assertEquals(LiarsDiceState.Phase.REVEAL, reveal.phase());
        assertEquals(roster, reveal.participantIds());
        for (int index = 0; index < roster.size(); index++)
            assertSame(hands.get(index), reveal.handForParticipant(roster.get(index)));
        assertEquals(List.of(2, 3, 1, 2, 1, 1),
                faces().stream().map(reveal.revealCounts()::rawCount).toList());
        assertEquals(List.of(2, 5, 3, 4, 3, 3),
                faces().stream().map(reveal.revealCounts()::effectiveCount).toList());
    }

    @Test void kickSucceedsInStartAndIsRejectedWithNoMutationAfterRosterLocks() {
        UUID target = current().playerFor(guest.identity()).id();
        select();
        service.kickPlayer(room.id(), room.owner(), target);
        assertFalse(current().players().stream().anyMatch(player -> player.id().equals(target)));

        // Return to a fresh fixture because new joins remain closed while a session exists.
        setup();
        UUID playingTarget = current().playerFor(guest.identity()).id();
        UUID session = select();
        service.startLiarsDice(room.id(), room.owner(), session);
        Room playing = current();
        assertError(INVALID_GAME_PHASE,
                () -> service.kickPlayer(room.id(), room.owner(), playingTarget));
        assertSame(playing, current());

        service.endLiarsDice(room.id(), room.owner(), session);
        Room reveal = current();
        assertError(INVALID_GAME_PHASE,
                () -> service.kickPlayer(room.id(), room.owner(), playingTarget));
        assertSame(reveal, current());
    }

    @Test void backToRoomIsGmOnlyAndAvailableOnlyInStart() {
        UUID startSession = select();
        assertError(NOT_AUTHORIZED,
                () -> service.backToRoom(room.id(), guest.identity(), startSession));

        Room lobby = service.backToRoom(room.id(), room.owner(), startSession);
        assertTrue(lobby.isLobby());
        assertEquals(2, lobby.players().size());

        UUID playingSession = service.selectLiarsDice(room.id(), room.owner())
                .currentSession().orElseThrow().id();
        service.startLiarsDice(room.id(), room.owner(), playingSession);
        assertError(INVALID_GAME_PHASE,
                () -> service.backToRoom(room.id(), room.owner(), playingSession));
    }

    @Test void restartCreatesCleanNewSessionWithoutRollingAndPreservesRoomData() {
        UUID session = select();
        service.startLiarsDice(room.id(), room.owner(), session);
        service.endLiarsDice(room.id(), room.owner(), session);
        Room before = current();
        var players = before.players();
        int calls = random.calls();

        Room restarted = service.restartLiarsDice(room.id(), room.owner(), session);
        GameSession next = restarted.currentSession().orElseThrow();
        var state = (LiarsDiceState) next.state();

        assertNotEquals(session, next.id());
        assertEquals(GameType.LIARS_DICE, next.gameType());
        assertEquals(LiarsDiceState.Phase.START, state.phase());
        assertTrue(state.participantIds().isEmpty());
        assertEquals(calls, random.calls());
        assertEquals(players, restarted.players());
        assertEquals(before.gmPlayerId(), restarted.gmPlayerId());
        assertEquals(before.owner(), restarted.owner());
        assertEquals(before.id(), restarted.id());
    }

    @Test void wrongPhaseAndStaleSessionOperationsAreRejected() {
        UUID session = select();
        assertError(INVALID_GAME_PHASE,
                () -> service.endLiarsDice(room.id(), room.owner(), session));
        assertError(INVALID_GAME_PHASE,
                () -> service.restartLiarsDice(room.id(), room.owner(), session));
        assertError(STALE_COMMAND,
                () -> service.startLiarsDice(room.id(), room.owner(), UUID.randomUUID()));
        service.startLiarsDice(room.id(), room.owner(), session);
        assertError(INVALID_GAME_PHASE,
                () -> service.restartLiarsDice(room.id(), room.owner(), session));
    }

    @Test void crossGameOperationsAreRejected() {
        service.startWhoAmI(room.id(), room.owner());
        UUID whoSession = current().currentSession().orElseThrow().id();
        assertError(INVALID_GAME_PHASE,
                () -> service.startLiarsDice(room.id(), room.owner(), whoSession));
        assertError(INVALID_GAME_PHASE,
                () -> service.endLiarsDice(room.id(), room.owner(), whoSession));

        setup();
        UUID liarsSession = select();
        assertError(INVALID_GAME_PHASE,
                () -> service.submitName(room.id(), room.owner(), liarsSession, "secret"));
        assertError(INVALID_GAME_PHASE,
                () -> service.endGame(room.id(), room.owner(), liarsSession));
    }

    @Test void simultaneousStartsCommitOneRoundAndRollExactlyOnce() throws Exception {
        UUID session = select();
        var gate = new CountDownLatch(1);
        var successes = new AtomicInteger();
        var failures = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 2; index++) {
                tasks.add(executor.submit(() -> {
                    try {
                        gate.await();
                        service.startLiarsDice(room.id(), room.owner(), session);
                        successes.incrementAndGet();
                    } catch (DomainException expected) {
                        assertEquals(INVALID_GAME_PHASE, expected.code());
                        failures.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        fail(interrupted);
                    }
                }));
            }
            gate.countDown();
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }

        assertEquals(1, successes.get());
        assertEquals(1, failures.get());
        assertEquals(10, random.calls());
        assertEquals(LiarsDiceState.Phase.PLAYING, state().phase());
    }

    private UUID select() {
        return service.selectLiarsDice(room.id(), room.owner()).currentSession().orElseThrow().id();
    }

    private Room current() {
        return store.find(room.id()).orElseThrow();
    }

    private LiarsDiceState state() {
        return (LiarsDiceState) current().currentSession().orElseThrow().state();
    }

    private static List<Integer> faces() { return List.of(1, 2, 3, 4, 5, 6); }

    private static void assertError(DomainException.Code code,
                                    org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(DomainException.class, action).code());
    }

    private static final class CountingRandom implements DiceRandomSource {
        private final int value;
        private final AtomicInteger calls = new AtomicInteger();
        private CountingRandom(int value) { this.value = value; }
        @Override public int nextDieValue() { calls.incrementAndGet(); return value; }
        int calls() { return calls.get(); }
    }

    private static final class SequenceRandom implements DiceRandomSource {
        private final ArrayDeque<Integer> values;
        private SequenceRandom(Integer... values) { this.values = new ArrayDeque<>(List.of(values)); }
        @Override public int nextDieValue() { return values.removeFirst(); }
    }
}
