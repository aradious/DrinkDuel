package com.drinkduel.game;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Initial state only. Submissions, assignments and gameplay transitions are deferred. */
public record WhoAmIState(List<UUID> participantIds) implements GameState {
    public enum Phase { SUBMIT_NAME, PLAYING, ROAST, REVEAL }
    public enum PlayerGameStatus { PLAYING, GOT_IT, GAVE_UP }

    public WhoAmIState {
        participantIds = List.copyOf(Objects.requireNonNull(participantIds));
        if (participantIds.size() < 2 || participantIds.size() > 20
                || participantIds.stream().distinct().count() != participantIds.size())
            throw new IllegalArgumentException("Initial session requires 2 to 20 distinct participants");
    }

    @Override public GameType gameType() { return GameType.WHO_AM_I; }
    public Phase phase() { return Phase.SUBMIT_NAME; }
}
