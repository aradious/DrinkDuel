package com.drinkduel.room;

import com.drinkduel.game.GameSession;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;

/** Immutable internal aggregate, not an outbound view model. */
public final class Room {
    public static final int MAX_PLAYERS = 20;
    public static final int MIN_ACTIVE_PLAYERS = 2;
    public static final Duration LIFETIME = Duration.ofHours(48);

    private final String id;
    private final Instant createdAt;
    private final PlayerIdentity.Owner owner;
    private final UUID gmPlayerId;
    private final List<Player> players;
    private final GameSession currentSession;
    private final long revision;
    private final Set<PlayerIdentity.Guest> kickedGuests;

    private Room(String id, Instant createdAt, PlayerIdentity.Owner owner, UUID gmPlayerId,
                 List<Player> players, GameSession currentSession, long revision,
                 Set<PlayerIdentity.Guest> kickedGuests) {
        this.id = id;
        this.createdAt = createdAt;
        this.owner = owner;
        this.gmPlayerId = gmPlayerId;
        this.players = List.copyOf(players);
        this.currentSession = currentSession;
        this.revision = revision;
        this.kickedGuests = Set.copyOf(kickedGuests);
    }

    static Room create(String id, Instant createdAt, Player gm) {
        if (!(gm.identity() instanceof PlayerIdentity.Owner owner))
            throw new DomainException(DomainException.Code.NOT_AUTHORIZED);
        return new Room(id, createdAt, owner, gm.id(), List.of(gm), null, 0, Set.of());
    }

    public String id() { return id; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return createdAt.plus(LIFETIME); }
    public PlayerIdentity.Owner owner() { return owner; }
    public UUID gmPlayerId() { return gmPlayerId; }
    public List<Player> players() { return players; }
    public Optional<GameSession> currentSession() { return Optional.ofNullable(currentSession); }
    public long revision() { return revision; }
    public boolean isLobby() { return currentSession == null; }
    public boolean isExpired(Instant now) { return !now.isBefore(expiresAt()); }

    public Room addPlayer(Player player) {
        Objects.requireNonNull(player);
        requireNotKicked(player.identity());
        if (!isLobby()) throw new DomainException(DomainException.Code.GAME_IN_PROGRESS);
        if (!(player.identity() instanceof PlayerIdentity.Guest))
            throw new DomainException(DomainException.Code.NOT_AUTHORIZED);
        if (players.stream().anyMatch(p -> p.id().equals(player.id()) || p.identity().equals(player.identity())))
            throw new DomainException(DomainException.Code.IDENTITY_ALREADY_JOINED);
        // Preserve entered spelling. No unconfirmed case-folding or nickname rewriting policy.
        if (players.stream().anyMatch(p -> p.nickname().equals(player.nickname())))
            throw new DomainException(DomainException.Code.NICKNAME_TAKEN);
        if (players.size() >= MAX_PLAYERS) throw new DomainException(DomainException.Code.ROOM_FULL);
        var updated = new ArrayList<>(players);
        updated.add(player);
        return copy(updated);
    }

    public Player playerFor(PlayerIdentity identity) {
        requireNotKicked(identity);
        return players.stream().filter(p -> p.identity().equals(identity)).findFirst()
                .orElseThrow(() -> new DomainException(DomainException.Code.NOT_AUTHORIZED));
    }

    public Room updateConnection(PlayerIdentity identity, ConnectionState state) {
        Objects.requireNonNull(state);
        Player existing = playerFor(identity);
        if (existing.connectionState() == state) return this;
        return copy(players.stream().map(p -> p.id().equals(existing.id())
                ? p.withConnectionState(state) : p).toList());
    }

    /** The start minimum does not remove disconnected members from participation. */
    public void requireSessionStartAllowed() {
        if (!isLobby()) throw new DomainException(DomainException.Code.GAME_IN_PROGRESS);
        requireMinimumActivePlayers();
    }

    void requireMinimumActivePlayers() {
        long connected = players.stream().filter(p -> p.connectionState() == ConnectionState.CONNECTED).count();
        if (connected < MIN_ACTIVE_PLAYERS) throw new DomainException(DomainException.Code.NOT_ENOUGH_PLAYERS);
    }

    private Room copy(List<Player> updated) {
        return new Room(id, createdAt, owner, gmPlayerId, updated, currentSession, revision, kickedGuests);
    }

    Room committed(long nextRevision) {
        return new Room(id, createdAt, owner, gmPlayerId, players, currentSession, nextRevision, kickedGuests);
    }

    void requireNotKicked(PlayerIdentity identity) {
        if (kickedGuests.contains(identity)) throw new DomainException(DomainException.Code.PLAYER_KICKED);
    }

    Room joinGuest(String nickname, PlayerIdentity.Guest identity, AvatarCatalog avatars) {
        requireNotKicked(identity);
        if (!isLobby()) throw new DomainException(DomainException.Code.GAME_IN_PROGRESS);
        DomainException.requireText(nickname);
        if (players.stream().anyMatch(p -> p.identity().equals(identity)))
            throw new DomainException(DomainException.Code.IDENTITY_ALREADY_JOINED);
        if (players.stream().anyMatch(p -> p.nickname().equals(nickname)))
            throw new DomainException(DomainException.Code.NICKNAME_TAKEN);
        if (players.size() >= MAX_PLAYERS) throw new DomainException(DomainException.Code.ROOM_FULL);
        return addPlayer(Player.create(nickname, identity, avatars));
    }

    Room leave(PlayerIdentity identity) {
        Player player = playerFor(identity);
        if (player.id().equals(gmPlayerId)) throw new DomainException(DomainException.Code.GM_CANNOT_LEAVE);
        if (!isLobby()) throw new DomainException(DomainException.Code.GAME_IN_PROGRESS);
        return copy(players.stream().filter(p -> !p.id().equals(player.id())).toList());
    }

    void requireGm(PlayerIdentity actor) {
        Player gm = playerFor(actor);
        if (!owner.equals(actor) || !gm.id().equals(gmPlayerId)
                || gm.connectionState() != ConnectionState.CONNECTED)
            throw new DomainException(DomainException.Code.NOT_AUTHORIZED);
    }

    Room startSession(PlayerIdentity actor, GameSession session) {
        requireGm(actor);
        requireSessionStartAllowed();
        return withSession(session);
    }

    Room withSession(GameSession session) {
        return new Room(id, createdAt, owner, gmPlayerId, players,
                Objects.requireNonNull(session), revision, kickedGuests);
    }

    Room withoutSession() {
        return new Room(id, createdAt, owner, gmPlayerId, players, null, revision, kickedGuests);
    }

    Room kick(PlayerIdentity actor, UUID targetId) {
        requireGm(actor);
        if (gmPlayerId.equals(targetId)) throw new DomainException(DomainException.Code.GM_CANNOT_KICK_SELF);
        Player target = players.stream().filter(p -> p.id().equals(targetId)).findFirst()
                .orElseThrow(() -> new DomainException(DomainException.Code.PLAYER_NOT_FOUND));
        var blocked = new HashSet<>(kickedGuests);
        blocked.add((PlayerIdentity.Guest) target.identity());
        var updatedSession = currentSession == null ? null : currentSession.withoutParticipant(targetId);
        return new Room(id, createdAt, owner, gmPlayerId,
                players.stream().filter(p -> !p.id().equals(targetId)).toList(), updatedSession, revision, blocked);
    }
}
