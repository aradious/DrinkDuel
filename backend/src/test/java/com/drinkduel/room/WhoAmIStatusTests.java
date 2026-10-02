package com.drinkduel.room;

import com.drinkduel.game.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.drinkduel.room.DomainException.Code.*;
import static com.drinkduel.game.WhoAmIState.PlayerGameStatus.*;

class WhoAmIStatusTests {
    RoomStore store; RoomService service; Room room; GuestToken guest; UUID player;
    @BeforeEach void setup() {
        var avatars = new AvatarCatalog();
        store = new InMemoryRoomStore(Clock.systemUTC(), avatars);
        service = new RoomService(store, avatars);
        room = service.createRoom(new PlayerIdentity.Google("status-owner"), "GM");
        guest = GuestToken.generate();
        player = service.joinRoom(room.id(), "Ken", guest).playerFor(guest.identity()).id();
        service.startWhoAmI(room.id(), room.owner());
        service.submitName(room.id(), room.owner(), session(), "GM-SECRET");
        service.submitName(room.id(), guest.identity(), session(), "KEN-SECRET");
        service.shuffle(room.id(), room.owner(), session());
    }
    Room current() { return store.find(room.id()).orElseThrow(); }
    UUID session() { return current().currentSession().orElseThrow().id(); }
    WhoAmIState state() { return (WhoAmIState) current().currentSession().orElseThrow().state(); }
    void got(UUID id) { service.markGotIt(room.id(), room.owner(), session(), id); }
    void reset(UUID id) { service.resetPlayerStatus(room.id(), room.owner(), session(), id, state().results().get(id).version()); }
    void error(DomainException.Code code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(DomainException.class, action).code());
    }
    void sameSecrets(WhoAmIState before) {
        assertEquals(before.participantIds(), state().participantIds());
        assertEquals(before.assignments(), state().assignments());
        assertEquals(before.submissions(), state().submissions());
        assertEquals(WhoAmIState.Phase.PLAYING, state().phase());
    }

    @Test void gmCanMarkOtherAndSelfWithoutEndingRound() {
        var before = state(); got(player); got(room.gmPlayerId());
        assertTrue(state().results().values().stream().allMatch(r -> r.status() == GOT_IT && r.version() == 1));
        sameSecrets(before);
    }
    @Test void onlyGmMayMarkOrReset() {
        var before = current();
        error(NOT_AUTHORIZED, () -> service.markGotIt(room.id(), guest.identity(), session(), player));
        error(NOT_AUTHORIZED, () -> service.resetPlayerStatus(room.id(), guest.identity(), session(), player, 0));
        error(NOT_AUTHORIZED, () -> service.markGotIt(room.id(), new PlayerIdentity.Google("forged"), session(), player));
        assertSame(before, current());
    }
    @Test void duplicateGotItIsRejectedAndResetRestoresPlaying() {
        var before = state(); got(player);
        error(INVALID_TRANSITION, () -> got(player));
        reset(player);
        assertEquals(PLAYING, state().results().get(player).status());
        assertEquals(2, state().results().get(player).version());
        error(INVALID_TRANSITION, () -> reset(player));
        sameSecrets(before);
    }
    @Test void delayedResetCannotEraseLaterResultEvenWhenStatusRepeats() {
        got(player); long first = state().results().get(player).version(); reset(player); got(player);
        var before = current();
        error(STALE_COMMAND, () -> service.resetPlayerStatus(room.id(), room.owner(), session(), player, first));
        assertSame(before, current());
        assertEquals(3, state().results().get(player).version());
    }
    @Test void phaseSessionAndMembershipPreconditionsProtectOperations() {
        error(STALE_COMMAND, () -> service.markGotIt(room.id(), room.owner(), UUID.randomUUID(), player));
        error(STALE_COMMAND, () -> service.resetPlayerStatus(room.id(), room.owner(), UUID.randomUUID(), player, 0));
        error(PLAYER_NOT_IN_GAME, () -> got(UUID.randomUUID()));
        error(PLAYER_NOT_IN_GAME, () -> service.resetPlayerStatus(room.id(), room.owner(), session(), UUID.randomUUID(), 0));
        var lobby = service.createRoom(new PlayerIdentity.Google("other"), "Other");
        error(INVALID_GAME_PHASE, () -> service.markGotIt(lobby.id(), lobby.owner(), UUID.randomUUID(), lobby.gmPlayerId()));
    }
    @Test void disconnectAndReconnectPreserveResultsAndBlockGmActionsWhileOffline() {
        var before = current().players(); got(room.gmPlayerId());
        service.disconnectPlayer(room.id(), room.owner());
        error(NOT_AUTHORIZED, () -> got(player));
        error(NOT_AUTHORIZED, () -> reset(room.gmPlayerId()));
        var game = state();
        service.disconnectPlayer(room.id(), guest.identity());
        assertSame(game, state());
        service.reconnectPlayer(room.id(), guest); service.reconnectGm(room.id(), room.owner());
        assertSame(game, state()); assertEquals(before, current().players());
    }
    @ParameterizedTest @EnumSource(WhoAmIState.PlayerGameStatus.class)
    void kickRemovesResultAndAssignmentButRetainsAttribution(WhoAmIState.PlayerGameStatus status) {
        if (status == GOT_IT) got(player);
        var retained = state().assignments().get(room.gmPlayerId());
        service.kickPlayer(room.id(), room.owner(), player);
        assertFalse(state().results().containsKey(player));
        assertFalse(state().assignments().containsKey(player));
        assertSame(retained, state().assignments().get(room.gmPlayerId()));
        assertEquals("Ken", retained.submission().submitterNickname());
        error(PLAYER_NOT_IN_GAME, () -> got(player));
    }
    @Test void concurrentDuplicateGotItAcceptsExactlyOne() throws Exception {
        var accepted = new AtomicInteger(); var actions = new ArrayList<Runnable>();
        for (int i = 0; i < 10; i++) actions.add(() -> finish(() -> got(player), accepted));
        together(actions);
        assertEquals(1, accepted.get());
        assertEquals(GOT_IT, state().results().get(player).status());
        assertEquals(1, state().results().get(player).version());
    }
    @Test void kickRacingStatusLeavesNoOrphanResult() throws Exception {
        var retained = state().assignments().get(room.gmPlayerId());
        together(List.of(() -> service.kickPlayer(room.id(), room.owner(), player), () -> {
            try { got(player); } catch (DomainException e) { assertEquals(PLAYER_NOT_IN_GAME, e.code()); }
        }));
        assertFalse(state().results().containsKey(player));
        assertSame(retained, state().assignments().get(room.gmPlayerId()));
    }
    @Test void freshRoundHasOnlyPlayingResults() {
        var ids = state().participantIds(); var fresh = new WhoAmIState(ids);
        for (UUID id : ids) fresh = fresh.submit(id, "Name", "Secret");
        fresh = fresh.shuffle(Set.copyOf(ids), new Random(5));
        assertTrue(fresh.results().values().stream().allMatch(r -> r.status() == PLAYING && r.version() == 0));
    }
    void finish(Runnable action, AtomicInteger accepted) {
        try { action.run(); accepted.incrementAndGet(); }
        catch (DomainException e) { assertEquals(INVALID_TRANSITION, e.code()); }
    }
    void together(List<Runnable> actions) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = actions.stream().map(action -> pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS)); action.run(); return null;
            })).toList();
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
    }
}
