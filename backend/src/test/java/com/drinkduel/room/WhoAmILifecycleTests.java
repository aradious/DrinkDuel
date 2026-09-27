package com.drinkduel.room;

import com.drinkduel.game.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.drinkduel.room.DomainException.Code.*;
import static com.drinkduel.game.WhoAmIState.Phase.*;

class WhoAmILifecycleTests {
    RoomStore store;
    RoomService service;
    Room room;
    GuestToken guest;
    UUID player;
    @BeforeEach void setup() {
        var avatars = new AvatarCatalog();
        store = new InMemoryRoomStore(Clock.systemUTC(), avatars);
        service = new RoomService(store, avatars);
        room = service.createRoom(new PlayerIdentity.Google("lifecycle-owner"), "GM");
        guest = GuestToken.generate();
        player = service.joinRoom(room.id(), "Ken", guest).playerFor(guest.identity()).id();
        service.startWhoAmI(room.id(), room.owner());
        service.submitName(room.id(), room.owner(), session(), "Doraemon");
        service.submitName(room.id(), guest.identity(), session(), "Doraemon");
        service.shuffle(room.id(), room.owner(), session());
    }
    Room current() { return store.find(room.id()).orElseThrow(); }
    UUID session() { return current().currentSession().orElseThrow().id(); }
    WhoAmIState state() { return (WhoAmIState) current().currentSession().orElseThrow().state(); }
    void end() { service.endGame(room.id(), room.owner(), session()); }
    void reveal() { end(); service.continueReveal(room.id(), room.owner(), session()); }
    void error(DomainException.Code code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(DomainException.class, action).code());
    }
    @ParameterizedTest @CsvSource({"0,0,PLAYING_GROUP,2,0", "1,0,LAST_ONE,1,0",
            "0,1,LAST_ONE,1,1", "0,2,GAVE_UP_GROUP,0,2", "1,1,GAVE_UP_GROUP,0,1",
            "2,0,GROUP_SUCCESS,0,0"})
    void roastPriorityAndManualEnd(int got, int gave, String kind, int playing, int quitters) {
        var ids = state().participantIds();
        for (int i = 0; i < got; i++) service.markGotIt(room.id(), room.owner(), session(), ids.get(i));
        for (int i = got; i < got + gave; i++) service.giveUp(room.id(), current().players().get(i).identity(), session());
        assertEquals(PLAYING, state().phase());
        var results = state().results();
        end();
        assertEquals(ROAST, state().phase());
        assertEquals(results, state().results());
        var roast = state().roastSummary();
        assertEquals(kind, roast.kind().name());
        assertEquals(playing, roast.playingPlayerIds().size());
        assertEquals(quitters, roast.gaveUpPlayerIds().size());
        error(INVALID_GAME_PHASE, () -> service.giveUp(room.id(), guest.identity(), session()));
        error(INVALID_GAME_PHASE, () -> service.markGotIt(room.id(), room.owner(), session(), player));
        error(INVALID_GAME_PHASE, () -> service.resetPlayerStatus(room.id(), room.owner(), session(), player, 0));
        service.continueReveal(room.id(), room.owner(), session());
        assertEquals(REVEAL, state().phase());
        assertEquals(results, state().results());
    }
    @Test void allLifecycleActionsRequireConnectedGm() {
        UUID round = session();
        List<Runnable> actions = List.of(
                () -> service.endGame(room.id(), guest.identity(), round),
                () -> service.continueReveal(room.id(), guest.identity(), round),
                () -> service.playAgain(room.id(), guest.identity(), round),
                () -> service.backToRoom(room.id(), guest.identity(), round),
                () -> service.closeRoom(room.id(), guest.identity(), round));
        for (var action : actions) error(NOT_AUTHORIZED, action::run);
        service.disconnectPlayer(room.id(), room.owner());
        var before = current();
        error(NOT_AUTHORIZED, this::end);
        error(NOT_AUTHORIZED, () -> service.closeRoom(room.id(), room.owner(), round));
        assertSame(before, current());
    }
    @Test void phaseAndSessionGuardsPreventSkippingAndRepeatedTransitions() {
        UUID round = session();
        error(INVALID_GAME_PHASE, () -> service.continueReveal(room.id(), room.owner(), round));
        error(INVALID_GAME_PHASE, () -> service.playAgain(room.id(), room.owner(), round));
        error(INVALID_GAME_PHASE, () -> service.backToRoom(room.id(), room.owner(), round));
        error(STALE_COMMAND, () -> service.endGame(room.id(), room.owner(), UUID.randomUUID()));
        end();
        error(INVALID_GAME_PHASE, this::end);
        error(INVALID_GAME_PHASE, () -> service.backToRoom(room.id(), room.owner(), round));
        service.continueReveal(room.id(), room.owner(), round);
        error(INVALID_GAME_PHASE, () -> service.continueReveal(room.id(), room.owner(), round));
    }
    @Test void playAgainPreservesRoomAndClearsEveryRoundField() {
        reveal();
        Room before = current();
        UUID old = session();
        service.playAgain(room.id(), room.owner(), old);
        assertNotEquals(old, session());
        assertEquals(SUBMIT_NAME, state().phase());
        assertEquals(before.players(), current().players());
        assertEquals(before.owner(), current().owner());
        assertEquals(before.gmPlayerId(), current().gmPlayerId());
        assertEquals(before.id(), current().id());
        assertEquals(before.createdAt(), current().createdAt());
        assertEquals(before.expiresAt(), current().expiresAt());
        assertTrue(state().submissions().isEmpty());
        assertTrue(state().assignments().isEmpty());
        assertTrue(state().results().isEmpty());
        error(GAME_NOT_READY, () -> service.shuffle(room.id(), room.owner(), session()));
        error(GAME_IN_PROGRESS, () -> service.joinRoom(room.id(), "New", GuestToken.generate()));
        error(STALE_COMMAND, () -> service.endGame(room.id(), room.owner(), old));
        error(STALE_COMMAND, () -> service.closeRoom(room.id(), room.owner(), old));
    }
    @Test void playAgainChecksMinimumAndRetainsDisconnectedMembers() {
        reveal();
        service.disconnectPlayer(room.id(), guest.identity());
        error(NOT_ENOUGH_PLAYERS, () -> service.playAgain(room.id(), room.owner(), session()));
        service.reconnectPlayer(room.id(), guest);
        service.backToRoom(room.id(), room.owner(), session());
        var extra = GuestToken.generate();
        service.joinRoom(room.id(), "Extra", extra);
        service.startWhoAmI(room.id(), room.owner());
        for (var p : current().players()) service.submitName(room.id(), p.identity(), session(), "Name");
        service.shuffle(room.id(), room.owner(), session());
        reveal();
        service.disconnectPlayer(room.id(), extra.identity());
        service.playAgain(room.id(), room.owner(), session());
        assertEquals(3, state().participantIds().size());
        assertEquals(ConnectionState.DISCONNECTED, current().playerFor(extra.identity()).connectionState());
        assertFalse(state().readyForShuffle(Set.of(room.gmPlayerId(), player)));
    }
    @Test void backToRoomPreservesMembersAndReopensJoinAndLeave() {
        reveal();
        var before = current();
        service.backToRoom(room.id(), room.owner(), session());
        assertTrue(current().isLobby());
        assertTrue(current().currentSession().isEmpty());
        assertEquals(before.players(), current().players());
        assertEquals(before.expiresAt(), current().expiresAt());
        var added = GuestToken.generate();
        service.joinRoom(room.id(), "New", added);
        service.leaveRoom(room.id(), added.identity());
        assertEquals(before.players(), current().players());
    }
    @Test void submitRecoveryOnlyWhenFewerThanTwoParticipants() {
        reveal();
        service.playAgain(room.id(), room.owner(), session());
        error(INVALID_GAME_PHASE, () -> service.backToRoom(room.id(), room.owner(), session()));
        service.kickPlayer(room.id(), room.owner(), player);
        service.backToRoom(room.id(), room.owner(), session());
        assertTrue(current().isLobby());
        assertEquals(1, current().players().size());
    }
    @ParameterizedTest @CsvSource({"ROAST", "REVEAL"})
    void kickPreservesOtherAssignmentAndCreatorDuringPostgame(String phase) {
        end();
        if (phase.equals("REVEAL")) service.continueReveal(room.id(), room.owner(), session());
        var assignment = state().assignments().get(room.gmPlayerId());
        var result = state().results().get(room.gmPlayerId());
        error(GAME_IN_PROGRESS, () -> service.leaveRoom(room.id(), guest.identity()));
        service.kickPlayer(room.id(), room.owner(), player);
        assertSame(assignment, state().assignments().get(room.gmPlayerId()));
        assertEquals("Ken", assignment.submission().submitterNickname());
        assertEquals(result, state().results().get(room.gmPlayerId()));
        assertEquals(Set.of(room.gmPlayerId()), state().results().keySet());
        assertEquals(phase, state().phase().name());
    }
    @ParameterizedTest @CsvSource({"ROAST", "REVEAL"})
    void gmReconnectPreservesPostgame(String phase) {
        end();
        if (phase.equals("REVEAL")) service.continueReveal(room.id(), room.owner(), session());
        var before = state();
        service.disconnectPlayer(room.id(), room.owner());
        error(NOT_AUTHORIZED, () -> service.continueReveal(room.id(), room.owner(), session()));
        error(NOT_AUTHORIZED, () -> service.playAgain(room.id(), room.owner(), session()));
        error(NOT_AUTHORIZED, () -> service.backToRoom(room.id(), room.owner(), session()));
        service.reconnectGm(room.id(), room.owner());
        assertSame(before, state());
    }
    @Test void closeRemovesRoomAndPreventsReconnectOrJoin() {
        service.closeRoom(room.id(), room.owner(), session());
        assertTrue(store.find(room.id()).isEmpty());
        error(ROOM_NOT_FOUND, () -> service.reconnectGm(room.id(), room.owner()));
        error(ROOM_NOT_FOUND, () -> service.reconnectPlayer(room.id(), guest));
        error(ROOM_NOT_FOUND, () -> service.joinRoom(room.id(), "New", GuestToken.generate()));
    }
    @Test void duplicateEndAndContinueEachAcceptExactlyOne() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        together(() -> attempt(this::end, accepted, INVALID_GAME_PHASE), () -> attempt(this::end, accepted, INVALID_GAME_PHASE));
        assertEquals(1, accepted.get());
        accepted.set(0);
        Runnable next = () -> service.continueReveal(room.id(), room.owner(), session());
        together(() -> attempt(next, accepted, INVALID_GAME_PHASE), () -> attempt(next, accepted, INVALID_GAME_PHASE));
        assertEquals(1, accepted.get());
        assertEquals(REVEAL, state().phase());
    }
    @Test void endRacingResultFreezesAnAtomicResult() throws Exception {
        together(this::end, () -> attempt(() -> service.giveUp(room.id(), guest.identity(), session()), new AtomicInteger(), INVALID_GAME_PHASE));
        assertEquals(ROAST, state().phase());
        var r = state().results().get(player);
        assertTrue((r.status() == WhoAmIState.PlayerGameStatus.PLAYING && r.version() == 0)
                || (r.status() == WhoAmIState.PlayerGameStatus.GAVE_UP && r.version() == 1));
    }
    @Test void playAgainRacingBackToRoomCannotApplyBoth() throws Exception {
        reveal(); UUID old = session(); var accepted = new AtomicInteger();
        together(() -> attempt(() -> service.playAgain(room.id(), room.owner(), old), accepted, STALE_COMMAND, INVALID_GAME_PHASE),
                () -> attempt(() -> service.backToRoom(room.id(), room.owner(), old), accepted, STALE_COMMAND, INVALID_GAME_PHASE));
        assertEquals(1, accepted.get());
        assertTrue(current().isLobby() || state().phase() == SUBMIT_NAME);
    }
    @Test void closeRacingPlayAgainNeverClosesNewRoundOrResurrectsRoom() throws Exception {
        reveal(); UUID old = session(); var accepted = new AtomicInteger();
        together(() -> attempt(() -> service.playAgain(room.id(), room.owner(), old), accepted, ROOM_NOT_FOUND),
                () -> attempt(() -> service.closeRoom(room.id(), room.owner(), old), accepted, STALE_COMMAND));
        assertEquals(1, accepted.get());
        if (store.find(room.id()).isPresent()) { assertNotEquals(old, session()); assertEquals(SUBMIT_NAME, state().phase()); }
    }
    @Test void kickRacingRevealKeepsAssignmentsCoherent() throws Exception {
        end(); var assignment = state().assignments().get(room.gmPlayerId());
        together(() -> service.kickPlayer(room.id(), room.owner(), player),
                () -> service.continueReveal(room.id(), room.owner(), session()));
        assertEquals(REVEAL, state().phase());
        assertEquals(1, state().assignments().size());
        assertSame(assignment, state().assignments().get(room.gmPlayerId()));
    }
    @Test void disconnectRacingEndPreservesRoundAndOwnership() throws Exception {
        together(() -> service.disconnectPlayer(room.id(), room.owner()),
                () -> attempt(this::end, new AtomicInteger(), NOT_AUTHORIZED));
        assertTrue(state().phase() == PLAYING || state().phase() == ROAST);
        var before = state();
        service.reconnectGm(room.id(), room.owner());
        assertSame(before, state());
        assertEquals(room.gmPlayerId(), current().gmPlayerId());
    }
    void attempt(Runnable action, AtomicInteger count, DomainException.Code... errors) {
        try { action.run(); count.incrementAndGet(); }
        catch (DomainException e) { assertTrue(List.of(errors).contains(e.code()), e.code().name()); }
    }
    void together(Runnable... actions) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = Arrays.stream(actions).map(action -> pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS)); action.run(); return null;
            })).toList();
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
    }
}
