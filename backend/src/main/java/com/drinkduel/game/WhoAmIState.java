package com.drinkduel.game;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import com.drinkduel.room.DomainException;
import static com.drinkduel.room.DomainException.Code.*;

/** Immutable Submit Name state. Assignment and later phases are not implemented. */
public final class WhoAmIState implements GameState {
    public enum Phase { SUBMIT_NAME, PLAYING, ROAST, REVEAL }
    public enum PlayerGameStatus { PLAYING, GOT_IT, GAVE_UP }

    private final List<UUID> participantIds;
    private final Map<UUID, SubmittedName> submissions;

    public WhoAmIState(List<UUID> participantIds) {
        this(participantIds, Map.of());
        if (participantIds.size() < 2 || participantIds.size() > 20
                || participantIds.stream().distinct().count() != participantIds.size())
            throw new IllegalArgumentException("Initial session requires 2 to 20 distinct participants");
    }

    private WhoAmIState(List<UUID> participantIds, Map<UUID, SubmittedName> submissions) {
        this.participantIds = List.copyOf(Objects.requireNonNull(participantIds));
        this.submissions = Map.copyOf(submissions);
    }

    public List<UUID> participantIds() { return participantIds; }
    public Map<UUID, SubmittedName> submissions() { return submissions; }

    public WhoAmIState submit(UUID playerId, String nickname, String text) {
        requireParticipant(playerId);
        if (submissions.containsKey(playerId)) throw new DomainException(ALREADY_SUBMITTED);
        var updated = new HashMap<>(submissions);
        updated.put(playerId, new SubmittedName(UUID.randomUUID(), playerId, nickname, text));
        return new WhoAmIState(participantIds, updated);
    }

    public WhoAmIState reset(UUID playerId, UUID expectedSubmissionId) {
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
        updated.remove(playerId);
        // Kicks may leave fewer than two participants; preserve the unfinished round.
        return new WhoAmIState(participantIds.stream().filter(id -> !id.equals(playerId)).toList(), updated);
    }

    public boolean readyForShuffle(Set<UUID> connectedPlayers) {
        return participantIds.size() >= 2 && submissions.size() == participantIds.size()
                && connectedPlayers.containsAll(participantIds);
    }

    private void requireParticipant(UUID playerId) {
        if (!participantIds.contains(playerId)) throw new DomainException(PLAYER_NOT_IN_GAME);
    }

    @Override public String toString() { return "WhoAmIState[redacted]"; }

    @Override public GameType gameType() { return GameType.WHO_AM_I; }
    public Phase phase() { return Phase.SUBMIT_NAME; }
}
