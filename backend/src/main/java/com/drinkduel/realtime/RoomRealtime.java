package com.drinkduel.realtime;

import com.drinkduel.room.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.drinkduel.room.DomainException.Code.*;

/** Coordinates subscriptions and RoomService on the STORE's existing per-room lock.
 * Client methods only enqueue; they must never perform network I/O here.
 */
public final class RoomRealtime implements RoomStore.Listener {
    public interface Client {
        void enqueue(RoomProtocol.Message message);
        void terminate(RoomProtocol.Terminal message);
    }

    public static final class Connection {
        private final Client client;
        private final PlayerIdentity.Google owner;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Binding binding;
        private Connection(Client client, PlayerIdentity.Google owner) {
            this.client = client;
            this.owner = owner;
        }
        public boolean attached() { return binding != null && !closed.get(); }
    }
    private record Binding(String roomId, UUID instance, UUID playerId, PlayerIdentity identity) {}
    private record Cached(String type, String target, String sessionId, String expectedSubmissionId,
                          RoomProtocol.Result result, Instant until) {}
    private static final class Channel {
        final UUID instance;
        final Map<UUID, Connection> active = new HashMap<>();
        final Map<UUID, LinkedHashMap<String, Cached>> requests = new HashMap<>();
        Channel(Room room) { instance = room.gmPlayerId(); }
    }

    private final RoomStore store;
    private final RoomService service;
    private final Clock clock;
    private final RoomViewFactory views = new RoomViewFactory();
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    public RoomRealtime(RoomStore store, RoomService service, Clock clock) {
        this.store = store;
        this.service = service;
        this.clock = clock;
        store.addListener(this);
    }

    public Connection open(Client client, PlayerIdentity.Google trustedOwner) {
        return new Connection(Objects.requireNonNull(client), trustedOwner);
    }

    public void command(Connection connection, RoomProtocol.Command command) {
        // Serializes attachment vs close on one socket, before taking the room lock.
        // Store notifications never acquire this monitor.
        synchronized (connection) {
            if (connection.closed.get()) return;
            try {
                validate(command);
                store.inRoom(command.roomId(), initial -> {
                    if (connection.closed.get()) return null;
                    if (connection.binding == null) attach(connection, command);
                    else {
                        try { execute(connection, command, initial); }
                        catch (DomainException exception) {
                            if (exception.code() == STALE_COMMAND)
                                connection.client.enqueue(views.forPlayer(initial, connection.binding.playerId()));
                            throw exception;
                        }
                    }
                    return null;
                });
            } catch (DomainException exception) {
                connection.client.enqueue(new RoomProtocol.Result(safeRequestId(command.requestId()),
                        false, exception.code().name(), null));
            } catch (RuntimeException exception) {
                // Never reflect exception messages or payload values into protocol errors.
                connection.client.enqueue(new RoomProtocol.Result(safeRequestId(command.requestId()),
                        false, "INTERNAL_ERROR", null));
            }
        }
    }

    private void attach(Connection connection, RoomProtocol.Command command) {
        Room room;
        PlayerIdentity identity;
        if (command.type().equals("JOIN_ROOM")) {
            GuestToken token = GuestToken.parse(command.guestToken());
            identity = token.identity();
            room = service.joinRoom(command.roomId(), command.nickname(), token);
        } else if (command.type().equals("RESUME_ROOM")) {
            if (command.guestToken() != null) {
                GuestToken token = GuestToken.parse(command.guestToken());
                identity = token.identity();
                room = service.reconnectPlayer(command.roomId(), token);
            } else {
                if (connection.owner == null) throw new DomainException(NOT_AUTHORIZED);
                identity = connection.owner;
                room = service.reconnectGm(command.roomId(), connection.owner);
            }
        } else throw new DomainException(NOT_AUTHORIZED);
        Player player = room.playerFor(identity);
        var channel = channels.computeIfAbsent(room.id(), ignored -> new Channel(room));
        connection.binding = new Binding(room.id(), room.gmPlayerId(), player.id(), identity);
        Connection previous = channel.active.put(player.id(), connection);
        // Newest valid connection wins. The old socket loses authority before it is closed.
        if (previous != null && previous != connection) revoke(previous, "REPLACED");
        connection.client.enqueue(views.forPlayer(room, player.id()));
        var result = new RoomProtocol.Result(command.requestId(), true, null, room.revision());
        remember(channel, player.id(), command, result);
        connection.client.enqueue(result);
    }

    private void execute(Connection connection, RoomProtocol.Command command, Room initial) {
        Binding binding = connection.binding;
        Channel channel = channels.get(binding.roomId());
        if (!binding.roomId().equals(command.roomId()) || !binding.instance().equals(initial.gmPlayerId())
                || channel == null || channel.active.get(binding.playerId()) != connection)
            throw new DomainException(NOT_AUTHORIZED);
        initial.playerFor(binding.identity());
        if ((command.type().equals("SUBMIT_NAME") || command.type().equals("GM_RESET_SUBMISSION") || command.type().equals("GM_SHUFFLE")
                || command.type().equals("GM_KICK_PLAYER")) && !initial.isLobby()
                && !initial.currentSession().orElseThrow().id().toString().equals(command.sessionId()))
            throw new DomainException(STALE_COMMAND);
        var cache = channel.requests.computeIfAbsent(binding.playerId(), ignored -> new LinkedHashMap<>());
        cache.values().removeIf(value -> !clock.instant().isBefore(value.until()));
        var cached = cache.get(command.requestId());
        if (cached != null) {
            if (!cached.type().equals(command.type()) || !Objects.equals(cached.target(), command.targetPlayerId())
                    || !Objects.equals(cached.sessionId(), command.sessionId())
                    || !Objects.equals(cached.expectedSubmissionId(), command.expectedSubmissionId()))
                throw new DomainException(INVALID_INPUT);
            connection.client.enqueue(views.forPlayer(initial, binding.playerId()));
            connection.client.enqueue(cached.result());
            return;
        }
        Room result;
        switch (command.type()) {
            case "GET_STATE" -> {
                result = initial;
                connection.client.enqueue(views.forPlayer(result, binding.playerId()));
            }
            case "LEAVE_ROOM" -> result = service.leaveRoom(binding.roomId(), binding.identity());
            case "GM_KICK_PLAYER" -> result = service.kickPlayer(binding.roomId(), binding.identity(),
                    UUID.fromString(command.targetPlayerId()));
            case "GM_SHUFFLE" -> result = service.shuffle(binding.roomId(), binding.identity(),
                    UUID.fromString(command.sessionId()));
            case "GM_START_GAME" -> result = service.startWhoAmI(binding.roomId(), binding.identity());
            case "SUBMIT_NAME" -> result = service.submitName(binding.roomId(), binding.identity(),
                    UUID.fromString(command.sessionId()), command.secretName());
            case "GM_RESET_SUBMISSION" -> result = service.resetSubmission(binding.roomId(), binding.identity(),
                    UUID.fromString(command.sessionId()), UUID.fromString(command.targetPlayerId()),
                    UUID.fromString(command.expectedSubmissionId()));
            default -> throw new DomainException(NOT_AUTHORIZED);
        }
        // Leave receives a terminal LEFT outcome instead of a result after revocation.
        if (!connection.closed.get()) {
            var reply = new RoomProtocol.Result(command.requestId(), true, null, result.revision());
            remember(channel, binding.playerId(), command, reply);
            connection.client.enqueue(reply);
        }
    }

    public void disconnected(Connection connection) {
        synchronized (connection) {
            if (connection.closed.getAndSet(true) || connection.binding == null) return;
            Binding binding = connection.binding;
            try {
                store.inRoom(binding.roomId(), room -> {
                    Channel channel = channels.get(binding.roomId());
                    if (binding.instance().equals(room.gmPlayerId()) && channel != null
                            && channel.active.remove(binding.playerId(), connection))
                        service.disconnectPlayer(binding.roomId(), binding.identity());
                    return null;
                });
            } catch (DomainException ignored) {
                // Expiration/removal or revocation already owns the terminal outcome.
            }
        }
    }

    @Override public void changed(Room room) {
        Channel channel = channels.get(room.id());
        if (channel == null || !channel.instance.equals(room.gmPlayerId())) return;
        // A removed member may already have no socket. Release their cached results too.
        var members = room.players().stream().map(Player::id).collect(java.util.stream.Collectors.toSet());
        channel.requests.keySet().retainAll(members);
        for (var entry : List.copyOf(channel.active.entrySet())) {
            Connection connection = entry.getValue();
            try {
                room.playerFor(connection.binding.identity());
            } catch (DomainException exception) {
                channel.active.remove(entry.getKey());
                channel.requests.remove(entry.getKey());
                revoke(connection, exception.code() == PLAYER_KICKED ? "PLAYER_KICKED" : "LEFT");
                continue;
            }
            connection.client.enqueue(views.forPlayer(room, entry.getKey()));
        }
    }

    @Override public void removed(RoomStore.Removal removal) {
        Channel channel = channels.get(removal.room().id());
        if (channel == null || !channel.instance.equals(removal.room().gmPlayerId())) return;
        channels.remove(removal.room().id(), channel);
        for (var connection : channel.active.values()) {
            connection.closed.set(true);
            connection.client.terminate(new RoomProtocol.Terminal("ROOM_ENDED", removal.reason().name()));
        }
        channel.active.clear();
        channel.requests.clear();
    }

    private void revoke(Connection connection, String reason) {
        connection.closed.set(true);
        connection.client.terminate(new RoomProtocol.Terminal("ACCESS_REVOKED", reason));
    }

    private void remember(Channel channel, UUID player, RoomProtocol.Command command, RoomProtocol.Result result) {
        var cache = channel.requests.computeIfAbsent(player, ignored -> new LinkedHashMap<>());
        cache.values().removeIf(value -> !clock.instant().isBefore(value.until()));
        // Cache only command metadata and safe results, never a secret-name payload.
        cache.put(command.requestId(), new Cached(command.type(), command.targetPlayerId(),
                command.sessionId(), command.expectedSubmissionId(), result,
                clock.instant().plus(Duration.ofMinutes(2))));
        while (cache.size() > 64) cache.remove(cache.keySet().iterator().next());
    }

    static String safeRequestId(String id) {
        return id != null && id.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}") ? id : null;
    }

    private void validate(RoomProtocol.Command command) {
        if (safeRequestId(command.requestId()) == null || command.roomId() == null
                || !command.roomId().matches("[A-HJ-NP-Z2-9]{6}") || command.type() == null)
            throw new DomainException(INVALID_INPUT);
        if (command.sessionId() != null && safeRequestId(command.sessionId()) == null)
            throw new DomainException(INVALID_INPUT);
        if (!command.type().equals("SUBMIT_NAME") && command.secretName() != null)
            throw new DomainException(INVALID_INPUT);
        if (!command.type().equals("GM_RESET_SUBMISSION") && command.expectedSubmissionId() != null)
            throw new DomainException(INVALID_INPUT);
        if (!java.util.Set.of("SUBMIT_NAME", "GM_RESET_SUBMISSION", "GM_KICK_PLAYER", "GM_SHUFFLE").contains(command.type())
                && command.sessionId() != null) throw new DomainException(INVALID_INPUT);
        switch (command.type()) {
            case "JOIN_ROOM" -> {
                if (command.nickname() == null || command.guestToken() == null || command.targetPlayerId() != null)
                    throw new DomainException(INVALID_INPUT);
            }
            case "RESUME_ROOM" -> {
                if (command.nickname() != null || command.targetPlayerId() != null) throw new DomainException(INVALID_INPUT);
            }
            case "GET_STATE", "LEAVE_ROOM", "GM_KICK_PLAYER", "GM_START_GAME" -> {
                if (command.nickname() != null || command.guestToken() != null) throw new DomainException(INVALID_INPUT);
                if (command.type().equals("GM_KICK_PLAYER")) {
                    if (safeRequestId(command.targetPlayerId()) == null) throw new DomainException(INVALID_INPUT);
                } else if (command.targetPlayerId() != null) throw new DomainException(INVALID_INPUT);
            }
            case "SUBMIT_NAME", "GM_RESET_SUBMISSION", "GM_SHUFFLE" -> {
                if (command.sessionId() == null || command.nickname() != null || command.guestToken() != null)
                    throw new DomainException(INVALID_INPUT);
                if (command.type().equals("SUBMIT_NAME") || command.type().equals("GM_SHUFFLE")) {
                    if (command.targetPlayerId() != null) throw new DomainException(INVALID_INPUT);
                } else if (safeRequestId(command.targetPlayerId()) == null
                        || safeRequestId(command.expectedSubmissionId()) == null)
                    throw new DomainException(INVALID_INPUT);
            }
            default -> throw new DomainException(INVALID_INPUT);
        }
    }
}
