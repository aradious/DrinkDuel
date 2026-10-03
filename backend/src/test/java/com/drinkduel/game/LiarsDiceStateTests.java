package com.drinkduel.game;

import com.drinkduel.room.DomainException;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.drinkduel.room.DomainException.Code.INVALID_GAME_PHASE;
import static org.junit.jupiter.api.Assertions.*;

class LiarsDiceStateTests {
    @Test void startHasNoRoundData() {
        var state = LiarsDiceState.start();

        assertEquals(GameType.LIARS_DICE, state.gameType());
        assertEquals(LiarsDiceState.Phase.START, state.phase());
        assertTrue(state.participantIds().isEmpty());
        assertDomainError(INVALID_GAME_PHASE, state::revealedHands);
        assertDomainError(INVALID_GAME_PHASE, state::revealCounts);
    }

    @Test void malformedExternallyConstructedHandsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DiceHand(List.of(1, 1, 2, 3)));
        assertThrows(IllegalArgumentException.class, () -> new DiceHand(List.of(1, 1, 2, 3, 4, 5)));
        assertThrows(IllegalArgumentException.class, () -> new DiceHand(List.of(0, 1, 1, 2, 3)));
        assertThrows(IllegalArgumentException.class, () -> new DiceHand(List.of(1, 1, 2, 3, 7)));
        assertThrows(IllegalArgumentException.class, () -> new DiceHand(List.of(1, 2, 3, 4, 5)));
        assertThrows(IllegalArgumentException.class, () -> new DiceHand(List.of(2, 3, 4, 5, 6)));
    }

    @Test void handIsExactlyFiveInRangeAndImmutable() {
        var input = new ArrayList<>(List.of(1, 1, 2, 3, 6));
        var hand = new DiceHand(input);
        input.set(0, 6);

        assertEquals(List.of(1, 1, 2, 3, 6), hand.values());
        assertEquals(5, hand.values().size());
        assertTrue(hand.values().stream().allMatch(value -> value >= 1 && value <= 6));
        assertThrows(UnsupportedOperationException.class, () -> hand.values().set(0, 2));
    }

    @Test void rollerRejectsWholeDistinctCandidateAndAcceptsExactSecondCandidate() {
        var source = new SequenceRandom(1, 2, 3, 4, 5, 2, 2, 3, 4, 6);
        var hand = new AuthoritativeDiceRoller(source).rollHand();

        assertEquals(List.of(2, 2, 3, 4, 6), hand.values());
        assertEquals(10, source.calls());
    }

    @Test void rollerGeneratesFiveValuesForAnAcceptedCandidate() {
        var calls = new AtomicInteger();
        var hand = new AuthoritativeDiceRoller(() -> {
            calls.incrementAndGet();
            return 4;
        }).rollHand();

        assertEquals(5, calls.get());
        assertEquals(List.of(4, 4, 4, 4, 4), hand.values());
    }

    @Test void rollerRejectsOutOfRangeRandomSourceValue() {
        assertThrows(IllegalStateException.class,
                () -> new AuthoritativeDiceRoller(() -> 7).rollHand());
    }

    @Test void secureProductionSourceAlwaysReturnsValidFaces() {
        var source = new SecureDiceRandomSource();
        for (int index = 0; index < 1_000; index++) {
            assertTrue(source.nextDieValue() >= 1 && source.nextDieValue() <= 6);
        }
    }

    @Test void rosterMustHaveAtLeastTwoDistinctParticipants() {
        var roller = constantRoller();
        UUID player = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> LiarsDiceState.start().startRound(List.of(player), roller));
        assertThrows(IllegalArgumentException.class,
                () -> LiarsDiceState.start().startRound(List.of(player, player), roller));
    }

    @Test void rosterCannotExceedRoomCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> LiarsDiceState.start().startRound(roster(21), constantRoller()));
    }

    @Test void createsValidIndependentHandsForTwoTenAndTwentyParticipants() {
        for (int size : List.of(2, 10, 20)) {
            var roster = roster(size);
            var counter = new AtomicInteger();
            var state = LiarsDiceState.start().startRound(roster,
                    new AuthoritativeDiceRoller(() -> counter.incrementAndGet() % 2 + 1));

            assertEquals(roster, state.participantIds());
            assertEquals(LiarsDiceState.Phase.PLAYING, state.phase());
            var seen = new ArrayList<DiceHand>();
            for (UUID participant : roster) {
                DiceHand hand = state.handForParticipant(participant);
                assertEquals(5, hand.values().size());
                assertTrue(hand.values().stream().allMatch(value -> value >= 1 && value <= 6));
                seen.forEach(previous -> assertNotSame(previous, hand));
                seen.add(hand);
            }
        }
    }

    @Test void playingExposesOnlyOneRequestedHandAndNotAllHands() {
        var roster = roster(2);
        var playing = LiarsDiceState.start().startRound(roster, constantRoller());

        assertEquals(List.of(3, 3, 3, 3, 3), playing.handForParticipant(roster.getFirst()).values());
        assertDomainError(INVALID_GAME_PHASE, playing::revealedHands);
        assertDomainError(INVALID_GAME_PHASE, playing::revealCounts);
    }

    @Test void startTransitionsToPlayingAndPlayingTransitionsToReveal() {
        var playing = LiarsDiceState.start().startRound(roster(2), constantRoller());
        var reveal = playing.reveal();

        assertEquals(LiarsDiceState.Phase.PLAYING, playing.phase());
        assertEquals(LiarsDiceState.Phase.REVEAL, reveal.phase());
        assertEquals(playing.participantIds(), reveal.participantIds());
        playing.participantIds().forEach(id -> assertSame(playing.handForParticipant(id), reveal.handForParticipant(id)));
    }

    @Test void invalidPhaseTransitionsAreRejectedAndRevealCannotRestartInPlace() {
        var start = LiarsDiceState.start();
        assertDomainError(INVALID_GAME_PHASE, start::reveal);
        var playing = start.startRound(roster(2), constantRoller());
        assertDomainError(INVALID_GAME_PHASE, () -> playing.startRound(roster(2), constantRoller()));
        var reveal = playing.reveal();
        assertDomainError(INVALID_GAME_PHASE, reveal::reveal);
        assertDomainError(INVALID_GAME_PHASE, () -> reveal.startRound(roster(2), constantRoller()));
    }

    @Test void revealCalculatesRawAndIndependentWildOneEffectiveCounts() {
        var source = new SequenceRandom(
                1, 1, 2, 3, 4,
                2, 2, 4, 5, 6);
        var reveal = LiarsDiceState.start().startRound(roster(2),
                new AuthoritativeDiceRoller(source)).reveal();
        var counts = reveal.revealCounts();

        assertEquals(List.of(2, 3, 1, 2, 1, 1),
                IntStream.rangeClosed(1, 6).map(counts::rawCount).boxed().toList());
        assertEquals(List.of(2, 5, 3, 4, 3, 3),
                IntStream.rangeClosed(1, 6).map(counts::effectiveCount).boxed().toList());
        assertEquals(10, counts.raw().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test void stateCollectionsAndRevealCountsAreImmutable() {
        var rosterInput = new ArrayList<>(roster(2));
        var playing = LiarsDiceState.start().startRound(rosterInput, constantRoller());
        rosterInput.clear();
        var reveal = playing.reveal();

        assertEquals(2, playing.participantIds().size());
        assertThrows(UnsupportedOperationException.class,
                () -> playing.participantIds().add(UUID.randomUUID()));
        assertThrows(UnsupportedOperationException.class,
                () -> reveal.revealedHands().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> reveal.revealCounts().raw().put(1, 99));
    }

    @Test void participantRemovalIsNoOpInStartAndRejectedAfterRosterLocks() {
        UUID player = UUID.randomUUID();
        var start = LiarsDiceState.start();
        assertSame(start, start.withoutParticipant(player));

        var playing = start.startRound(roster(2), constantRoller());
        assertDomainError(INVALID_GAME_PHASE, () -> playing.withoutParticipant(playing.participantIds().getFirst()));
        var reveal = playing.reveal();
        assertDomainError(INVALID_GAME_PHASE, () -> reveal.withoutParticipant(reveal.participantIds().getFirst()));
    }

    private static AuthoritativeDiceRoller constantRoller() {
        return new AuthoritativeDiceRoller(() -> 3);
    }

    private static List<UUID> roster(int size) {
        return IntStream.range(0, size).mapToObj(ignored -> UUID.randomUUID()).toList();
    }

    private static void assertDomainError(DomainException.Code code,
                                          org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(DomainException.class, action).code());
    }

    private static final class SequenceRandom implements DiceRandomSource {
        private final ArrayDeque<Integer> values;
        private int calls;

        private SequenceRandom(Integer... values) {
            this.values = new ArrayDeque<>(List.of(values));
        }

        @Override public int nextDieValue() {
            calls++;
            return values.removeFirst();
        }

        int calls() { return calls; }
    }
}
