package com.drinkduel.room;

import static org.junit.jupiter.api.Assertions.*;
import static com.drinkduel.room.DomainException.Code.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class RoomServiceTests {
    private RoomStoreTests.MutableClock clock;
    private InMemoryRoomStore store;
    private RoomService service;
    private Room room;
    private final AtomicInteger draws = new AtomicInteger();

    @BeforeEach void setup() {
        clock = new RoomStoreTests.MutableClock(Instant.parse("2026-09-26T00:00:00Z"));
        var avatars = new AvatarCatalog(new RandomGenerator() {
            public long nextLong() { throw new AssertionError(); }
            public int nextInt(int bound) { return draws.getAndIncrement() % bound; }
        });
        store = new InMemoryRoomStore(clock, avatars);
        service = new RoomService(store, avatars);
        room = service.createRoom(new PlayerIdentity.Google("owner"), "GM");
    }

    private Room current() { return store.find(room.id()).orElseThrow(); }
    private Player join(GuestToken token, String name) {
        return service.joinRoom(room.id(), name, token).playerFor(token.identity());
    }
    private void error(DomainException.Code code, Executable action) {
        assertEquals(code, assertThrows(DomainException.class, action).code());
    }

    @Test void createsLobbyWithGmAndFixedLifetime() {
        assertTrue(room.isLobby());
        assertTrue(room.id().matches("[A-HJ-NP-Z2-9]{6}"));
        assertEquals(clock.instant().plus(Duration.ofHours(48)), room.expiresAt());
        assertEquals(1, room.players().size());
        assertEquals(room.gmPlayerId(), room.playerFor(room.owner()).id());
        assertTrue(AvatarCatalog.contains(room.playerFor(room.owner()).avatarId()));
    }

    @Test void retriesRoomIdCollisionWithoutReplacingExistingRoomOrRedrawingAvatar() {
        var calls = new AtomicInteger();
        var avatars = new AvatarCatalog();
        var collisionStore = new InMemoryRoomStore(clock, avatars, new RandomGenerator() {
            public long nextLong() { throw new AssertionError(); }
            public int nextInt(int bound) { return calls.getAndIncrement() < 12 ? 0 : 1; }
        });
        var first = collisionStore.create(room.owner(), "First");
        var second = collisionStore.create(new PlayerIdentity.Google("second"), "Second");
        assertEquals("AAAAAA", first.id());
        assertEquals("BBBBBB", second.id());
        assertEquals(18, calls.get());
        assertSame(first, collisionStore.find(first.id()).orElseThrow());
        assertSame(second, collisionStore.find(second.id()).orElseThrow());
    }

    @Test void joinValidatesNicknameIdentityCapacityBeforeAvatarDraw() {
        var token = GuestToken.generate();
        var player = join(token, "Ken");
        assertTrue(AvatarCatalog.contains(player.avatarId()));
        assertNotEquals(room.gmPlayerId(), player.id());
        error(NICKNAME_TAKEN, () -> join(GuestToken.generate(), "Ken"));
        error(NICKNAME_TAKEN, () -> join(GuestToken.generate(), "GM"));
        error(IDENTITY_ALREADY_JOINED, () -> join(token, "Another nickname"));
        error(INVALID_INPUT, () -> join(GuestToken.generate(), " "));
        assertEquals(2, draws.get());
        for (int i = 2; i < 20; i++) join(GuestToken.generate(), "P" + i);
        service.disconnectPlayer(room.id(), token.identity());
        error(ROOM_FULL, () -> join(GuestToken.generate(), "Overflow"));
        assertEquals(20, draws.get());
        assertEquals(20, current().players().size());
    }

    @Test void disconnectAndReconnectPreserveIdentityNicknameAvatarAndOrder() {
        var token = GuestToken.generate();
        var player = join(token, "Ken");
        var disconnected = service.disconnectPlayer(room.id(), token.identity());
        assertEquals(2, disconnected.players().size());
        assertEquals(ConnectionState.DISCONNECTED, disconnected.playerFor(token.identity()).connectionState());
        var back = service.reconnectPlayer(room.id(), GuestToken.parse(token.value()));
        assertEquals(player, back.playerFor(token.identity()));
        assertEquals(List.of(room.gmPlayerId(), player.id()), back.players().stream().map(Player::id).toList());
        assertSame(back, service.reconnectPlayer(room.id(), token));
        error(NOT_AUTHORIZED, () -> service.reconnectPlayer(room.id(), GuestToken.generate()));
        assertEquals(2, draws.get());
    }

    @Test void leaveRemovesBindingAndRejoinCreatesNewPlayerWithNewDraw() {
        var token = GuestToken.generate();
        var before = join(token, "Ken");
        service.leaveRoom(room.id(), token.identity());
        assertEquals(1, current().players().size());
        error(NOT_AUTHORIZED, () -> service.reconnectPlayer(room.id(), token));
        var after = join(token, "Ken");
        assertNotEquals(before.id(), after.id());
        assertEquals(3, draws.get());
        error(GM_CANNOT_LEAVE, () -> service.leaveRoom(room.id(), room.owner()));
    }

    @Test void kickRequiresConnectedOwnerAndCannotTargetGmOrMissingPlayer() {
        var token = GuestToken.generate();
        var player = join(token, "Ken");
        error(NOT_AUTHORIZED, () -> service.kickPlayer(room.id(), token.identity(), player.id()));
        error(NOT_AUTHORIZED, () -> service.kickPlayer(room.id(), new PlayerIdentity.Google("wrong"), player.id()));
        error(GM_CANNOT_KICK_SELF, () -> service.kickPlayer(room.id(), room.owner(), room.gmPlayerId()));
        error(PLAYER_NOT_FOUND, () -> service.kickPlayer(room.id(), room.owner(), UUID.randomUUID()));
        service.disconnectPlayer(room.id(), room.owner());
        error(NOT_AUTHORIZED, () -> service.kickPlayer(room.id(), room.owner(), player.id()));
        assertEquals(2, current().players().size());
    }

    @Test void kickBlocksReconnectAndRejoinOnlyInThatRoom() {
        var token = GuestToken.generate();
        var player = join(token, "Ken");
        Room before = current();
        service.kickPlayer(room.id(), room.owner(), player.id());
        assertEquals(2, before.players().size());
        assertEquals(1, current().players().size());
        error(PLAYER_KICKED, () -> service.reconnectPlayer(room.id(), token));
        error(PLAYER_KICKED, () -> join(token, "New nickname"));
        var other = service.createRoom(room.owner(), "GM");
        assertEquals(2, service.joinRoom(other.id(), "Ken", token).players().size());
    }

    @Test void gmDisconnectPreservesOwnershipAndOnlySameOwnerCanResume() {
        service.disconnectPlayer(room.id(), room.owner());
        assertEquals(room.owner(), current().owner());
        assertEquals(ConnectionState.DISCONNECTED, current().playerFor(room.owner()).connectionState());
        error(NOT_AUTHORIZED, () -> service.reconnectGm(room.id(), new PlayerIdentity.Google("wrong")));
        var back = service.reconnectGm(room.id(), new PlayerIdentity.Google("owner"));
        assertEquals(room.players(), back.players());
        assertEquals(1, draws.get());
        assertEquals(room.expiresAt(), back.expiresAt());
    }

    @Test void operationsEnforceMissingAndFixedExpiredRoom() {
        var token = GuestToken.generate();
        error(ROOM_NOT_FOUND, () -> service.joinRoom("ABSENT", "Ken", token));
        join(token, "Ken");
        clock.advance(Duration.ofHours(47));
        service.reconnectPlayer(room.id(), token);
        clock.advance(Duration.ofHours(1));
        error(ROOM_EXPIRED, () -> service.reconnectPlayer(room.id(), token));
        error(ROOM_NOT_FOUND, () -> service.joinRoom(room.id(), "Ken", token));
    }

    @Test void concurrentSameNicknameJoinsCreateExactlyOnePlayerAndDraw() throws Exception {
        var actions = new ArrayList<Runnable>();
        for (int i = 0; i < 24; i++) actions.add(() -> {
            try { join(GuestToken.generate(), "Ken"); }
            catch (DomainException ex) { assertEquals(NICKNAME_TAKEN, ex.code()); }
        });
        together(actions);
        assertEquals(2, current().players().size());
        assertEquals(2, draws.get());
    }

    @Test void concurrentJoinsNearCapacityNeverExceedTwenty() throws Exception {
        for (int i = 1; i < 19; i++) join(GuestToken.generate(), "P" + i);
        var actions = new ArrayList<Runnable>();
        for (int i = 0; i < 24; i++) {
            String name = "Racer" + i;
            actions.add(() -> {
                try { join(GuestToken.generate(), name); }
                catch (DomainException ex) { assertEquals(ROOM_FULL, ex.code()); }
            });
        }
        together(actions);
        assertEquals(20, current().players().size());
        assertEquals(20, current().players().stream().map(Player::id).distinct().count());
        assertEquals(20, draws.get());
    }

    @Test void reconnectRacingDuplicateJoinKeepsExistingPlayer() throws Exception {
        var token = GuestToken.generate();
        var player = join(token, "Ken");
        service.disconnectPlayer(room.id(), token.identity());
        together(List.of(() -> service.reconnectPlayer(room.id(), token),
                () -> error(IDENTITY_ALREADY_JOINED, () -> join(token, "Renamed"))));
        assertEquals(player, current().playerFor(token.identity()));
        assertEquals(2, current().players().size());
        assertEquals(2, draws.get());
    }

    @Test void kickRacingReconnectCannotRestoreKickedPlayer() throws Exception {
        for (int i = 0; i < 30; i++) {
            var token = GuestToken.generate();
            var player = join(token, "Ken");
            service.disconnectPlayer(room.id(), token.identity());
            together(List.of(() -> service.kickPlayer(room.id(), room.owner(), player.id()), () -> {
                try { service.reconnectPlayer(room.id(), token); }
                catch (DomainException ex) { assertEquals(PLAYER_KICKED, ex.code()); }
            }));
            assertEquals(1, current().players().size());
            error(PLAYER_KICKED, () -> service.reconnectPlayer(room.id(), token));
        }
    }

    private void together(List<Runnable> actions) throws Exception {
        var start = new CountDownLatch(1);
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
}
