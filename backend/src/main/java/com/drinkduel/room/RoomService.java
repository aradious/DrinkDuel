package com.drinkduel.room;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Internal application boundary. Returned snapshots must never be serialized directly to clients.
 * Google identities must come from a verified authentication adapter, not client-supplied subjects.
 */
@Service
public final class RoomService {
    private final RoomStore store;
    private final AvatarCatalog avatars;

    public RoomService(RoomStore store, AvatarCatalog avatars) {
        this.store = Objects.requireNonNull(store);
        this.avatars = Objects.requireNonNull(avatars);
    }

    public Room createRoom(PlayerIdentity.Google verifiedOwner, String nickname) {
        return store.create(verifiedOwner, nickname);
    }

    public Room joinRoom(String roomId, String nickname, GuestToken token) {
        var identity = Objects.requireNonNull(token).identity();
        return store.mutate(roomId, room -> room.joinGuest(nickname, identity, avatars));
    }

    public Room reconnectPlayer(String roomId, GuestToken token) {
        return presence(roomId, Objects.requireNonNull(token).identity(), ConnectionState.CONNECTED);
    }

    public Room reconnectGm(String roomId, PlayerIdentity.Google verifiedOwner) {
        return presence(roomId, Objects.requireNonNull(verifiedOwner), ConnectionState.CONNECTED);
    }

    /** The future connection registry calls this only when no validated connections remain. */
    public Room disconnectPlayer(String roomId, PlayerIdentity authenticatedIdentity) {
        return presence(roomId, Objects.requireNonNull(authenticatedIdentity), ConnectionState.DISCONNECTED);
    }

    public Room leaveRoom(String roomId, PlayerIdentity authenticatedIdentity) {
        Objects.requireNonNull(authenticatedIdentity);
        return store.mutate(roomId, room -> room.leave(authenticatedIdentity));
    }

    public Room kickPlayer(String roomId, PlayerIdentity authenticatedActor, UUID targetId) {
        Objects.requireNonNull(authenticatedActor);
        Objects.requireNonNull(targetId);
        return store.mutate(roomId, room -> room.kick(authenticatedActor, targetId));
    }

    private Room presence(String roomId, PlayerIdentity identity, ConnectionState state) {
        return store.mutate(roomId, room -> room.updateConnection(identity, state));
    }
}
