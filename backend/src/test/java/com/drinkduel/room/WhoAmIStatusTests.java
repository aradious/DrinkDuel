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
    RoomStore store;
    RoomService service;
    Room room;
    GuestToken guest;
    UUID player;

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
    void give() { service.giveUp(room.id(), guest.identity(), session()); }
    void reset(UUID id) { service.resetPlayerStatus(room.id(), room.owner(), session(), id, state().results().get(id).version()); }
    void error(DomainException.Code code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(DomainException.class, action).code());
    }
    void sameSecrets(WhoAmIState before) {
        assertEquals(before.participantIds(), state().participantIds());
        assertEquals(before.assignments(), state().assignments());
        assertEquals(before.submissions(), state().submissions());
        for (UUID id : before.participantIds())
            assertSame(before.assignments().get(id), state().assignments().get(id));
        assertEquals(WhoAmIState.Phase.PLAYING, state().phase());
    }

    @Test void gmCanMarkOtherAndSelfWithoutEndingRound() {
        var before = state();
        got(player);
        got(room.gmPlayerId());
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

    @Test void bothPlayersIncludingGmCanGiveUpWithoutEndingRound() {
        var before = state();
        give();
        service.giveUp(room.id(), room.owner(), session());
        assertTrue(state().results().values().stream().allMatch(r -> r.status() == GAVE_UP));
        sameSecrets(before);
    }

    @Test void duplicateAndCrossResultTransitionsAreRejected() {
        give();
        var before = current();
        error(INVALID_TRANSITION, this::give);
        error(INVALID_TRANSITION, () -> got(player));
        assertSame(before, current());
        reset(player);
        got(player);
        before = current();
        error(INVALID_TRANSITION, this::give);
        error(INVALID_TRANSITION, () -> got(player));
        assertSame(before, current());
    }

    @Test void gmResetBothResultsPreservesAssignmentAndOnlyAffectsTarget() {
        var before = state();
        got(room.gmPlayerId());
        give();
        reset(player);
        assertEquals(PLAYING, state().results().get(player).status());
        assertEquals(GOT_IT, state().results().get(room.gmPlayerId()).status());
        reset(room.gmPlayerId());
        assertTrue(state().results().values().stream().allMatch(r -> r.status() == PLAYING && r.version() == 2));
        error(INVALID_TRANSITION, () -> reset(player));
        sameSecrets(before);
        assertThrows(UnsupportedOperationException.class, () -> state().results().clear());
    }

    @Test void delayedResetCannotEraseLaterResultEvenWhenStatusRepeats() {
        got(player);
        long first = state().results().get(player).version();
        reset(player);
        got(player);
        var before = current();
        error(STALE_COMMAND, () -> service.resetPlayerStatus(room.id(), room.owner(), session(), player, first));
        assertSame(before, current());
        assertEquals(3, state().results().get(player).version());
    }

    @Test void phaseSessionAndMembershipPreconditionsProtectAllOperations() {
        error(STALE_COMMAND, () -> service.giveUp(room.id(), guest.identity(), UUID.randomUUID()));
        error(STALE_COMMAND, () -> service.markGotIt(room.id(), room.owner(), UUID.randomUUID(), player));
        error(STALE_COMMAND, () -> service.resetPlayerStatus(room.id(), room.owner(), UUID.randomUUID(), player, 0));
        error(PLAYER_NOT_IN_GAME, () -> got(UUID.randomUUID()));
        error(PLAYER_NOT_IN_GAME, () -> service.resetPlayerStatus(room.id(), room.owner(), session(), UUID.randomUUID(), 0));
        error(NOT_AUTHORIZED, () -> service.giveUp(room.id(), GuestToken.generate().identity(), session()));
        var lobby = service.createRoom(new PlayerIdentity.Google("other"), "Other");
        error(INVALID_GAME_PHASE, () -> service.markGotIt(lobby.id(), lobby.owner(), UUID.randomUUID(), lobby.gmPlayerId()));
        var token = GuestToken.generate();
        service.joinRoom(lobby.id(), "Friend", token);
        var submit = service.startWhoAmI(lobby.id(), lobby.owner());
        UUID round = submit.currentSession().orElseThrow().id();
        error(INVALID_GAME_PHASE, () -> service.giveUp(lobby.id(), token.identity(), round));
        error(INVALID_GAME_PHASE, () -> service.markGotIt(lobby.id(), lobby.owner(), round, lobby.gmPlayerId()));
        error(INVALID_GAME_PHASE, () -> service.resetPlayerStatus(lobby.id(), lobby.owner(), round, lobby.gmPlayerId(), 0));
    }

    @Test void disconnectAndReconnectPreserveResultsAndGmOfflineAllowsGiveUp() {
        var before = current().players();
        got(room.gmPlayerId());
        service.disconnectPlayer(room.id(), room.owner());
        error(NOT_AUTHORIZED, () -> got(player));
        error(NOT_AUTHORIZED, () -> reset(room.gmPlayerId()));
        give();
        var game = state();
        service.disconnectPlayer(room.id(), guest.identity());
        assertSame(game, state());
        service.reconnectPlayer(room.id(), guest);
        service.reconnectGm(room.id(), room.owner());
        assertSame(game, state());
        assertEquals(before, current().players());
    }

    @ParameterizedTest @EnumSource(WhoAmIState.PlayerGameStatus.class)
    void kickRemovesResultAndAssignmentButRetainsAttribution(WhoAmIState.PlayerGameStatus status) {
        if (status == GOT_IT) got(player);
        if (status == GAVE_UP) give();
        var retained = state().assignments().get(room.gmPlayerId());
        service.kickPlayer(room.id(), room.owner(), player);
        assertFalse(state().results().containsKey(player));
        assertFalse(state().assignments().containsKey(player));
        assertSame(retained, state().assignments().get(room.gmPlayerId()));
        assertEquals("Ken", retained.submission().submitterNickname());
        assertEquals(1, state().participantIds().size());
        error(PLAYER_KICKED, this::give);
        error(PLAYER_NOT_IN_GAME, () -> got(player));
    }

    @Test void concurrentGotItAndGiveUpAcceptExactlyOne() throws Exception {
        var before = state();
        var accepted = new AtomicInteger();
        together(List.of(() -> finish(() -> got(player), accepted), () -> finish(this::give, accepted)));
        assertEquals(1, accepted.get());
        assertEquals(1, state().results().get(player).version());
        assertNotEquals(PLAYING, state().results().get(player).status());
        sameSecrets(before);
    }

    @Test void concurrentDuplicateGotItAcceptsExactlyOne() throws Exception {
        var accepted = new AtomicInteger();
        var actions = new ArrayList<Runnable>();
        for (int i = 0; i < 10; i++) actions.add(() -> finish(() -> got(player), accepted));
        together(actions);
        assertEquals(1, accepted.get());
        assertEquals(1, state().results().get(player).version());
    }

    @Test void resetRacingNewStatusUsesValidTransitions() throws Exception {
        got(player);
        together(List.of(() -> service.resetPlayerStatus(room.id(), room.owner(), session(), player, 1), () -> {
            try { give(); } catch (DomainException e) { assertEquals(INVALID_TRANSITION, e.code()); }
        }));
        var result = state().results().get(player);
        assertTrue((result.status() == PLAYING && result.version() == 2)
                || (result.status() == GAVE_UP && result.version() == 3));
        error(STALE_COMMAND, () -> service.resetPlayerStatus(room.id(), room.owner(), session(), player, 1));
    }

    @Test void kickRacingStatusLeavesNoOrphanResult() throws Exception {
        var retained = state().assignments().get(room.gmPlayerId());
        together(List.of(() -> service.kickPlayer(room.id(), room.owner(), player), () -> {
            try { got(player); } catch (DomainException e) { assertEquals(PLAYER_NOT_IN_GAME, e.code()); }
        }));
        assertFalse(state().results().containsKey(player));
        assertSame(retained, state().assignments().get(room.gmPlayerId()));
    }

    @Test void aFreshRoundHasFreshStatuses() {
        var ids = state().participantIds();
        var fresh = new WhoAmIState(ids);
        assertTrue(fresh.results().isEmpty());
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
