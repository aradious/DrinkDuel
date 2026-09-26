package com.drinkduel.game;

import com.drinkduel.room.DomainException;
import java.util.Objects;
import java.util.UUID;

/** Server-only round data. The ID also guards resets against an older submission. */
public record SubmittedName(UUID id, UUID submitterPlayerId, String submitterNickname, String text) {
    public SubmittedName {
        Objects.requireNonNull(id);
        Objects.requireNonNull(submitterPlayerId);
        Objects.requireNonNull(submitterNickname);
        if (text == null || text.isBlank()) throw new DomainException(DomainException.Code.INVALID_SUBMISSION);
    }
    @Override public String toString() { return "SubmittedName[redacted]"; }
}
