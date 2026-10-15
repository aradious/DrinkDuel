package com.drinkduel.game;

import com.drinkduel.room.DomainException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.drinkduel.room.DomainException.Code.INVALID_GAME_PHASE;
import static com.drinkduel.room.DomainException.Code.PLAYER_NOT_IN_GAME;

/** Immutable authoritative Sheriff session state. */
public final class SheriffState implements GameState {
    public enum Phase { START, PLAYING, REVEAL }

    public static final int MIN_PARTICIPANTS = 4;
    public static final int MAX_PARTICIPANTS = 20;

    private final Phase phase;
    private final List<UUID> participantIds;
    private final Map<UUID, SheriffRole> assignments;
    private final Map<UUID, SheriffPlayerStatus> playerStatuses;

    private SheriffState(Phase phase, List<UUID> participantIds,
                         Map<UUID, SheriffRole> assignments,
                         Map<UUID, SheriffPlayerStatus> playerStatuses) {
        this.phase = Objects.requireNonNull(phase);
        this.participantIds = List.copyOf(Objects.requireNonNull(participantIds));
        this.assignments = immutableMap(assignments);
        this.playerStatuses = immutableMap(playerStatuses);
        validateState();
    }

    public static SheriffState start() {
        return new SheriffState(Phase.START, List.of(), Map.of(), Map.of());
    }

    /** Locks the supplied roster and assigns every role in one immutable transition. */
    public SheriffState startRound(List<UUID> roster, SheriffRoleShuffler shuffler) {
        if (phase != Phase.START) throw new DomainException(INVALID_GAME_PHASE);
        Objects.requireNonNull(shuffler);
        var participants = List.copyOf(Objects.requireNonNull(roster));
        validateRoster(participants);

        var requiredRoles = rolesFor(participants.size());
        var shuffledRoles = List.copyOf(Objects.requireNonNull(shuffler.shuffle(requiredRoles)));
        if (!roleCounts(requiredRoles).equals(roleCounts(shuffledRoles))) {
            throw new IllegalArgumentException("Role shuffler must return the exact role multiset");
        }

        var assignedRoles = new LinkedHashMap<UUID, SheriffRole>();
        var statuses = new LinkedHashMap<UUID, SheriffPlayerStatus>();
        for (int index = 0; index < participants.size(); index++) {
            assignedRoles.put(participants.get(index), shuffledRoles.get(index));
            statuses.put(participants.get(index), SheriffPlayerStatus.ALIVE);
        }
        return new SheriffState(Phase.PLAYING, participants, assignedRoles, statuses);
    }

    /** Marks one living participant dead. Repeated delivery for an existing death is idempotent. */
    public SheriffState markDead(UUID playerId) {
        requireRoundPhase(Phase.PLAYING);
        requireParticipant(playerId);
        if (playerStatuses.get(playerId) == SheriffPlayerStatus.DEAD) return this;

        var statuses = new LinkedHashMap<>(playerStatuses);
        statuses.put(playerId, SheriffPlayerStatus.DEAD);
        return new SheriffState(Phase.PLAYING, participantIds, assignments, statuses);
    }

    /** Ends the round without evaluating combat, victory, scoring, or rankings. */
    public SheriffState reveal() {
        requireRoundPhase(Phase.PLAYING);
        return new SheriffState(Phase.REVEAL, participantIds, assignments, playerStatuses);
    }

    /** Authoritative role access for later recipient-specific backend projection. */
    public SheriffRole roleForParticipant(UUID playerId) {
        requireAssignedRound();
        requireParticipant(playerId);
        return assignments.get(playerId);
    }

    public SheriffPlayerStatus statusForParticipant(UUID playerId) {
        requireAssignedRound();
        requireParticipant(playerId);
        return playerStatuses.get(playerId);
    }

    /**
     * Returns only role information that is public to every client: Sheriff, a dead
     * participant, or any participant after final Reveal.
     */
    public Optional<SheriffRole> publicRoleForParticipant(UUID playerId) {
        requireAssignedRound();
        requireParticipant(playerId);
        SheriffRole role = assignments.get(playerId);
        if (phase == Phase.REVEAL || role == SheriffRole.SHERIFF
                || playerStatuses.get(playerId) == SheriffPlayerStatus.DEAD) {
            return Optional.of(role);
        }
        return Optional.empty();
    }

    public UUID sheriffPlayerId() {
        requireAssignedRound();
        return assignments.entrySet().stream()
                .filter(entry -> entry.getValue() == SheriffRole.SHERIFF)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Sheriff assignment is missing"));
    }

    public int assignedCount(SheriffRole role) {
        requireAssignedRound();
        Objects.requireNonNull(role);
        return (int) assignments.values().stream().filter(role::equals).count();
    }

    public int aliveCount(SheriffRole role) {
        return statusCount(role, SheriffPlayerStatus.ALIVE);
    }

    public int deadCount(SheriffRole role) {
        return statusCount(role, SheriffPlayerStatus.DEAD);
    }

    public int totalPlayers() {
        requireAssignedRound();
        return participantIds.size();
    }

    public int alivePlayers() {
        requireAssignedRound();
        return (int) playerStatuses.values().stream().filter(SheriffPlayerStatus.ALIVE::equals).count();
    }

    public int deadPlayers() {
        requireAssignedRound();
        return (int) playerStatuses.values().stream().filter(SheriffPlayerStatus.DEAD::equals).count();
    }

    @Override
    public SheriffState withoutParticipant(UUID playerId) {
        Objects.requireNonNull(playerId);
        if (phase == Phase.START) return this;
        throw new DomainException(INVALID_GAME_PHASE);
    }

    @Override public GameType gameType() { return GameType.SHERIFF; }
    public Phase phase() { return phase; }
    public List<UUID> participantIds() { return participantIds; }

    private int statusCount(SheriffRole role, SheriffPlayerStatus status) {
        requireAssignedRound();
        Objects.requireNonNull(role);
        return (int) participantIds.stream()
                .filter(playerId -> assignments.get(playerId) == role)
                .filter(playerId -> playerStatuses.get(playerId) == status)
                .count();
    }

    private void validateState() {
        if (phase == Phase.START) {
            if (!participantIds.isEmpty() || !assignments.isEmpty() || !playerStatuses.isEmpty()) {
                throw new IllegalArgumentException("START cannot contain round data");
            }
            return;
        }

        validateRoster(participantIds);
        var rosterIds = new LinkedHashSet<>(participantIds);
        if (!assignments.keySet().equals(rosterIds) || !playerStatuses.keySet().equals(rosterIds)) {
            throw new IllegalArgumentException("Every locked participant must have one role and life status");
        }
        if (!roleCounts(rolesFor(participantIds.size())).equals(roleCounts(assignments.values()))) {
            throw new IllegalArgumentException("Assignments do not match the required role distribution");
        }
    }

    private static void validateRoster(List<UUID> roster) {
        if (roster.size() < MIN_PARTICIPANTS || roster.size() > MAX_PARTICIPANTS
                || roster.stream().anyMatch(Objects::isNull)
                || roster.stream().distinct().count() != roster.size()) {
            throw new IllegalArgumentException("Sheriff roster requires 4 to 20 distinct participant IDs");
        }
    }

    private static List<SheriffRole> rolesFor(int participantCount) {
        var roles = new ArrayList<SheriffRole>(participantCount);
        roles.add(SheriffRole.SHERIFF);
        for (int index = 0; index < participantCount / 2 - 1; index++) roles.add(SheriffRole.DEPUTY);
        for (int index = 0; index < participantCount / 2; index++) roles.add(SheriffRole.OUTLAW);
        if (participantCount % 2 == 1) roles.add(SheriffRole.RENEGADE);
        return List.copyOf(roles);
    }

    private static Map<SheriffRole, Integer> roleCounts(Iterable<SheriffRole> roles) {
        var counts = new EnumMap<SheriffRole, Integer>(SheriffRole.class);
        for (SheriffRole role : SheriffRole.values()) counts.put(role, 0);
        for (SheriffRole role : roles) {
            if (role == null) throw new IllegalArgumentException("Roles cannot contain null");
            counts.compute(role, (ignored, count) -> count + 1);
        }
        return counts;
    }

    private void requireRoundPhase(Phase required) {
        if (phase != required) throw new DomainException(INVALID_GAME_PHASE);
    }

    private void requireAssignedRound() {
        if (phase == Phase.START) throw new DomainException(INVALID_GAME_PHASE);
    }

    private void requireParticipant(UUID playerId) {
        Objects.requireNonNull(playerId);
        if (!assignments.containsKey(playerId)) throw new DomainException(PLAYER_NOT_IN_GAME);
    }

    private static <T> Map<UUID, T> immutableMap(Map<UUID, T> source) {
        Objects.requireNonNull(source);
        var copy = new LinkedHashMap<UUID, T>();
        source.forEach((id, value) -> copy.put(Objects.requireNonNull(id), Objects.requireNonNull(value)));
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public String toString() {
        return "SheriffState[phase=" + phase + ", participants=" + participantIds.size()
                + ", secrets=redacted]";
    }
}
