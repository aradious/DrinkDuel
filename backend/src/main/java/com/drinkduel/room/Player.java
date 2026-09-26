package com.drinkduel.room;

import java.util.Objects;
import java.util.UUID;

public record Player(UUID id, String nickname, int avatarId,
                     PlayerIdentity identity, ConnectionState connectionState) {
    public Player {
        Objects.requireNonNull(id);
        DomainException.requireText(nickname);
        if (!AvatarCatalog.contains(avatarId)) throw new DomainException(DomainException.Code.INVALID_INPUT);
        Objects.requireNonNull(identity);
        Objects.requireNonNull(connectionState);
    }

    public static Player create(String nickname, PlayerIdentity identity, AvatarCatalog avatars) {
        return new Player(UUID.randomUUID(), nickname, avatars.randomAvatarId(), identity,
                ConnectionState.CONNECTED);
    }

    /** Transport will derive this from its live connection registry, not individual socket closes. */
    Player withConnectionState(ConnectionState state) {
        return new Player(id, nickname, avatarId, identity, state);
    }
}
