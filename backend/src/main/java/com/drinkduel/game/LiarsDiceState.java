package com.drinkduel.game;

import com.drinkduel.room.DomainException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.drinkduel.room.DomainException.Code.INVALID_GAME_PHASE;
import static com.drinkduel.room.DomainException.Code.PLAYER_NOT_IN_GAME;

/** Immutable authoritative Liar's Dice session state. */
public final class LiarsDiceState implements GameState {
    public enum Phase { START, PLAYING, REVEAL }

    private final Phase phase;
    private final List<UUID> participantIds;
    private final Map<UUID, DiceHand> hands;
    private final LiarsDiceRevealCounts revealCounts;

    private LiarsDiceState(Phase phase, List<UUID> participantIds,
                           Map<UUID, DiceHand> hands, LiarsDiceRevealCounts revealCounts) {
        this.phase = Objects.requireNonNull(phase);
        this.participantIds = List.copyOf(Objects.requireNonNull(participantIds));
        this.hands = immutableHands(hands);
        this.revealCounts = revealCounts;
        validateState();
    }

    public static LiarsDiceState start() {
        return new LiarsDiceState(Phase.START, List.of(), Map.of(), null);
    }

    public LiarsDiceState startRound(List<UUID> roster, AuthoritativeDiceRoller roller) {
        if (phase != Phase.START) throw new DomainException(INVALID_GAME_PHASE);
        Objects.requireNonNull(roster);
        Objects.requireNonNull(roller);
        var participants = List.copyOf(roster);
        validateRoster(participants);
        var rolledHands = new LinkedHashMap<UUID, DiceHand>();
        participants.forEach(playerId -> rolledHands.put(playerId, roller.rollHand()));
        return new LiarsDiceState(Phase.PLAYING, participants, rolledHands, null);
    }

    public LiarsDiceState reveal() {
        if (phase != Phase.PLAYING) throw new DomainException(INVALID_GAME_PHASE);
        return new LiarsDiceState(Phase.REVEAL, participantIds, hands,
                LiarsDiceRevealCounts.from(hands.values()));
    }

    /** Returns one authoritative hand for later recipient-specific projection. */
    public DiceHand handForParticipant(UUID playerId) {
        if (phase == Phase.START) throw new DomainException(INVALID_GAME_PHASE);
        DiceHand hand = hands.get(playerId);
        if (hand == null) throw new DomainException(PLAYER_NOT_IN_GAME);
        return hand;
    }

    /** All hands become available only after the authoritative Reveal transition. */
    public Map<UUID, DiceHand> revealedHands() {
        if (phase != Phase.REVEAL) throw new DomainException(INVALID_GAME_PHASE);
        return hands;
    }

    public LiarsDiceRevealCounts revealCounts() {
        if (phase != Phase.REVEAL) throw new DomainException(INVALID_GAME_PHASE);
        return revealCounts;
    }

    @Override
    public LiarsDiceState withoutParticipant(UUID playerId) {
        Objects.requireNonNull(playerId);
        if (phase == Phase.START) return this;
        throw new DomainException(INVALID_GAME_PHASE);
    }

    @Override public GameType gameType() { return GameType.LIARS_DICE; }
    public Phase phase() { return phase; }
    public List<UUID> participantIds() { return participantIds; }

    private void validateState() {
        if (phase == Phase.START) {
            if (!participantIds.isEmpty() || !hands.isEmpty() || revealCounts != null)
                throw new IllegalArgumentException("START cannot contain round data");
            return;
        }
        validateRoster(participantIds);
        if (!hands.keySet().equals(new java.util.LinkedHashSet<>(participantIds)))
            throw new IllegalArgumentException("Every roster participant must have exactly one hand");
        if ((phase == Phase.PLAYING) != (revealCounts == null))
            throw new IllegalArgumentException("Reveal counts must exist only in REVEAL");
    }

    private static void validateRoster(List<UUID> roster) {
        if (roster.size() < 2 || roster.size() > 20 || roster.stream().anyMatch(Objects::isNull)
                || roster.stream().distinct().count() != roster.size()) {
            throw new IllegalArgumentException("Round roster requires 2 to 20 distinct participant IDs");
        }
    }

    private static Map<UUID, DiceHand> immutableHands(Map<UUID, DiceHand> source) {
        Objects.requireNonNull(source);
        var copy = new LinkedHashMap<UUID, DiceHand>();
        source.forEach((id, hand) -> copy.put(Objects.requireNonNull(id), Objects.requireNonNull(hand)));
        return Collections.unmodifiableMap(copy);
    }

    @Override public String toString() { return "LiarsDiceState[phase=" + phase + ", secrets=redacted]"; }
}
