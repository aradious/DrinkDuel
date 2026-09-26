package com.drinkduel.room;

import static org.junit.jupiter.api.Assertions.*;
import com.drinkduel.game.GameSession;
import com.drinkduel.game.GameType;
import com.drinkduel.game.WhoAmIState;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoomStoreTests {
    private MutableClock clock;
    private AvatarCatalog avatars;
    private InMemoryRoomStore store;
    private Room room;

    @BeforeEach void setup() {
        clock = new MutableClock(Instant.parse("2026-09-26T00:00:00Z"));
        avatars = new AvatarCatalog();
        store = new InMemoryRoomStore(clock, avatars);
        room = store.create(new PlayerIdentity.Google("verified-google-subject"), "GM");
    }

    private Player guest(String nickname) {
        return Player.create(nickname, GuestToken.generate().identity(), avatars);
    }

    private Room add(Player player) {
        return store.mutate(room.id(), current -> current.addPlayer(player));
    }

    private void assertCode(DomainException.Code code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(DomainException.class, action).code());
    }

    @Test void createsLobbyWithOwnerAndFixedDeadline() {
        assertTrue(room.id().matches("[A-HJ-NP-Z2-9]{6}"));
        assertEquals(clock.instant(), room.createdAt());
        assertEquals(clock.instant().plus(Duration.ofHours(48)), room.expiresAt());
        assertTrue(room.isLobby());
        assertTrue(room.currentSession().isEmpty());
        assertEquals(1, room.players().size());
        assertEquals(room.gmPlayerId(), room.players().getFirst().id());
        assertEquals(room.owner(), room.players().getFirst().identity());
        assertEquals(0, room.revision());
        assertSame(room, store.find(room.id()).orElseThrow());
        assertTrue(store.find("ABSENT").isEmpty());
    }

    @Test void removesRoomWithoutAllowingResurrection() {
        var removal = store.remove(room.id()).orElseThrow();
        assertEquals(RoomStore.RemovalReason.CLOSED, removal.reason());
        assertTrue(store.find(room.id()).isEmpty());
        assertTrue(store.remove(room.id()).isEmpty());
        assertCode(DomainException.Code.ROOM_NOT_FOUND, () -> store.mutate(room.id(), old -> room));
    }

    @Test void deadlineIsFixedDespiteActivityAndExactBoundaryExpires() {
        clock.advance(Duration.ofHours(47));
        Player player = guest("Ken");
        Room updated = add(player);
        store.mutate(room.id(), r -> r.updateConnection(player.identity(), ConnectionState.DISCONNECTED));
        store.mutate(room.id(), r -> r.updateConnection(player.identity(), ConnectionState.CONNECTED));
        assertEquals(room.expiresAt(), updated.expiresAt());
        clock.advance(Duration.ofHours(1).minusNanos(1));
        assertTrue(store.find(room.id()).isPresent());
        clock.advance(Duration.ofNanos(1));
        assertCode(DomainException.Code.ROOM_EXPIRED, () -> store.find(room.id()));
        assertTrue(store.find(room.id()).isEmpty());
    }

    @Test void expiredMutationNeverRunsAndCleanupIsIdempotent() {
        clock.advance(Duration.ofHours(48));
        AtomicInteger calls = new AtomicInteger();
        assertCode(DomainException.Code.ROOM_EXPIRED, () -> store.mutate(room.id(), r -> {
            calls.incrementAndGet();
            return r;
        }));
        assertEquals(0, calls.get());
        assertTrue(store.expireRooms().isEmpty());
    }

    @Test void cleanupRemovesOnlyExpiredRoomsAndReportsReasonOnce() {
        clock.advance(Duration.ofHours(24));
        Room newer = store.create(new PlayerIdentity.Google("other-owner"), "Other GM");
        clock.advance(Duration.ofHours(24));
        var expired = store.expireRooms();
        assertEquals(1, expired.size());
        assertEquals(room.id(), expired.getFirst().room().id());
        assertEquals(RoomStore.RemovalReason.EXPIRED, expired.getFirst().reason());
        assertTrue(store.find(room.id()).isEmpty());
        assertTrue(store.find(newer.id()).isPresent());
        assertTrue(store.expireRooms().isEmpty());
    }

    @Test void removalAtDeadlineIsExpirationNotManualClosure() {
        clock.advance(Duration.ofHours(48));
        assertEquals(RoomStore.RemovalReason.EXPIRED, store.remove(room.id()).orElseThrow().reason());
    }

    @Test void callbackCrossingDeadlineCannotCommit() {
        Player player = guest("Late");
        assertCode(DomainException.Code.ROOM_EXPIRED, () -> store.mutate(room.id(), r -> {
            clock.advance(Duration.ofHours(48));
            return r.addPlayer(player);
        }));
        assertTrue(store.find(room.id()).isEmpty());
    }

    @Test void capacityIncludesGmAndDisconnectedMembers() {
        for (int i = 1; i < 20; i++) add(guest("Player " + i));
        var current = store.find(room.id()).orElseThrow();
        var player = current.players().getLast();
        store.mutate(room.id(), r -> r.updateConnection(player.identity(), ConnectionState.DISCONNECTED));
        assertCode(DomainException.Code.ROOM_FULL, () -> add(guest("Overflow")));
        assertEquals(20, store.find(room.id()).orElseThrow().players().size());
    }

    @Test void rejectsDuplicateNicknameAndDuplicateIdentityWithoutChangingState() {
        Player ken = guest("Ken");
        add(ken);
        assertCode(DomainException.Code.NICKNAME_TAKEN, () -> add(guest("Ken")));
        assertCode(DomainException.Code.NICKNAME_TAKEN, () -> add(guest("GM")));
        Player renamed = Player.create("Different nickname", ken.identity(), avatars);
        assertCode(DomainException.Code.IDENTITY_ALREADY_JOINED, () -> add(renamed));
        assertEquals(2, store.find(room.id()).orElseThrow().players().size());
        assertEquals(1, store.find(room.id()).orElseThrow().revision());
    }

    @Test void identityDoesNotDependOnNickname() {
        Player first = guest("Same name");
        Player second = guest("Same name");
        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.identity(), second.identity());
        add(first);
        assertCode(DomainException.Code.NOT_AUTHORIZED,
                () -> store.find(room.id()).orElseThrow().playerFor(second.identity()));
    }

    @Test void reconnectPreservesPlayerAvatarOrderAndOwner() {
        Player ken = guest("Ken");
        add(ken);
        Room disconnected = store.mutate(room.id(), r -> r.updateConnection(ken.identity(), ConnectionState.DISCONNECTED));
        assertEquals(ConnectionState.DISCONNECTED, disconnected.playerFor(ken.identity()).connectionState());
        Room reconnected = store.mutate(room.id(), r -> r.updateConnection(ken.identity(), ConnectionState.CONNECTED));
        assertEquals(ken, reconnected.playerFor(ken.identity()));
        assertEquals(List.of(room.gmPlayerId(), ken.id()), reconnected.players().stream().map(Player::id).toList());
        store.mutate(room.id(), r -> r.updateConnection(room.owner(), ConnectionState.DISCONNECTED));
        Room gmBack = store.mutate(room.id(), r -> r.updateConnection(room.owner(), ConnectionState.CONNECTED));
        assertEquals(room.players().getFirst(), gmBack.playerFor(room.owner()));
        assertEquals(room.owner(), gmBack.owner());
    }

    @Test void minimumBelongsToSessionEligibilityNotLobbyCreation() {
        assertCode(DomainException.Code.NOT_ENOUGH_PLAYERS, room::requireSessionStartAllowed);
        Player ken = guest("Ken");
        Room ready = add(ken);
        assertDoesNotThrow(ready::requireSessionStartAllowed);
        Room absent = store.mutate(room.id(), r -> r.updateConnection(ken.identity(), ConnectionState.DISCONNECTED));
        assertCode(DomainException.Code.NOT_ENOUGH_PLAYERS, absent::requireSessionStartAllowed);
    }

    @Test void snapshotsAreImmutableAndFailedCallbacksDoNotCommit() {
        Player ken = guest("Ken");
        assertThrows(UnsupportedOperationException.class, () -> room.players().clear());
        assertThrows(IllegalStateException.class, () -> store.mutate(room.id(), r -> {
            r.addPlayer(ken);
            throw new IllegalStateException("abort");
        }));
        assertSame(room, store.find(room.id()).orElseThrow());
        Room updated = add(ken);
        assertEquals(1, room.players().size());
        assertEquals(2, updated.players().size());
        assertCode(DomainException.Code.STALE_STATE, () -> store.mutate(room.id(), r -> room));
        assertSame(updated, store.find(room.id()).orElseThrow());
    }

    @Test void sessionFoundationIsImmutableAndSeparateFromRoom() {
        var ids = new ArrayList<>(List.of(UUID.randomUUID(), UUID.randomUUID()));
        var state = new WhoAmIState(ids);
        var session = new GameSession(UUID.randomUUID(), state);
        ids.clear();
        assertEquals(2, state.participantIds().size());
        assertThrows(UnsupportedOperationException.class, () -> state.participantIds().clear());
        assertEquals(GameType.WHO_AM_I, session.gameType());
        assertEquals(WhoAmIState.Phase.SUBMIT_NAME, state.phase());
        assertThrows(IllegalArgumentException.class, () -> new WhoAmIState(List.of(UUID.randomUUID())));
        assertTrue(room.currentSession().isEmpty());
    }

    @Test void concurrentJoinsRespectCapacityWithoutLostUpdates() throws Exception {
        List<Player> candidates = new ArrayList<>();
        for (int i = 0; i < 60; i++) candidates.add(guest("Concurrent " + i));
        AtomicInteger accepted = new AtomicInteger();
        runTogether(candidates.stream().<Runnable>map(p -> () -> {
            try {
                add(p);
                accepted.incrementAndGet();
            } catch (DomainException exception) {
                assertEquals(DomainException.Code.ROOM_FULL, exception.code());
            }
        }).toList());
        Room result = store.find(room.id()).orElseThrow();
        assertEquals(19, accepted.get());
        assertEquals(20, result.players().size());
        assertEquals(20, result.players().stream().map(Player::id).distinct().count());
        assertEquals(19, result.revision());
    }

    @Test void concurrentDuplicateNicknamesAllowExactlyOneJoin() throws Exception {
        List<Player> candidates = new ArrayList<>();
        for (int i = 0; i < 20; i++) candidates.add(guest("Same nickname"));
        AtomicInteger accepted = new AtomicInteger();
        runTogether(candidates.stream().<Runnable>map(p -> () -> {
            try {
                add(p);
                accepted.incrementAndGet();
            } catch (DomainException exception) {
                assertEquals(DomainException.Code.NICKNAME_TAKEN, exception.code());
            }
        }).toList());
        assertEquals(1, accepted.get());
        assertEquals(2, store.find(room.id()).orElseThrow().players().size());
    }

    @Test void concurrentRemovalAndMutationCannotReviveRoom() throws Exception {
        List<Runnable> actions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Player player = guest("Race " + i);
            actions.add(() -> {
                try { add(player); }
                catch (DomainException exception) { assertEquals(DomainException.Code.ROOM_NOT_FOUND, exception.code()); }
            });
        }
        actions.add(() -> store.remove(room.id()));
        runTogether(actions);
        assertTrue(store.find(room.id()).isEmpty());
    }

    private void runTogether(List<Runnable> actions) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = actions.stream().map(action -> executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                action.run();
                return null;
            })).toList();
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
