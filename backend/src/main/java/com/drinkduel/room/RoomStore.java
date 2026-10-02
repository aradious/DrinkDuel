package com.drinkduel.room;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.function.Function;

/** Internal storage boundary. Callers handle authentication; never expose aggregates as API responses. */
public interface RoomStore {
    Room create(PlayerIdentity.Owner verifiedOwner, String nickname);
    Optional<Room> find(String roomId);

    /**
     * Atomically transform the current immutable aggregate. Callback must be short, pure,
     * non-blocking, and must not re-enter the store. A failed callback commits nothing.
     */
    Room mutate(String roomId, UnaryOperator<Room> mutation);

    /** Coordinate attachment/outputs with mutations using the existing room lock.
     * The callback may call services/store operations for THIS room only. No network I/O.
     * This is a serialization boundary, not a rollback transaction.
     */
    <T> T inRoom(String roomId, Function<Room, T> operation);

    /** Internal notifications under the room lock; listeners only enqueue immutable outputs.
     * Listeners must not block, throw, or re-enter the store.
     */
    void addListener(Listener listener);
    interface Listener {
        void changed(Room room);
        void removed(Removal removal);
    }

    Optional<Removal> remove(String roomId);
    List<Removal> expireRooms();

    enum RemovalReason { CLOSED, EXPIRED }
    record Removal(Room room, RemovalReason reason) {}
}
