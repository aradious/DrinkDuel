package com.drinkduel.game;

import java.util.Objects;
import java.util.UUID;

/** References the immutable submission, preserving attribution even after its creator is kicked. */
public record WhoAmIAssignment(UUID playerId, SubmittedName submission) {
    public WhoAmIAssignment {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(submission);
        if (playerId.equals(submission.submitterPlayerId()))
            throw new IllegalArgumentException("Self-assignment is invalid");
    }
    @Override public String toString() { return "WhoAmIAssignment[redacted]"; }
}
