package com.drinkduel.game;

import com.drinkduel.room.DomainException;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.drinkduel.room.DomainException.Code.INVALID_GAME_PHASE;
import static com.drinkduel.room.DomainException.Code.PLAYER_NOT_IN_GAME;
import static org.junit.jupiter.api.Assertions.*;

class SheriffStateTests {
    @Test void startHasNoAssignmentsOrLifeState() {
        var state = SheriffState.start();

        assertEquals(GameType.SHERIFF, state.gameType());
        assertEquals(SheriffState.Phase.START, state.phase());
        assertTrue(state.participantIds().isEmpty());
        assertDomainError(INVALID_GAME_PHASE, state::totalPlayers);
        assertDomainError(INVALID_GAME_PHASE, state::sheriffPlayerId);
    }

    @Test void everySupportedRosterGetsTheExactEvenOrOddDistribution() {
        for (int size = SheriffState.MIN_PARTICIPANTS; size <= SheriffState.MAX_PARTICIPANTS; size++) {
            var state = SheriffState.start().startRound(roster(size), identityShuffler());

            assertEquals(size, state.totalPlayers());
            assertEquals(1, state.assignedCount(SheriffRole.SHERIFF));
            assertEquals(size / 2 - 1, state.assignedCount(SheriffRole.DEPUTY));
            assertEquals(size / 2, state.assignedCount(SheriffRole.OUTLAW));
            assertEquals(size % 2, state.assignedCount(SheriffRole.RENEGADE));
            assertEquals(size, state.alivePlayers());
            assertEquals(0, state.deadPlayers());
            for (SheriffRole role : SheriffRole.values()) {
                assertEquals(state.assignedCount(role), state.aliveCount(role));
                assertEquals(0, state.deadCount(role));
            }
        }
    }

    @Test void rosterMustContainFourToTwentyDistinctParticipants() {
        assertThrows(IllegalArgumentException.class,
                () -> SheriffState.start().startRound(roster(3), identityShuffler()));
        assertThrows(IllegalArgumentException.class,
                () -> SheriffState.start().startRound(roster(21), identityShuffler()));
        UUID duplicate = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> SheriffState.start().startRound(List.of(duplicate, duplicate,
                        UUID.randomUUID(), UUID.randomUUID()), identityShuffler()));
    }

    @Test void injectedShuffleChangesWhichParticipantIsSheriff() {
        var roster = roster(6);
        var identity = SheriffState.start().startRound(roster, identityShuffler());
        var rotated = SheriffState.start().startRound(roster, roles -> {
            var copy = new ArrayList<>(roles);
            Collections.rotate(copy, -1);
            return copy;
        });

        assertEquals(roster.getFirst(), identity.sheriffPlayerId());
        assertEquals(roster.getLast(), rotated.sheriffPlayerId());
        assertNotEquals(identity.sheriffPlayerId(), rotated.sheriffPlayerId());
    }

    @Test void productionShufflerReturnsAnImmutableExactPermutation() {
        var input = List.of(SheriffRole.SHERIFF, SheriffRole.DEPUTY,
                SheriffRole.OUTLAW, SheriffRole.OUTLAW, SheriffRole.RENEGADE);
        var shuffled = new SecureSheriffRoleShuffler(new SecureRandom(new byte[] { 1, 2, 3 }))
                .shuffle(input);

        assertEquals(input.size(), shuffled.size());
        for (SheriffRole role : SheriffRole.values()) {
            assertEquals(input.stream().filter(role::equals).count(),
                    shuffled.stream().filter(role::equals).count());
        }
        assertThrows(UnsupportedOperationException.class, () -> shuffled.set(0, SheriffRole.OUTLAW));
    }

    @Test void malformedShufflerOutputCannotCommitAPartialOrInvalidAssignment() {
        var start = SheriffState.start();
        var roster = roster(4);

        assertThrows(IllegalArgumentException.class,
                () -> start.startRound(roster, roles -> roles.subList(0, roles.size() - 1)));
        assertEquals(SheriffState.Phase.START, start.phase());
        assertTrue(start.participantIds().isEmpty());
    }

    @Test void stateDefensivelyCopiesRosterAndExposesImmutableParticipantOrder() {
        var rosterInput = new ArrayList<>(roster(5));
        var expected = List.copyOf(rosterInput);
        var state = SheriffState.start().startRound(rosterInput, identityShuffler());
        rosterInput.clear();

        assertEquals(expected, state.participantIds());
        assertThrows(UnsupportedOperationException.class,
                () -> state.participantIds().add(UUID.randomUUID()));
    }

    @Test void deathIsImmutableIrreversibleAndDuplicateDeliveryIsIdempotent() {
        var playing = SheriffState.start().startRound(roster(5), identityShuffler());
        UUID target = playing.participantIds().get(2);
        SheriffRole targetRole = playing.roleForParticipant(target);

        var afterDeath = playing.markDead(target);
        var duplicate = afterDeath.markDead(target);

        assertEquals(SheriffPlayerStatus.ALIVE, playing.statusForParticipant(target));
        assertEquals(SheriffPlayerStatus.DEAD, afterDeath.statusForParticipant(target));
        assertEquals(4, afterDeath.alivePlayers());
        assertEquals(1, afterDeath.deadPlayers());
        assertEquals(1, afterDeath.deadCount(targetRole));
        assertSame(afterDeath, duplicate);
    }

    @Test void publicRoleBoundaryExposesOnlySheriffDeathsAndFinalReveal() {
        var playing = SheriffState.start().startRound(roster(5), identityShuffler());
        UUID sheriff = playing.sheriffPlayerId();
        UUID secretPlayer = playing.participantIds().stream()
                .filter(playerId -> !playerId.equals(sheriff))
                .findFirst().orElseThrow();

        assertEquals(SheriffRole.SHERIFF, playing.publicRoleForParticipant(sheriff).orElseThrow());
        assertTrue(playing.publicRoleForParticipant(secretPlayer).isEmpty());

        var afterDeath = playing.markDead(secretPlayer);
        assertEquals(afterDeath.roleForParticipant(secretPlayer),
                afterDeath.publicRoleForParticipant(secretPlayer).orElseThrow());

        var reveal = afterDeath.reveal();
        reveal.participantIds().forEach(playerId ->
                assertEquals(reveal.roleForParticipant(playerId),
                        reveal.publicRoleForParticipant(playerId).orElseThrow()));
    }

    @Test void revealPreservesRosterAssignmentsAndLifeState() {
        var playing = SheriffState.start().startRound(roster(7), identityShuffler());
        UUID deadPlayer = playing.participantIds().getLast();
        var reveal = playing.markDead(deadPlayer).reveal();

        assertEquals(SheriffState.Phase.REVEAL, reveal.phase());
        assertEquals(playing.participantIds(), reveal.participantIds());
        assertEquals(SheriffPlayerStatus.DEAD, reveal.statusForParticipant(deadPlayer));
        for (UUID playerId : playing.participantIds()) {
            assertEquals(playing.roleForParticipant(playerId), reveal.roleForParticipant(playerId));
        }
    }

    @Test void invalidPhaseTransitionsAndUnknownParticipantsAreRejected() {
        var start = SheriffState.start();
        assertDomainError(INVALID_GAME_PHASE, () -> start.markDead(UUID.randomUUID()));
        assertDomainError(INVALID_GAME_PHASE, start::reveal);

        var playing = start.startRound(roster(4), identityShuffler());
        assertDomainError(INVALID_GAME_PHASE,
                () -> playing.startRound(roster(4), identityShuffler()));
        assertDomainError(PLAYER_NOT_IN_GAME, () -> playing.markDead(UUID.randomUUID()));
        assertDomainError(PLAYER_NOT_IN_GAME, () -> playing.roleForParticipant(UUID.randomUUID()));

        var reveal = playing.reveal();
        assertDomainError(INVALID_GAME_PHASE, () -> reveal.markDead(reveal.participantIds().getFirst()));
        assertDomainError(INVALID_GAME_PHASE, reveal::reveal);
        assertDomainError(INVALID_GAME_PHASE,
                () -> reveal.startRound(roster(4), identityShuffler()));
    }

    @Test void participantRemovalIsOnlyANoOpBeforeRosterLock() {
        UUID playerId = UUID.randomUUID();
        var start = SheriffState.start();
        assertSame(start, start.withoutParticipant(playerId));

        var playing = start.startRound(roster(4), identityShuffler());
        assertDomainError(INVALID_GAME_PHASE, () -> playing.withoutParticipant(playerId));
        var reveal = playing.reveal();
        assertDomainError(INVALID_GAME_PHASE, () -> reveal.withoutParticipant(playerId));
    }

    @Test void stringRepresentationDoesNotExposeAssignmentsOrParticipantIds() {
        var state = SheriffState.start().startRound(roster(4), identityShuffler());

        assertTrue(state.toString().contains("secrets=redacted"));
        state.participantIds().forEach(id -> assertFalse(state.toString().contains(id.toString())));
        for (SheriffRole role : SheriffRole.values()) {
            assertFalse(state.toString().contains(role.name()));
        }
    }

    private static SheriffRoleShuffler identityShuffler() {
        return List::copyOf;
    }

    private static List<UUID> roster(int size) {
        return IntStream.range(0, size).mapToObj(ignored -> UUID.randomUUID()).toList();
    }

    private static void assertDomainError(DomainException.Code code,
                                          org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(DomainException.class, action).code());
    }
}
