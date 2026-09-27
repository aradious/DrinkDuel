package com.drinkduel.realtime;

import com.drinkduel.room.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomRealtimeTests {
    private final MutableClock clock = new MutableClock();
    private RoomStore store;
    private RoomService service;
    private RoomRealtime realtime;
    private Room room;

    @BeforeEach void setup() {
        var avatars = new AvatarCatalog();
        store = new InMemoryRoomStore(clock, avatars);
        service = new RoomService(store, avatars);
        realtime = new RoomRealtime(store, service, clock);
        room = service.createRoom(new PlayerIdentity.Google("private-google-subject"), "GM");
    }

    private Socket socket(PlayerIdentity.Google owner) {
        var client = new Client();
        return new Socket(realtime.open(client, owner), client);
    }
    private Socket gm() {
        var socket = socket(room.owner());
        send(socket, "RESUME_ROOM", room.id(), null, null, null);
        return socket;
    }
    private Socket join(GuestToken token, String nickname) {
        var socket = socket(null);
        send(socket, "JOIN_ROOM", room.id(), token, nickname, null);
        assertTrue(socket.connection.attached());
        return socket;
    }
    private void send(Socket socket, String type, String id, GuestToken token, String nickname, UUID target) {
        realtime.command(socket.connection, command(type, id, token, nickname, target));
    }
    private RoomProtocol.Command command(String type, String id, GuestToken token, String nickname, UUID target) {
        return new RoomProtocol.Command(type, UUID.randomUUID().toString(), id, nickname,
                token == null ? null : token.value(), target == null ? null : target.toString());
    }
    private Room current() { return store.find(room.id()).orElseThrow(); }

    @Test void attachmentIsRequiredAndRoomIdAloneGrantsNothing() {
        var socket = socket(null);
        send(socket, "GET_STATE", room.id(), null, null, null);
        assertEquals("NOT_AUTHORIZED", socket.client.result().code());
        send(socket, "RESUME_ROOM", room.id(), null, null, null);
        assertEquals("NOT_AUTHORIZED", socket.client.result().code());
        assertFalse(socket.connection.attached());
        assertEquals(0, socket.client.states().size());
    }

    @Test void joinAndServiceMembershipChangesBroadcastPersonalizedSnapshots() {
        var gm = gm();
        var token = GuestToken.generate();
        var guest = join(token, "Ken");
        var gmView = gm.client.state();
        var guestView = guest.client.state();
        assertEquals(2, gmView.players().size());
        assertEquals(gmView.players(), guestView.players());
        assertEquals(room.gmPlayerId(), gmView.currentPlayerId());
        assertEquals(current().playerFor(token.identity()).id(), guestView.currentPlayerId());
        assertTrue(gmView.isGm());
        assertFalse(guestView.isGm());
        assertEquals(List.of("GET_STATE", "GM_KICK_PLAYER", "GM_START_GAME", "GM_CLOSE_ROOM"), gmView.allowedActions());
        assertEquals(List.of("GET_STATE", "LEAVE_ROOM"), guestView.allowedActions());
        assertEquals("LOBBY", guestView.lifecycle());
        assertEquals(20, guestView.capacity());
        assertTrue(guestView.joinable());
        service.joinRoom(room.id(), "External join", GuestToken.generate());
        assertEquals(3, guest.client.state().players().size());
        assertEquals(3, gm.client.state().players().size());
    }

    @Test void serializedSnapshotsExcludeAllPrivateIdentityAndSynchronizationData() {
        var gm = gm();
        var token = GuestToken.generate();
        var guest = join(token, "Ken");
        UUID id = current().playerFor(token.identity()).id();
        send(gm, "GM_KICK_PLAYER", room.id(), null, null, id);
        String json = new JsonMapper().writeValueAsString(gm.client.state());
        assertFalse(json.contains(token.value()));
        assertFalse(json.contains(token.identity().fingerprint()));
        assertFalse(json.contains(room.owner().subject()));
        for (String field : List.of("kickedGuests", "owner", "identity", "fingerprint", "lock", "guestToken"))
            assertFalse(json.contains("\"" + field + "\""), field);
        assertEquals("PLAYER_KICKED", guest.client.terminal().reason());
    }

    @Test void crossRoomCommandsAndSubscriptionsCannotEscapeBoundRoom() {
        var a = join(GuestToken.generate(), "A");
        var other = service.createRoom(new PlayerIdentity.Google("other-owner"), "Other GM");
        var token = GuestToken.generate();
        var b = socket(null);
        send(b, "JOIN_ROOM", other.id(), token, "B", null);
        int beforeA = a.client.states().size();
        service.joinRoom(other.id(), "C", GuestToken.generate());
        assertEquals(beforeA, a.client.states().size());
        for (String type : List.of("GET_STATE", "LEAVE_ROOM", "RESUME_ROOM")) {
            send(a, type, other.id(), null, null, null);
            assertEquals("NOT_AUTHORIZED", a.client.result().code());
        }
        send(a, "GM_KICK_PLAYER", other.id(), null, null, b.client.state().currentPlayerId());
        assertEquals("NOT_AUTHORIZED", a.client.result().code());
        assertEquals(3, store.find(other.id()).orElseThrow().players().size());
        assertTrue(a.client.states().stream().allMatch(s -> s.room().roomId().equals(room.id())));
    }

    @Test void disconnectIsIdempotentPreservesMembershipAndBroadcasts() {
        var gm = gm();
        var token = GuestToken.generate();
        var guest = join(token, "Ken");
        Player original = current().playerFor(token.identity());
        realtime.disconnected(guest.connection);
        var disconnected = current().playerFor(token.identity());
        assertEquals(original.id(), disconnected.id());
        assertEquals(original.nickname(), disconnected.nickname());
        assertEquals(original.avatarId(), disconnected.avatarId());
        assertEquals(ConnectionState.DISCONNECTED, disconnected.connectionState());
        assertEquals(2, current().players().size());
        long revision = current().revision();
        realtime.disconnected(guest.connection);
        assertEquals(revision, current().revision());
        assertEquals("DISCONNECTED", gm.client.state().players().getLast().connectionStatus());
    }

    @Test void reconnectPreservesPlayerAndReceivesFreshCompleteState() {
        var token = GuestToken.generate();
        var guest = join(token, "Ken");
        var original = current().playerFor(token.identity());
        realtime.disconnected(guest.connection);
        service.joinRoom(room.id(), "New friend", GuestToken.generate());
        var back = socket(null);
        send(back, "RESUME_ROOM", room.id(), token, null, null);
        assertEquals(original, current().playerFor(token.identity()));
        assertEquals(original.id(), back.client.state().currentPlayerId());
        assertEquals(3, back.client.state().players().size());
        assertEquals(current().revision(), back.client.state().roomRevision());
    }

    @Test void newestConnectionWinsAndOldDisconnectCannotChangePresence() {
        var token = GuestToken.generate();
        var old = join(token, "Ken");
        var back = socket(null);
        send(back, "RESUME_ROOM", room.id(), token, null, null);
        assertEquals("REPLACED", old.client.terminal().reason());
        assertFalse(old.connection.attached());
        realtime.disconnected(old.connection);
        send(old, "LEAVE_ROOM", room.id(), null, null, null);
        assertTrue(back.connection.attached());
        assertEquals(ConnectionState.CONNECTED, current().playerFor(token.identity()).connectionState());
        assertEquals(2, current().players().size());
        int oldMessages = old.client.messages.size();
        join(GuestToken.generate(), "Another");
        assertEquals(oldMessages, old.client.messages.size());
    }

    @Test void gmDisconnectPreservesOwnershipAndWrongOwnerCannotResume() {
        var gm = gm();
        var guest = join(GuestToken.generate(), "Ken");
        realtime.disconnected(gm.connection);
        assertEquals(room.owner(), current().owner());
        assertEquals("DISCONNECTED", guest.client.state().players().getFirst().connectionStatus());
        var wrong = socket(new PlayerIdentity.Google("wrong"));
        send(wrong, "RESUME_ROOM", room.id(), null, null, null);
        assertEquals("NOT_AUTHORIZED", wrong.client.result().code());
        assertEquals(0, wrong.client.states().size());
        var back = gm();
        assertTrue(back.client.state().isGm());
        assertEquals(room.gmPlayerId(), back.client.state().currentPlayerId());
        assertEquals(room.players().getFirst(), current().playerFor(room.owner()));
    }

    @Test void guestCannotKickAndGmCannotLeaveOrKickSelf() {
        var gm = gm();
        var guest = join(GuestToken.generate(), "Ken");
        send(guest, "GM_KICK_PLAYER", room.id(), null, null, room.gmPlayerId());
        assertEquals("NOT_AUTHORIZED", guest.client.result().code());
        send(gm, "LEAVE_ROOM", room.id(), null, null, null);
        assertEquals("GM_CANNOT_LEAVE", gm.client.result().code());
        send(gm, "GM_KICK_PLAYER", room.id(), null, null, room.gmPlayerId());
        assertEquals("GM_CANNOT_KICK_SELF", gm.client.result().code());
    }

    @Test void kickRevokesRecipientAndPreventsReconnectionAndLaterSnapshots() {
        var gm = gm();
        var token = GuestToken.generate();
        var guest = join(token, "Ken");
        send(gm, "GM_KICK_PLAYER", room.id(), null, null, guest.client.state().currentPlayerId());
        assertEquals("ACCESS_REVOKED", guest.client.terminal().type());
        assertEquals("PLAYER_KICKED", guest.client.terminal().reason());
        assertEquals(1, gm.client.state().players().size());
        int before = guest.client.messages.size();
        join(GuestToken.generate(), "Another");
        assertEquals(before, guest.client.messages.size());
        var retry = socket(null);
        send(retry, "RESUME_ROOM", room.id(), token, null, null);
        assertEquals("PLAYER_KICKED", retry.client.result().code());
        assertTrue(retry.client.states().isEmpty());
    }

    @Test void leaveRemovesMemberAndLaterJoinCreatesNewPlayer() {
        var gm = gm();
        var token = GuestToken.generate();
        var guest = join(token, "Ken");
        UUID before = guest.client.state().currentPlayerId();
        send(guest, "LEAVE_ROOM", room.id(), null, null, null);
        assertEquals("LEFT", guest.client.terminal().reason());
        assertEquals(1, gm.client.state().players().size());
        var back = join(token, "Ken");
        assertNotEquals(before, back.client.state().currentPlayerId());
    }

    @Test void scheduledExpirationNotifiesAllClientsOnceAndStopsAccess() {
        var gm = gm();
        var guest = join(GuestToken.generate(), "Ken");
        clock.advance(Duration.ofHours(48));
        assertEquals(1, store.expireRooms().size());
        for (var socket : List.of(gm, guest)) {
            assertEquals(new RoomProtocol.Terminal("ROOM_ENDED", "EXPIRED"), socket.client.terminal());
            assertFalse(socket.connection.attached());
            int count = socket.client.messages.size();
            send(socket, "GET_STATE", room.id(), null, null, null);
            realtime.disconnected(socket.connection);
            assertEquals(count, socket.client.messages.size());
        }
        assertTrue(store.expireRooms().isEmpty());
        assertTrue(store.find(room.id()).isEmpty());
    }

    @Test void requestTriggeredExpirationAndManualRemovalHaveTerminalNotifications() {
        var gm = gm();
        clock.advance(Duration.ofHours(48));
        send(gm, "GET_STATE", room.id(), null, null, null);
        assertEquals("EXPIRED", gm.client.terminal().reason());
        room = service.createRoom(room.owner(), "GM");
        var newer = gm();
        store.remove(room.id());
        assertEquals(new RoomProtocol.Terminal("ROOM_ENDED", "CLOSED"), newer.client.terminal());
    }

    @Test void duplicateRequestDoesNotRepeatMutationAndReturnsFreshState() {
        var gm = gm();
        var guest = join(GuestToken.generate(), "Ken");
        var kick = command("GM_KICK_PLAYER", room.id(), null, null, guest.client.state().currentPlayerId());
        realtime.command(gm.connection, kick);
        long revision = current().revision();
        realtime.command(gm.connection, kick);
        assertTrue(gm.client.result().accepted());
        assertEquals(revision, current().revision());
        assertEquals(revision, gm.client.state().roomRevision());
        clock.advance(Duration.ofMinutes(2));
        realtime.command(gm.connection, kick);
        assertEquals("PLAYER_NOT_FOUND", gm.client.result().code());
    }

    @Test void malformedCommandAndUnexpectedExceptionsReturnSafeErrors() {
        var client = socket(null);
        send(client, "NOT_A_COMMAND", room.id(), null, null, null);
        assertEquals("INVALID_INPUT", client.client.result().code());
        realtime.command(client.connection, new RoomProtocol.Command("RESUME_ROOM", "private-data",
                room.id(), null, null, null));
        assertNull(client.client.result().requestId());
        var failingStore = mock(RoomStore.class);
        when(failingStore.inRoom(anyString(), any())).thenThrow(new IllegalStateException("private-google-subject"));
        var failed = new RoomRealtime(failingStore, service, clock);
        var output = new Client();
        failed.command(failed.open(output, null), command("RESUME_ROOM", room.id(), null, null, null));
        assertEquals("INTERNAL_ERROR", output.result().code());
        assertFalse(new JsonMapper().writeValueAsString(output.messages).contains("private-google-subject"));
    }

    @Test void concurrentJoinAndAttachNeverMissesNewestSnapshotOrExceedsCapacity() throws Exception {
        var gm = gm();
        List<Socket> clients = new ArrayList<>();
        var actions = new ArrayList<Runnable>();
        for (int i = 0; i < 30; i++) {
            var client = socket(null);
            clients.add(client);
            String name = "P" + i;
            actions.add(() -> send(client, "JOIN_ROOM", room.id(), GuestToken.generate(), name, null));
        }
        together(actions);
        assertEquals(20, current().players().size());
        assertEquals(19, clients.stream().filter(s -> s.connection.attached()).count());
        assertEquals(20, gm.client.state().players().size());
        for (var client : clients) {
            if (client.connection.attached()) {
                assertEquals(current().revision(), client.client.state().roomRevision());
                assertEquals(20, client.client.state().players().size());
                long previous = -1;
                for (var state : client.client.states()) {
                    assertTrue(state.room().roomRevision() >= previous);
                    previous = state.room().roomRevision();
                }
            } else assertEquals("ROOM_FULL", client.client.result().code());
        }
    }

    @Test void concurrentReplacementAndStaleCloseKeepNewestPlayerConnected() throws Exception {
        for (int i = 0; i < 25; i++) {
            var token = GuestToken.generate();
            var old = join(token, "Ken" + i);
            var back = socket(null);
            together(List.of(() -> realtime.disconnected(old.connection),
                    () -> send(back, "RESUME_ROOM", room.id(), token, null, null)));
            assertTrue(back.connection.attached());
            assertEquals(ConnectionState.CONNECTED, current().playerFor(token.identity()).connectionState());
            send(back, "LEAVE_ROOM", room.id(), null, null, null);
        }
    }

    @Test void concurrentKickAndResumeNeverRestoresRevokedAccess() throws Exception {
        var gm = gm();
        for (int i = 0; i < 25; i++) {
            var token = GuestToken.generate();
            var old = join(token, "Ken" + i);
            var back = socket(null);
            UUID target = old.client.state().currentPlayerId();
            together(List.of(() -> send(gm, "GM_KICK_PLAYER", room.id(), null, null, target),
                    () -> send(back, "RESUME_ROOM", room.id(), token, null, null)));
            assertFalse(old.connection.attached());
            assertFalse(back.connection.attached());
            assertEquals(1, current().players().size());
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

    record Socket(RoomRealtime.Connection connection, Client client) {}
    static final class Client implements RoomRealtime.Client {
        final List<RoomProtocol.Message> messages = new CopyOnWriteArrayList<>();
        public void enqueue(RoomProtocol.Message message) { messages.add(message); }
        public void terminate(RoomProtocol.Terminal message) { messages.add(message); }
        List<RoomProtocol.State> states() { return messages.stream().filter(RoomProtocol.State.class::isInstance)
                .map(RoomProtocol.State.class::cast).toList(); }
        RoomProtocol.Snapshot state() { return states().getLast().room(); }
        RoomProtocol.Result result() { return messages.stream().filter(RoomProtocol.Result.class::isInstance)
                .map(RoomProtocol.Result.class::cast).toList().getLast(); }
        RoomProtocol.Terminal terminal() { return messages.stream().filter(RoomProtocol.Terminal.class::isInstance)
                .map(RoomProtocol.Terminal.class::cast).toList().getLast(); }
    }
    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-26T00:00:00Z"));
        void advance(Duration amount) { now.updateAndGet(value -> value.plus(amount)); }
        public Instant instant() { return now.get(); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
    }
}
