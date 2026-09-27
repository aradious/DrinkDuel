package com.drinkduel.game;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import com.drinkduel.room.DomainException;
import static com.drinkduel.room.DomainException.Code.*;

/** Immutable round state; secret-bearing data stays inside the server. */
public final class WhoAmIState implements GameState {
    public enum Phase { SUBMIT_NAME, PLAYING, ROAST, REVEAL }
    public enum PlayerGameStatus { PLAYING, GOT_IT, GAVE_UP }
    public record PlayerResult(PlayerGameStatus status, long version) {
        public PlayerResult {
            Objects.requireNonNull(status);
            if (version < 0) throw new IllegalArgumentException("Invalid status version");
        }
    }

    private final List<UUID> participantIds;
    private final Map<UUID, SubmittedName> submissions;
    private final Map<UUID, WhoAmIAssignment> assignments;
    private final Phase phase;
    private final Map<UUID, PlayerResult> results;

    public WhoAmIState(List<UUID> participantIds) {
        this(participantIds, Map.of());
        if (participantIds.size() < 2 || participantIds.size() > 20
                || participantIds.stream().distinct().count() != participantIds.size())
            throw new IllegalArgumentException("Initial session requires 2 to 20 distinct participants");
    }

    private WhoAmIState(List<UUID> participantIds, Map<UUID, SubmittedName> submissions) {
        this(participantIds, submissions, Map.of(), Phase.SUBMIT_NAME, Map.of());
    }

    private WhoAmIState(List<UUID> participantIds, Map<UUID, SubmittedName> submissions,
                       Map<UUID, WhoAmIAssignment> assignments, Phase phase, Map<UUID, PlayerResult> results) {
        this.participantIds = List.copyOf(Objects.requireNonNull(participantIds));
        this.submissions = Map.copyOf(submissions);
        this.assignments = Map.copyOf(assignments);
        this.phase = phase;
        this.results = Map.copyOf(results);
    }

    public List<UUID> participantIds() { return participantIds; }
    public Map<UUID, SubmittedName> submissions() { return submissions; }
    public Map<UUID, WhoAmIAssignment> assignments() { return assignments; }
    public Map<UUID, PlayerResult> results() { return results; }

    public WhoAmIState submit(UUID playerId, String nickname, String text) {
        requireSubmissionPhase();
        requireParticipant(playerId);
        if (submissions.containsKey(playerId)) throw new DomainException(ALREADY_SUBMITTED);
        var updated = new HashMap<>(submissions);
        updated.put(playerId, new SubmittedName(UUID.randomUUID(), playerId, nickname, text));
        return new WhoAmIState(participantIds, updated);
    }

    public WhoAmIState reset(UUID playerId, UUID expectedSubmissionId) {
        requireSubmissionPhase();
        requireParticipant(playerId);
        var submission = submissions.get(playerId);
        if (submission == null) throw new DomainException(SUBMISSION_NOT_FOUND);
        if (!submission.id().equals(expectedSubmissionId)) throw new DomainException(STALE_COMMAND);
        var updated = new HashMap<>(submissions);
        updated.remove(playerId);
        return new WhoAmIState(participantIds, updated);
    }

    @Override public WhoAmIState withoutParticipant(UUID playerId) {
        requireParticipant(playerId);
        var updated = new HashMap<>(submissions);
        if (phase == Phase.SUBMIT_NAME) updated.remove(playerId);
        var remainingAssignments = new HashMap<>(assignments);
        remainingAssignments.remove(playerId);
        var remainingResults = new HashMap<>(results);
        remainingResults.remove(playerId);
        // Kicks may leave fewer than two participants; preserve the unfinished round.
        return new WhoAmIState(participantIds.stream().filter(id -> !id.equals(playerId)).toList(),
                updated, remainingAssignments, phase, remainingResults);
    }

    public boolean readyForShuffle(Set<UUID> connectedPlayers) {
        return phase == Phase.SUBMIT_NAME && participantIds.size() >= 2 && submissions.size() == participantIds.size()
                && connectedPlayers.containsAll(participantIds);
    }

    public WhoAmIState shuffle(Set<UUID> connectedPlayers, java.util.random.RandomGenerator random) {
        requireSubmissionPhase();
        if (participantIds.size() < 2) throw new DomainException(NOT_ENOUGH_PLAYERS);
        if (!readyForShuffle(connectedPlayers)) throw new DomainException(GAME_NOT_READY);
        // Sattolo's algorithm creates one random cycle in exactly N-1 swaps.
        // Every submission is used once and no position can retain its own submission.
        var owners = new java.util.ArrayList<>(participantIds);
        for (int i = owners.size() - 1; i > 0; i--)
            java.util.Collections.swap(owners, i, random.nextInt(i));
        var assigned = new HashMap<UUID, WhoAmIAssignment>();
        for (int i = 0; i < participantIds.size(); i++) {
            UUID recipient = participantIds.get(i);
            assigned.put(recipient, new WhoAmIAssignment(recipient, submissions.get(owners.get(i))));
        }
        var initialResults = new HashMap<UUID, PlayerResult>();
        participantIds.forEach(id -> initialResults.put(id, new PlayerResult(PlayerGameStatus.PLAYING, 0)));
        return new WhoAmIState(participantIds, submissions, assigned, Phase.PLAYING, initialResults);
    }

    public WhoAmIState markGotIt(UUID playerId) {
        return finish(playerId, PlayerGameStatus.GOT_IT);
    }

    public WhoAmIState giveUp(UUID playerId) {
        return finish(playerId, PlayerGameStatus.GAVE_UP);
    }

    private WhoAmIState finish(UUID playerId, PlayerGameStatus status) {
        PlayerResult result = playingResult(playerId);
        if (result.status() != PlayerGameStatus.PLAYING) throw new DomainException(INVALID_TRANSITION);
        return withResult(playerId, new PlayerResult(status, result.version() + 1));
    }

    public WhoAmIState resetStatus(UUID playerId, long expectedVersion) {
        PlayerResult result = playingResult(playerId);
        if (result.version() != expectedVersion) throw new DomainException(STALE_COMMAND);
        if (result.status() == PlayerGameStatus.PLAYING) throw new DomainException(INVALID_TRANSITION);
        return withResult(playerId, new PlayerResult(PlayerGameStatus.PLAYING, result.version() + 1));
    }

    private PlayerResult playingResult(UUID playerId) {
        if (phase != Phase.PLAYING) throw new DomainException(INVALID_GAME_PHASE);
        requireParticipant(playerId);
        return results.get(playerId);
    }

    private WhoAmIState withResult(UUID playerId, PlayerResult result) {
        var updated = new HashMap<>(results);
        updated.put(playerId, result);
        return new WhoAmIState(participantIds, submissions, assignments, phase, updated);
    }

    private void requireSubmissionPhase() {
        if (phase != Phase.SUBMIT_NAME) throw new DomainException(INVALID_GAME_PHASE);
    }

    private void requireParticipant(UUID playerId) {
        if (!participantIds.contains(playerId)) throw new DomainException(PLAYER_NOT_IN_GAME);
    }

    @Override public String toString() { return "WhoAmIState[redacted]"; }

    @Override public GameType gameType() { return GameType.WHO_AM_I; }
    public Phase phase() { return phase; }
}
