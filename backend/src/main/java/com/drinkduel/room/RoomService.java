package com.drinkduel.room;

import com.drinkduel.game.GameSession;
import com.drinkduel.game.WhoAmIState;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Internal application boundary. Returned snapshots must never be serialized directly to clients.
 * Google identities must come from a verified authentication adapter, not client-supplied subjects.
 */
@Service
public final class RoomService {
    private final RoomStore store;
    private final AvatarCatalog avatars;
    private final java.security.SecureRandom shuffleRandom = new java.security.SecureRandom();

    public RoomService(RoomStore store, AvatarCatalog avatars) {
        this.store = Objects.requireNonNull(store);
        this.avatars = Objects.requireNonNull(avatars);
    }

    public Room createRoom(PlayerIdentity.Google verifiedOwner, String nickname) {
        return store.create(verifiedOwner, nickname);
    }

    public Room joinRoom(String roomId, String nickname, GuestToken token) {
        var identity = Objects.requireNonNull(token).identity();
        return store.mutate(roomId, room -> room.joinGuest(nickname, identity, avatars));
    }

    public Room reconnectPlayer(String roomId, GuestToken token) {
        return presence(roomId, Objects.requireNonNull(token).identity(), ConnectionState.CONNECTED);
    }

    public Room reconnectGm(String roomId, PlayerIdentity.Google verifiedOwner) {
        return presence(roomId, Objects.requireNonNull(verifiedOwner), ConnectionState.CONNECTED);
    }

    /** The future connection registry calls this only when no validated connections remain. */
    public Room disconnectPlayer(String roomId, PlayerIdentity authenticatedIdentity) {
        return presence(roomId, Objects.requireNonNull(authenticatedIdentity), ConnectionState.DISCONNECTED);
    }

    public Room leaveRoom(String roomId, PlayerIdentity authenticatedIdentity) {
        Objects.requireNonNull(authenticatedIdentity);
        return store.mutate(roomId, room -> room.leave(authenticatedIdentity));
    }

    public Room kickPlayer(String roomId, PlayerIdentity authenticatedActor, UUID targetId) {
        Objects.requireNonNull(authenticatedActor);
        Objects.requireNonNull(targetId);
        return store.mutate(roomId, room -> room.kick(authenticatedActor, targetId));
    }

    private Room presence(String roomId, PlayerIdentity identity, ConnectionState state) {
        return store.mutate(roomId, room -> room.updateConnection(identity, state));
    }

    public Room startWhoAmI(String roomId, PlayerIdentity actor) {
        return store.mutate(roomId, room -> {
            room.requireGm(actor);
            room.requireSessionStartAllowed();
            var state = new WhoAmIState(room.players().stream().map(Player::id).toList());
            return room.startSession(actor, new GameSession(UUID.randomUUID(), state));
        });
    }

    public Room submitName(String roomId, PlayerIdentity actor, UUID sessionId, String text) {
        return store.mutate(roomId, room -> {
            Player player = room.playerFor(actor);
            var state = submissionState(room, sessionId);
            return room.withSession(new GameSession(sessionId, state.submit(player.id(), player.nickname(), text)));
        });
    }

    public Room resetSubmission(String roomId, PlayerIdentity actor, UUID sessionId,
                                UUID targetPlayerId, UUID expectedSubmissionId) {
        return store.mutate(roomId, room -> {
            room.requireGm(actor);
            var state = submissionState(room, sessionId);
            return room.withSession(new GameSession(sessionId, state.reset(targetPlayerId, expectedSubmissionId)));
        });
    }

    public Room shuffle(String roomId, PlayerIdentity actor, UUID sessionId) {
        return store.mutate(roomId, room -> {
            room.requireGm(actor);
            var state = submissionState(room, sessionId);
            var connected = room.players().stream()
                    .filter(p -> p.connectionState() == ConnectionState.CONNECTED)
                    .map(Player::id).collect(java.util.stream.Collectors.toSet());
            return room.withSession(new GameSession(sessionId, state.shuffle(connected, shuffleRandom)));
        });
    }

    private WhoAmIState submissionState(Room room, UUID sessionId) {
        var session = room.currentSession()
                .orElseThrow(() -> new DomainException(DomainException.Code.INVALID_GAME_PHASE));
        if (!session.id().equals(sessionId)) throw new DomainException(DomainException.Code.STALE_COMMAND);
        if (!(session.state() instanceof WhoAmIState state) || state.phase() != WhoAmIState.Phase.SUBMIT_NAME)
            throw new DomainException(DomainException.Code.INVALID_GAME_PHASE);
        return state;
    }
}
