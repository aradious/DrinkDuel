package com.drinkduel.room;

import com.drinkduel.game.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.drinkduel.room.DomainException.Code.*;

class WhoAmIShuffleTests {
    RoomStore store;
    RoomService service;
    Room room;
    GuestToken guest;
    UUID guestId;

    @BeforeEach void setup() {
        var avatars = new AvatarCatalog();
        store = new InMemoryRoomStore(Clock.systemUTC(), avatars);
        service = new RoomService(store, avatars);
        room = service.createRoom(new PlayerIdentity.Google("shuffle-owner"), "GM");
        guest = GuestToken.generate();
        guestId = service.joinRoom(room.id(), "Ken", guest).playerFor(guest.identity()).id();
        service.startWhoAmI(room.id(), room.owner());
    }
    Room current() { return store.find(room.id()).orElseThrow(); }
    UUID session() { return current().currentSession().orElseThrow().id(); }
    WhoAmIState state() { return (WhoAmIState) current().currentSession().orElseThrow().state(); }
    void ready() {
        service.submitName(room.id(), room.owner(), session(), "GM-secret");
        service.submitName(room.id(), guest.identity(), session(), "Ken-secret");
    }
    void shuffle() { service.shuffle(room.id(), room.owner(), session()); }
    void error(DomainException.Code code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(DomainException.class, action).code());
    }
    static IntStream sizes() { return IntStream.rangeClosed(2, 20); }

    @ParameterizedTest @MethodSource("sizes")
    void derangementUsesEachSubmissionExactlyOnceForEverySupportedSize(int size) {
        var ids = IntStream.range(0, size).mapToObj(i -> new UUID(0, i + 1)).toList();
        var initial = new WhoAmIState(ids);
        for (UUID id : ids) initial = initial.submit(id, "Player-" + id, "duplicate-text");
        var expected = new HashSet<>(initial.submissions().values());
        var outcomes = new HashSet<List<UUID>>();
        var calls = new AtomicInteger();
        var random = new Random(123456) {
            @Override public int nextInt(int bound) {
                calls.incrementAndGet();
                return super.nextInt(bound);
            }
        };
        for (int round = 0; round < 100; round++) {
            var shuffled = initial.shuffle(Set.copyOf(ids), random);
            assertEquals(WhoAmIState.Phase.PLAYING, shuffled.phase());
            assertEquals(Set.copyOf(ids), shuffled.assignments().keySet());
            assertEquals(expected, new HashSet<>(shuffled.assignments().values().stream()
                    .map(WhoAmIAssignment::submission).toList()));
            shuffled.assignments().forEach((recipient, assignment) -> {
                assertEquals(recipient, assignment.playerId());
                assertNotEquals(recipient, assignment.submission().submitterPlayerId());
                assertSame(initialSubmission(expected, assignment.submission().id()), assignment.submission());
            });
            outcomes.add(ids.stream().map(id -> shuffled.assignments().get(id).submission().id()).toList());
            assertFalse(shuffled.readyForShuffle(Set.copyOf(ids)));
        }
        assertEquals(100 * (size - 1), calls.get());
        if (size > 2) assertTrue(outcomes.size() > 1);
        assertTrue(initial.assignments().isEmpty());
        var playing = initial.shuffle(Set.copyOf(ids), new Random(10));
        var originalAssignments = playing.assignments();
        for (UUID removed : ids.subList(1, ids.size())) {
            playing = playing.withoutParticipant(removed);
            assertEquals(Set.copyOf(playing.participantIds()), playing.assignments().keySet());
            for (UUID remaining : playing.participantIds())
                assertSame(originalAssignments.get(remaining), playing.assignments().get(remaining));
            assertEquals(expected, new HashSet<>(playing.submissions().values()));
        }
    }
    private SubmittedName initialSubmission(Set<SubmittedName> values, UUID id) {
        return values.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test void gmShuffleSwapsTwoPlayersAndPreservesRoomMetadata() {
        ready();
        Room before = current();
        shuffle();
        assertEquals("Ken-secret", state().assignments().get(room.gmPlayerId()).submission().text());
        assertEquals("GM-secret", state().assignments().get(guestId).submission().text());
        assertEquals(before.revision() + 1, current().revision());
        assertEquals(before.players(), current().players());
        assertEquals(before.expiresAt(), current().expiresAt());
        assertEquals(before.currentSession().orElseThrow().id(), session());
        assertThrows(UnsupportedOperationException.class, () -> state().assignments().clear());
        assertFalse(state().assignments().toString().contains("secret"));
    }

    @Test void unauthorizedAndStaleShuffleCannotMutate() {
        ready();
        var before = current();
        error(NOT_AUTHORIZED, () -> service.shuffle(room.id(), guest.identity(), session()));
        error(NOT_AUTHORIZED, () -> service.shuffle(room.id(), new PlayerIdentity.Google("forged"), session()));
        error(STALE_COMMAND, () -> service.shuffle(room.id(), room.owner(), UUID.randomUUID()));
        assertSame(before, current());
        service.disconnectPlayer(room.id(), room.owner());
        var offline = current();
        error(NOT_AUTHORIZED, this::shuffle);
        assertSame(offline, current());
    }

    @Test void missingSubmissionOrDisconnectedMemberBlocksShuffleWithoutMutation() {
        var before = current();
        error(GAME_NOT_READY, this::shuffle);
        assertSame(before, current());
        ready();
        service.disconnectPlayer(room.id(), guest.identity());
        before = current();
        error(GAME_NOT_READY, this::shuffle);
        assertSame(before, current());
        service.reconnectPlayer(room.id(), guest);
        shuffle();
        assertEquals(WhoAmIState.Phase.PLAYING, state().phase());
    }

    @Test void fewerThanTwoCannotShuffle() {
        ready();
        service.kickPlayer(room.id(), room.owner(), guestId);
        var before = current();
        error(NOT_ENOUGH_PLAYERS, this::shuffle);
        assertSame(before, current());
        assertTrue(state().assignments().isEmpty());
    }

    @Test void playingRejectsRepeatShuffleSubmissionAndReset() {
        ready();
        shuffle();
        var before = current();
        error(INVALID_GAME_PHASE, this::shuffle);
        error(INVALID_GAME_PHASE, () -> service.submitName(room.id(), guest.identity(), session(), "new"));
        error(INVALID_GAME_PHASE, () -> service.resetSubmission(room.id(), room.owner(), session(),
                guestId, state().submissions().get(guestId).id()));
        error(INVALID_GAME_PHASE, () -> state().submit(guestId, "Ken", "new"));
        error(INVALID_GAME_PHASE, () -> state().reset(guestId, state().submissions().get(guestId).id()));
        assertSame(before, current());
    }

    @Test void disconnectAndBothKindsOfReconnectPreserveIdentityAndAssignments() {
        ready();
        shuffle();
        var before = current();
        var game = state();
        service.disconnectPlayer(room.id(), guest.identity());
        service.disconnectPlayer(room.id(), room.owner());
        assertSame(game, state());
        error(NOT_AUTHORIZED, () -> service.kickPlayer(room.id(), room.owner(), guestId));
        service.reconnectPlayer(room.id(), guest);
        service.reconnectGm(room.id(), room.owner());
        assertEquals(before.players(), current().players());
        assertSame(game, state());
        error(GAME_IN_PROGRESS, () -> service.joinRoom(room.id(), "Late", GuestToken.generate()));
        error(GAME_IN_PROGRESS, () -> service.leaveRoom(room.id(), guest.identity()));
    }

    @Test void kickDiscardsOnlyRecipientsAssignmentAndRetainsCreatorAttributionBelowTwo() {
        ready();
        shuffle();
        var remaining = state().assignments().get(room.gmPlayerId());
        service.kickPlayer(room.id(), room.owner(), guestId);
        assertEquals(List.of(room.gmPlayerId()), state().participantIds());
        assertEquals(Map.of(room.gmPlayerId(), remaining), state().assignments());
        assertSame(remaining, state().assignments().get(room.gmPlayerId()));
        assertEquals(guestId, remaining.submission().submitterPlayerId());
        assertEquals("Ken", remaining.submission().submitterNickname());
        assertEquals("Ken-secret", remaining.submission().text());
        assertEquals(WhoAmIState.Phase.PLAYING, state().phase());
        error(PLAYER_KICKED, () -> service.reconnectPlayer(room.id(), guest));
    }

    @Test void concurrentShufflesCommitExactlyOnce() throws Exception {
        ready();
        var accepted = new AtomicInteger();
        long before = current().revision();
        var tasks = new ArrayList<Runnable>();
        for (int i = 0; i < 20; i++) tasks.add(() -> {
            try { shuffle(); accepted.incrementAndGet(); }
            catch (DomainException e) { assertEquals(INVALID_GAME_PHASE, e.code()); }
        });
        together(tasks);
        assertEquals(1, accepted.get());
        assertEquals(before + 1, current().revision());
        assertEquals(2, state().assignments().size());
    }

    @Test void disconnectRacingShuffleHasOnlyCompleteValidOutcomes() throws Exception {
        ready();
        together(List.of(this::tryShuffle, () -> service.disconnectPlayer(room.id(), guest.identity())));
        assertEquals(ConnectionState.DISCONNECTED, current().playerFor(guest.identity()).connectionState());
        assertEquals(state().phase() == WhoAmIState.Phase.PLAYING ? 2 : 0, state().assignments().size());
        assertEquals(2, state().submissions().size());
    }

    @Test void kickRacingShuffleNeverReassignsRemainingPlayer() throws Exception {
        ready();
        together(List.of(this::tryShuffle, () -> service.kickPlayer(room.id(), room.owner(), guestId)));
        assertEquals(1, state().participantIds().size());
        if (state().phase() == WhoAmIState.Phase.PLAYING) {
            assertEquals(1, state().assignments().size());
            assertEquals(guestId, state().assignments().get(room.gmPlayerId()).submission().submitterPlayerId());
        } else assertTrue(state().assignments().isEmpty());
    }

    private void tryShuffle() {
        try { shuffle(); }
        catch (DomainException e) { assertTrue(Set.of(GAME_NOT_READY, NOT_ENOUGH_PLAYERS).contains(e.code())); }
    }
    private void together(List<Runnable> tasks) throws Exception {
        var latch = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = tasks.stream().map(task -> pool.submit(() -> {
                assertTrue(latch.await(5, TimeUnit.SECONDS));
                task.run();
                return null;
            })).toList();
            latch.countDown();
            for (var f : futures) f.get(10, TimeUnit.SECONDS);
        }
    }
}
