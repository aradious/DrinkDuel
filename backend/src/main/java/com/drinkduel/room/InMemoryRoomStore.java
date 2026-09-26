package com.drinkduel.room;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public final class InMemoryRoomStore implements RoomStore {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final ConcurrentHashMap<String, Entry> rooms = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final AvatarCatalog avatars;

    private static final class Entry {
        private Room room;
        private boolean removed;
        private Entry(Room room) { this.room = room; }
    }

    public InMemoryRoomStore(Clock clock, AvatarCatalog avatars) {
        this.clock = Objects.requireNonNull(clock);
        this.avatars = Objects.requireNonNull(avatars);
    }

    @Override public Room create(PlayerIdentity.Google verifiedOwner, String nickname) {
        Player gm = Player.create(nickname, Objects.requireNonNull(verifiedOwner), avatars);
        while (true) {
            StringBuilder id = new StringBuilder(6);
            for (int i = 0; i < 6; i++) id.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            Room room = Room.create(id.toString(), clock.instant(), gm);
            if (rooms.putIfAbsent(room.id(), new Entry(room)) == null) return room;
        }
    }

    @Override public Optional<Room> find(String roomId) {
        Entry entry = rooms.get(Objects.requireNonNull(roomId));
        if (entry == null) return Optional.empty();
        synchronized (entry) {
            if (entry.removed) return Optional.empty();
            requireLive(roomId, entry);
            return Optional.of(entry.room);
        }
    }

    @Override public Room mutate(String roomId, UnaryOperator<Room> mutation) {
        Objects.requireNonNull(mutation);
        Entry entry = rooms.get(Objects.requireNonNull(roomId));
        if (entry == null) throw new DomainException(DomainException.Code.ROOM_NOT_FOUND);
        synchronized (entry) {
            requireLive(roomId, entry);
            Room before = entry.room;
            Room after = Objects.requireNonNull(mutation.apply(before));
            // Recheck the deadline after work; an operation cannot extend a room's life.
            requireLive(roomId, entry);
            if (!after.id().equals(before.id()) || !after.createdAt().equals(before.createdAt())
                    || !after.owner().equals(before.owner()) || !after.gmPlayerId().equals(before.gmPlayerId())
                    || after.revision() != before.revision())
                throw new DomainException(DomainException.Code.STALE_STATE);
            entry.room = after == before ? before : after.committed(before.revision() + 1);
            return entry.room;
        }
    }

    @Override public Optional<Removal> remove(String roomId) {
        Entry entry = rooms.get(Objects.requireNonNull(roomId));
        if (entry == null) return Optional.empty();
        synchronized (entry) {
            if (entry.removed) return Optional.empty();
            var reason = entry.room.isExpired(clock.instant()) ? RemovalReason.EXPIRED : RemovalReason.CLOSED;
            return Optional.of(delete(roomId, entry, reason));
        }
    }

    @Override public List<Removal> expireRooms() {
        var expired = new ArrayList<Removal>();
        rooms.forEach((id, entry) -> {
            synchronized (entry) {
                if (!entry.removed && entry.room.isExpired(clock.instant()))
                    expired.add(delete(id, entry, RemovalReason.EXPIRED));
            }
        });
        return List.copyOf(expired);
    }

    private void requireLive(String id, Entry entry) {
        if (entry.removed) throw new DomainException(DomainException.Code.ROOM_NOT_FOUND);
        if (entry.room.isExpired(clock.instant())) {
            delete(id, entry, RemovalReason.EXPIRED);
            throw new DomainException(DomainException.Code.ROOM_EXPIRED);
        }
    }

    private Removal delete(String id, Entry entry, RemovalReason reason) {
        entry.removed = true;
        rooms.remove(id, entry);
        return new Removal(entry.room, reason);
    }
}
