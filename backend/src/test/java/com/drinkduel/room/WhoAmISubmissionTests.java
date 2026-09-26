package com.drinkduel.room;

import com.drinkduel.game.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;
import static com.drinkduel.room.DomainException.Code.*;

class WhoAmISubmissionTests {
    private RoomStore store;
    private RoomService service;
    private Room room;
    private GuestToken guest;
    private UUID playerId;
    private RoomStoreTests.MutableClock clock;

    @BeforeEach void setup() {
        clock = new RoomStoreTests.MutableClock(Instant.parse("2026-09-26T00:00:00Z"));
        var avatars = new AvatarCatalog();
        store = new InMemoryRoomStore(clock, avatars);
        service = new RoomService(store, avatars);
        room = service.createRoom(new PlayerIdentity.Google("owner"), "GM");
        guest = GuestToken.generate();
        playerId = service.joinRoom(room.id(), "Ken", guest).playerFor(guest.identity()).id();
    }
    private Room current() { return store.find(room.id()).orElseThrow(); }
    private GameSession session() { return current().currentSession().orElseThrow(); }
    private WhoAmIState state() { return (WhoAmIState) session().state(); }
    private void start() { service.startWhoAmI(room.id(), room.owner()); }
    private void submit(PlayerIdentity identity, String text) { service.submitName(room.id(), identity, session().id(), text); }
    private void reset(UUID player, UUID submission) {
        service.resetSubmission(room.id(), room.owner(), session().id(), player, submission);
    }
    private boolean ready() {
        return state().readyForShuffle(current().players().stream()
                .filter(p -> p.connectionState() == ConnectionState.CONNECTED).map(Player::id)
                .collect(java.util.stream.Collectors.toSet()));
    }
    private void error(DomainException.Code code, Executable action) {
        assertEquals(code, assertThrows(DomainException.class, action).code());
    }

    @Test void ownerStartsFreshSessionIncludingEveryMemberWithoutChangingRoomPlayers() {
        var third = GuestToken.generate();
        service.joinRoom(room.id(), "Offline member", third);
        service.disconnectPlayer(room.id(), third.identity());
        Room before = current();
        start();
        assertEquals(before.players(), current().players());
        assertEquals(before.id(), current().id());
        assertEquals(before.expiresAt(), current().expiresAt());
        assertEquals(before.players().stream().map(Player::id).toList(), state().participantIds());
        assertEquals(GameType.WHO_AM_I, session().gameType());
        assertEquals(WhoAmIState.Phase.SUBMIT_NAME, state().phase());
        assertTrue(state().submissions().isEmpty());
        assertFalse(current().isLobby());
    }

    @Test void onlyConnectedOwnerCanOpenAndCannotOpenAgain() {
        error(NOT_AUTHORIZED, () -> service.startWhoAmI(room.id(), guest.identity()));
        error(NOT_AUTHORIZED, () -> service.startWhoAmI(room.id(), new PlayerIdentity.Google("wrong")));
        service.disconnectPlayer(room.id(), room.owner());
        error(NOT_AUTHORIZED, () -> service.startWhoAmI(room.id(), room.owner()));
        service.reconnectGm(room.id(), room.owner());
        start();
        UUID id = session().id();
        error(GAME_IN_PROGRESS, this::start);
        assertEquals(id, session().id());
    }

    @Test void atLeastTwoConnectedPlayersAreRequiredAtStart() {
        service.disconnectPlayer(room.id(), guest.identity());
        error(NOT_ENOUGH_PLAYERS, this::start);
        service.leaveRoom(room.id(), guest.identity());
        error(NOT_ENOUGH_PLAYERS, this::start);
        assertTrue(current().currentSession().isEmpty());
    }

    @Test void newJoinsAndLeaveAreClosedButExistingParticipantCanReconnect() {
        start();
        UUID sessionId = session().id();
        var before = current().playerFor(guest.identity());
        error(GAME_IN_PROGRESS, () -> service.joinRoom(room.id(), "Late", GuestToken.generate()));
        error(GAME_IN_PROGRESS, () -> service.leaveRoom(room.id(), guest.identity()));
        service.disconnectPlayer(room.id(), guest.identity());
        service.reconnectPlayer(room.id(), guest);
        assertEquals(before, current().playerFor(guest.identity()));
        assertEquals(sessionId, session().id());
        assertEquals(2, state().participantIds().size());
    }

    @Test void gmAndGuestMaySubmitIdenticalTextAsDistinctSubmissions() {
        start();
        submit(room.owner(), "Doraemon");
        assertFalse(ready());
        submit(guest.identity(), "Doraemon");
        assertTrue(ready());
        var gm = state().submissions().get(room.gmPlayerId());
        var ken = state().submissions().get(playerId);
        assertNotEquals(gm.id(), ken.id());
        assertEquals("Doraemon", ken.text());
        assertEquals("Ken", ken.submitterNickname());
        assertEquals(playerId, ken.submitterPlayerId());
        assertEquals(2, state().submissions().size());
        assertEquals(WhoAmIState.Phase.SUBMIT_NAME, state().phase());
    }

    @Test void invalidEmptySubmissionsAndDirectReplacementDoNotChangeState() {
        start();
        for (String input : Arrays.asList(null, "", " \t\n"))
            error(INVALID_SUBMISSION, () -> submit(guest.identity(), input));
        assertTrue(state().submissions().isEmpty());
        submit(guest.identity(), "  Batman  ");
        var before = state();
        error(ALREADY_SUBMITTED, () -> submit(guest.identity(), "Replacement"));
        assertSame(before, state());
        assertEquals("  Batman  ", state().submissions().get(playerId).text());
    }

    @Test void wrongPhaseSessionOrIdentityCannotSubmit() {
        error(INVALID_GAME_PHASE, () -> service.submitName(room.id(), guest.identity(), UUID.randomUUID(), "Secret"));
        start();
        error(STALE_COMMAND, () -> service.submitName(room.id(), guest.identity(), UUID.randomUUID(), "Secret"));
        error(NOT_AUTHORIZED, () -> submit(GuestToken.generate().identity(), "Secret"));
        error(PLAYER_NOT_IN_GAME, () -> state().submit(UUID.randomUUID(), "Unknown", "Secret"));
        assertTrue(state().submissions().isEmpty());
    }

    @Test void gmCanResetOthersAndSelfAndEachResubmissionHasNewIdentity() {
        start();
        submit(room.owner(), "GM secret");
        submit(guest.identity(), "Guest secret");
        UUID old = state().submissions().get(playerId).id();
        reset(playerId, old);
        assertEquals(1, state().submissions().size());
        assertFalse(ready());
        submit(guest.identity(), "New secret");
        assertNotEquals(old, state().submissions().get(playerId).id());
        reset(room.gmPlayerId(), state().submissions().get(room.gmPlayerId()).id());
        assertFalse(state().submissions().containsKey(room.gmPlayerId()));
        submit(room.owner(), "New GM secret");
        assertTrue(ready());
    }

    @Test void guestCannotResetAndMissingSubmissionHasSafeError() {
        start();
        submit(guest.identity(), "Secret");
        error(NOT_AUTHORIZED, () -> service.resetSubmission(room.id(), guest.identity(), session().id(),
                playerId, state().submissions().get(playerId).id()));
        error(SUBMISSION_NOT_FOUND, () -> reset(room.gmPlayerId(), UUID.randomUUID()));
        error(PLAYER_NOT_IN_GAME, () -> reset(UUID.randomUUID(), UUID.randomUUID()));
        assertEquals(1, state().submissions().size());
    }

    @Test void delayedResetCannotEraseNewSubmission() {
        start();
        submit(guest.identity(), "Old secret");
        UUID old = state().submissions().get(playerId).id();
        reset(playerId, old);
        submit(guest.identity(), "New secret");
        var before = state();
        error(STALE_COMMAND, () -> reset(playerId, old));
        assertSame(before, state());
    }

    @Test void disconnectedParticipantBlocksReadinessEvenAfterSubmitting() {
        start();
        submit(room.owner(), "GM secret");
        service.disconnectPlayer(room.id(), guest.identity());
        assertFalse(ready());
        service.reconnectPlayer(room.id(), guest);
        submit(guest.identity(), "Guest secret");
        assertTrue(ready());
        var before = state().submissions().get(playerId);
        service.disconnectPlayer(room.id(), guest.identity());
        assertFalse(ready());
        service.reconnectPlayer(room.id(), guest);
        assertEquals(before, state().submissions().get(playerId));
        assertTrue(ready());
    }

    @Test void gmOfflinePreservesSessionAllowsSubmissionButBlocksGmCommands() {
        start();
        UUID sessionId = session().id();
        service.disconnectPlayer(room.id(), room.owner());
        submit(guest.identity(), "Guest secret");
        error(NOT_AUTHORIZED, () -> reset(playerId, state().submissions().get(playerId).id()));
        error(NOT_AUTHORIZED, () -> service.kickPlayer(room.id(), room.owner(), playerId));
        service.reconnectGm(room.id(), room.owner());
        assertEquals(sessionId, session().id());
        assertEquals(1, state().submissions().size());
        reset(playerId, state().submissions().get(playerId).id());
    }

    @Test void kickingSubmittedParticipantRemovesTheirSubmissionAndPreservesOthers() {
        var other = GuestToken.generate();
        service.joinRoom(room.id(), "Other", other);
        start();
        submit(room.owner(), "GM secret");
        submit(guest.identity(), "Guest secret");
        submit(other.identity(), "Other secret");
        var retained = state().submissions().get(room.gmPlayerId());
        service.kickPlayer(room.id(), room.owner(), playerId);
        assertFalse(state().participantIds().contains(playerId));
        assertFalse(state().submissions().containsKey(playerId));
        assertEquals(retained, state().submissions().get(room.gmPlayerId()));
        assertEquals(2, state().submissions().size());
        assertTrue(ready());
        error(PLAYER_KICKED, () -> service.reconnectPlayer(room.id(), guest));
        error(PLAYER_KICKED, () -> submit(guest.identity(), "Secret"));
    }

    @Test void kickingMissingDisconnectedParticipantRecalculatesReadinessAndOneNeverReady() {
        var other = GuestToken.generate();
        UUID otherId = service.joinRoom(room.id(), "Other", other).playerFor(other.identity()).id();
        start();
        submit(room.owner(), "GM secret");
        submit(guest.identity(), "Guest secret");
        service.disconnectPlayer(room.id(), other.identity());
        assertFalse(ready());
        service.kickPlayer(room.id(), room.owner(), otherId);
        assertTrue(ready());
        service.kickPlayer(room.id(), room.owner(), playerId);
        assertFalse(ready());
        assertEquals(List.of(room.gmPlayerId()), state().participantIds());
        assertEquals(WhoAmIState.Phase.SUBMIT_NAME, state().phase());
    }

    @Test void immutableSnapshotsAndDebugStringsDoNotExposeSecret() {
        start();
        var before = state();
        submit(guest.identity(), "SECRET-SENTINEL");
        assertTrue(before.submissions().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> state().submissions().clear());
        assertThrows(UnsupportedOperationException.class, () -> state().participantIds().clear());
        assertFalse(session().toString().contains("SECRET-SENTINEL"));
        assertFalse(state().submissions().toString().contains("SECRET-SENTINEL"));
    }

    @Test void fixedRoomExpirationStillAppliesDuringSubmission() {
        start();
        UUID id = session().id();
        clock.advance(Duration.ofHours(48));
        error(ROOM_EXPIRED, () -> service.submitName(room.id(), guest.identity(), id, "Secret"));
        assertTrue(store.find(room.id()).isEmpty());
    }

    @Test void simultaneousPlayersSubmitWithoutLostUpdates() throws Exception {
        start();
        together(List.of(() -> submit(room.owner(), "One"), () -> submit(guest.identity(), "Two")));
        assertEquals(2, state().submissions().size());
        assertTrue(ready());
    }

    @Test void simultaneousDuplicateSubmissionsAcceptExactlyOne() throws Exception {
        start();
        var accepted = new AtomicInteger();
        var actions = new ArrayList<Runnable>();
        for (int i = 0; i < 20; i++) actions.add(() -> {
            try { submit(guest.identity(), "Secret"); accepted.incrementAndGet(); }
            catch (DomainException exception) { assertEquals(ALREADY_SUBMITTED, exception.code()); }
        });
        together(actions);
        assertEquals(1, accepted.get());
        assertEquals(1, state().submissions().size());
    }

    @Test void resetRacingSubmitKeepsOnlyAValidNewSubmissionOrAnEmptySlot() throws Exception {
        start();
        for (int i = 0; i < 20; i++) {
            if (!state().submissions().containsKey(playerId)) submit(guest.identity(), "Old");
            UUID old = state().submissions().get(playerId).id();
            together(List.of(() -> reset(playerId, old), () -> {
                try { submit(guest.identity(), "New"); }
                catch (DomainException exception) { assertEquals(ALREADY_SUBMITTED, exception.code()); }
            }));
            var remaining = state().submissions().get(playerId);
            if (remaining != null) { assertNotEquals(old, remaining.id()); assertEquals("New", remaining.text()); }
        }
    }

    @Test void kickRacingSubmitCannotLeaveAnOrphanSubmission() throws Exception {
        start();
        together(List.of(() -> service.kickPlayer(room.id(), room.owner(), playerId), () -> {
            try { submit(guest.identity(), "Secret"); }
            catch (DomainException exception) { assertEquals(PLAYER_KICKED, exception.code()); }
        }));
        assertFalse(state().submissions().containsKey(playerId));
        assertFalse(state().participantIds().contains(playerId));
        assertEquals(1, current().players().size());
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
