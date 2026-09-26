package com.drinkduel.room;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Internal storage boundary. Callers handle authentication; never expose aggregates as API responses. */
public interface RoomStore {
    Room create(PlayerIdentity.Google verifiedOwner, String nickname);
    Optional<Room> find(String roomId);

    /**
     * Atomically transform the current immutable aggregate. Callback must be short, pure,
     * non-blocking, and must not re-enter the store. A failed callback commits nothing.
     */
    Room mutate(String roomId, UnaryOperator<Room> mutation);

    Optional<Removal> remove(String roomId);
    List<Removal> expireRooms();

    enum RemovalReason { CLOSED, EXPIRED }
    record Removal(Room room, RemovalReason reason) {}
}
