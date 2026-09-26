package com.drinkduel.realtime;

import com.drinkduel.game.WhoAmIState;
import com.drinkduel.room.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class WhoAmIRealtimeTests {
    private RoomStore store;
    private RoomService service;
    private RoomRealtime realtime;
    private Room room;
    private GuestToken token;
    private Socket gm;
    private Socket guest;
    private final List<Socket> sockets = new ArrayList<>();

    @BeforeEach void setup() {
        var avatars = new AvatarCatalog();
        store = new InMemoryRoomStore(Clock.systemUTC(), avatars);
        service = new RoomService(store, avatars);
        realtime = new RoomRealtime(store, service, Clock.systemUTC());
        room = service.createRoom(new PlayerIdentity.Google("private-owner"), "GM");
        gm = socket(room.owner());
        realtime.command(gm.connection, new RoomProtocol.Command("RESUME_ROOM", requestId(), room.id(), null, null, null));
        token = GuestToken.generate();
        guest = socket(null);
        realtime.command(guest.connection, new RoomProtocol.Command("JOIN_ROOM", requestId(), room.id(), "Ken", token.value(), null));
    }
    private String requestId() { return UUID.randomUUID().toString(); }
    private Socket socket(PlayerIdentity.Google owner) {
        var client = new RoomRealtimeTests.Client();
        var socket = new Socket(realtime.open(client, owner), client);
        sockets.add(socket);
        return socket;
    }
    private Room current() { return store.find(room.id()).orElseThrow(); }
    private UUID sessionId() { return current().currentSession().orElseThrow().id(); }
    private WhoAmIState state() { return (WhoAmIState) current().currentSession().orElseThrow().state(); }
    private void open() {
        realtime.command(gm.connection, new RoomProtocol.Command("GM_START_GAME", requestId(), room.id(), null, null, null));
        assertTrue(gm.client.result().accepted());
    }
    private RoomProtocol.Command command(String type, UUID target, String secret, UUID expected) {
        return new RoomProtocol.Command(type, requestId(), room.id(), null, null,
                target == null ? null : target.toString(), sessionId().toString(), secret,
                expected == null ? null : expected.toString());
    }
    private void submit(Socket socket, String text) {
        realtime.command(socket.connection, command("SUBMIT_NAME", null, text, null));
    }
    private void assertNoSecrets(String... secrets) {
        var mapper = new JsonMapper();
        for (Socket socket : sockets) for (var message : socket.client.messages) {
            String json = mapper.writeValueAsString(message);
            for (String secret : secrets) assertFalse(json.contains(secret), "Secret appeared in outgoing message");
            for (String field : List.of("secretName", "text", "submissions", "submitterNickname", "submitterPlayerId"))
                assertFalse(json.contains("\"" + field + "\":"), "Unexpected secret field " + field);
        }
    }

    @Test void onlyGmOpensAndEveryoneReceivesSubmitPhaseWithClosedJoining() {
        realtime.command(guest.connection, new RoomProtocol.Command("GM_START_GAME", requestId(), room.id(), null, null, null));
        assertEquals("NOT_AUTHORIZED", guest.client.result().code());
        open();
        for (var client : List.of(gm.client, guest.client)) {
            assertEquals("WHO_AM_I", client.state().game().gameType());
            assertEquals("SUBMIT_NAME", client.state().game().phase());
            assertEquals(2, client.state().game().participantCount());
            assertFalse(client.state().game().readyForShuffle());
            assertFalse(client.state().joinable());
            assertTrue(client.state().allowedActions().contains("SUBMIT_NAME"));
            assertFalse(client.state().allowedActions().contains("LEAVE_ROOM"));
        }
    }

    @Test void submissionProgressAndFutureShufflePermissionAreSafeForEveryRecipient() {
        open();
        submit(guest, "SECRET-DORAEMON");
        assertEquals(1, gm.client.state().game().submittedCount());
        assertTrue(guest.client.state().game().currentPlayerSubmitted());
        assertFalse(gm.client.state().game().currentPlayerSubmitted());
        assertFalse(guest.client.state().allowedActions().contains("SUBMIT_NAME"));
        assertFalse(gm.client.state().game().readyForShuffle());
        submit(gm, "SECRET-DORAEMON");
        assertTrue(gm.client.state().game().readyForShuffle());
        assertTrue(gm.client.state().game().canShuffleWhenImplemented());
        assertFalse(guest.client.state().game().canShuffleWhenImplemented());
        assertTrue(guest.client.state().game().participants().stream().allMatch(RoomProtocol.ParticipantView::submitted));
        assertTrue(guest.client.state().game().participants().stream().allMatch(p -> p.resetSubmissionId() == null));
        assertFalse(gm.client.state().allowedActions().contains("GM_SHUFFLE"));
        assertNoSecrets("SECRET-DORAEMON", token.value(), token.identity().fingerprint(), room.owner().subject());
    }

    @Test void resetBroadcastsProgressResubmissionAndStaleResetReturnsFreshSafeState() {
        open();
        submit(guest, "SECRET-OLD");
        UUID player = guest.client.state().currentPlayerId();
        UUID expected = gm.client.state().game().participants().stream().filter(p -> p.playerId().equals(player))
                .findFirst().orElseThrow().resetSubmissionId();
        var reset = command("GM_RESET_SUBMISSION", player, null, expected);
        realtime.command(guest.connection, reset);
        assertEquals("NOT_AUTHORIZED", guest.client.result().code());
        realtime.command(gm.connection, reset);
        assertEquals(0, guest.client.state().game().submittedCount());
        assertTrue(guest.client.state().allowedActions().contains("SUBMIT_NAME"));
        submit(guest, "SECRET-NEW");
        realtime.command(gm.connection, command("GM_RESET_SUBMISSION", player, null, expected));
        assertEquals("STALE_COMMAND", gm.client.result().code());
        assertEquals(current().revision(), gm.client.state().roomRevision());
        assertEquals("SECRET-NEW", state().submissions().get(player).text());
        assertNoSecrets("SECRET-OLD", "SECRET-NEW");
    }

    @Test void duplicateSubmitRequestAfterResetCannotReapplyOldSecret() {
        open();
        var original = command("SUBMIT_NAME", null, "SECRET-ONCE", null);
        realtime.command(guest.connection, original);
        UUID player = guest.client.state().currentPlayerId();
        realtime.command(gm.connection, command("GM_RESET_SUBMISSION", player, null, state().submissions().get(player).id()));
        realtime.command(guest.connection, original);
        assertTrue(state().submissions().isEmpty());
        assertFalse(guest.client.state().game().currentPlayerSubmitted());
        assertNoSecrets("SECRET-ONCE");
    }

    @Test void gmOfflineAllowsGuestSubmissionAndOwnerResumeRestoresControls() {
        open();
        UUID id = sessionId();
        realtime.disconnected(gm.connection);
        submit(guest, "SECRET-OFFLINE");
        assertTrue(guest.client.result().accepted());
        assertFalse(guest.client.state().game().readyForShuffle());
        var back = socket(room.owner());
        realtime.command(back.connection, new RoomProtocol.Command("RESUME_ROOM", requestId(), room.id(), null, null, null));
        assertEquals(id, back.client.state().sessionId());
        assertEquals(1, back.client.state().game().submittedCount());
        assertTrue(back.client.state().allowedActions().contains("GM_RESET_SUBMISSION"));
        assertTrue(back.client.state().allowedActions().contains("GM_KICK_PLAYER"));
        assertNoSecrets("SECRET-OFFLINE");
    }

    @Test void newJoinsAndLeaveRejectedButResumePreservesSubmittedStateAndAvatar() {
        open();
        submit(guest, "SECRET-RESTORED");
        var original = guest.client.state();
        var newcomer = socket(null);
        realtime.command(newcomer.connection, new RoomProtocol.Command("JOIN_ROOM", requestId(), room.id(), "Late", GuestToken.generate().value(), null));
        assertEquals("GAME_IN_PROGRESS", newcomer.client.result().code());
        realtime.command(guest.connection, new RoomProtocol.Command("LEAVE_ROOM", requestId(), room.id(), null, null, null));
        assertEquals("GAME_IN_PROGRESS", guest.client.result().code());
        realtime.disconnected(guest.connection);
        submit(gm, "SECRET-GM");
        assertFalse(gm.client.state().game().readyForShuffle());
        var back = socket(null);
        realtime.command(back.connection, new RoomProtocol.Command("RESUME_ROOM", requestId(), room.id(), null, token.value(), null));
        assertEquals(original.currentPlayerId(), back.client.state().currentPlayerId());
        assertEquals(original.players(), back.client.state().players());
        assertTrue(back.client.state().game().currentPlayerSubmitted());
        assertTrue(back.client.state().game().readyForShuffle());
        assertNoSecrets("SECRET-RESTORED", "SECRET-GM");
    }

    @Test void kickUpdatesParticipantsSubmissionCountsAndRevokesAccess() {
        var third = GuestToken.generate();
        UUID thirdId = service.joinRoom(room.id(), "Third", third).playerFor(third.identity()).id();
        open();
        submit(gm, "SECRET-GM");
        submit(guest, "SECRET-GUEST");
        assertFalse(gm.client.state().game().readyForShuffle());
        realtime.command(gm.connection, command("GM_KICK_PLAYER", thirdId, null, null));
        assertTrue(gm.client.state().game().readyForShuffle());
        realtime.command(gm.connection, command("GM_KICK_PLAYER", guest.client.state().currentPlayerId(), null, null));
        assertEquals("PLAYER_KICKED", guest.client.terminal().reason());
        assertEquals(1, gm.client.state().game().participantCount());
        assertEquals(1, gm.client.state().game().submittedCount());
        assertFalse(gm.client.state().game().readyForShuffle());
        assertEquals("SUBMIT_NAME", gm.client.state().game().phase());
        assertNoSecrets("SECRET-GM", "SECRET-GUEST");
    }

    @Test void invalidSecretImpersonationStaleSessionAndShuffleCannotMutate() {
        open();
        submit(guest, " ");
        assertEquals("INVALID_SUBMISSION", guest.client.result().code());
        realtime.command(guest.connection, command("SUBMIT_NAME", room.gmPlayerId(), "SECRET-FORGED", null));
        assertEquals("INVALID_INPUT", guest.client.result().code());
        realtime.command(guest.connection, new RoomProtocol.Command("SUBMIT_NAME", requestId(), room.id(),
                null, null, null, UUID.randomUUID().toString(), "SECRET-STALE", null));
        assertEquals("STALE_COMMAND", guest.client.result().code());
        assertEquals(current().revision(), guest.client.state().roomRevision());
        realtime.command(gm.connection, command("GM_SHUFFLE", null, null, null));
        assertEquals("INVALID_INPUT", gm.client.result().code());
        assertTrue(state().submissions().isEmpty());
        assertNoSecrets("SECRET-FORGED", "SECRET-STALE");
    }

    @Test void reconnectRacingSubmissionSeesTheFinalCompleteSnapshot() throws Exception {
        open();
        realtime.disconnected(guest.connection);
        var back = socket(null);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var reconnect = executor.submit(() -> {
                start.await();
                realtime.command(back.connection, new RoomProtocol.Command("RESUME_ROOM", requestId(), room.id(), null, token.value(), null));
                return null;
            });
            var submit = executor.submit(() -> { start.await(); submit(gm, "SECRET-RACE"); return null; });
            start.countDown();
            reconnect.get(5, TimeUnit.SECONDS);
            submit.get(5, TimeUnit.SECONDS);
        }
        assertEquals(current().revision(), back.client.state().roomRevision());
        assertEquals(1, back.client.state().game().submittedCount());
        assertNoSecrets("SECRET-RACE");
    }

    private record Socket(RoomRealtime.Connection connection, RoomRealtimeTests.Client client) {}
}
