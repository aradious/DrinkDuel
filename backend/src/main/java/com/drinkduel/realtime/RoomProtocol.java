package com.drinkduel.realtime;

import java.util.List;
import java.util.UUID;

/** Explicit wire allowlist. No domain aggregates or credentials in outgoing records. */
public final class RoomProtocol {
    private RoomProtocol() {}

    public record Command(String type, String requestId, String roomId, String nickname,
                          String guestToken, String targetPlayerId, String sessionId,
                          String secretName, String expectedSubmissionId, Long expectedStatusVersion) {
        public Command(String type, String requestId, String roomId, String nickname,
                       String guestToken, String targetPlayerId, String sessionId,
                       String secretName, String expectedSubmissionId) {
            this(type, requestId, roomId, nickname, guestToken, targetPlayerId, sessionId,
                    secretName, expectedSubmissionId, null);
        }
        public Command(String type, String requestId, String roomId, String nickname,
                       String guestToken, String targetPlayerId) {
            this(type, requestId, roomId, nickname, guestToken, targetPlayerId, null, null, null);
        }
        @Override public String toString() { return "RoomCommand[redacted]"; }
    }

    public sealed interface Message permits State, Result, Terminal {}
    public record State(String type, Snapshot room) implements Message {
        public State(Snapshot room) { this("STATE", room); }
    }
    public record Snapshot(String roomId, long roomRevision, String expiresAt, UUID sessionId,
                           String lifecycle, UUID currentPlayerId, UUID gmPlayerId, boolean isGm,
                           int capacity, boolean joinable, List<String> allowedActions,
                           List<PlayerView> players, WhoAmIView game, LiarsDiceView liarsDice) {}
    public record WhoAmIView(String gameType, String phase, List<ParticipantView> participants,
                             int submittedCount, int participantCount, boolean currentPlayerSubmitted,
                             boolean readyForShuffle, boolean canShuffle, List<GameCard> cards,
                             RoastView roast, List<RevealCard> reveal) {}
    public record RoastView(String kind, List<UUID> playingPlayerIds) {}
    /** Reveal-only display data; never expose submission IDs, creator identity bindings, or result versions. */
    public record RevealCard(UUID playerId, String nickname, int avatarId, String gameStatus,
                             String assignedName, String createdBy) {}
    /** No submission IDs or attribution. assignedName is null for the verified recipient's card. */
    public record GameCard(UUID playerId, String nickname, int avatarId, String connectionStatus,
                           String gameStatus, long statusVersion, String assignedName) {}
    /** Reset guard is metadata for GM only; it never contains submitted text. */
    public record ParticipantView(UUID playerId, boolean submitted, UUID resetSubmissionId) {}
    public record PlayerView(UUID playerId, String nickname, int avatarId, String connectionStatus) {}
    public record LiarsDiceView(String gameType, String phase, List<UUID> participantIds,
                                List<Integer> ownDice, List<LiarsDiceHandView> revealedHands,
                                List<LiarsDiceFaceCountView> revealCounts) {
        public LiarsDiceView {
            participantIds = List.copyOf(participantIds);
            ownDice = List.copyOf(ownDice);
            revealedHands = List.copyOf(revealedHands);
            revealCounts = List.copyOf(revealCounts);
        }
        @Override public String toString() { return "LiarsDiceView[redacted]"; }
    }
    public record LiarsDiceHandView(UUID playerId, List<Integer> dice) {
        public LiarsDiceHandView { dice = List.copyOf(dice); }
        @Override public String toString() { return "LiarsDiceHandView[redacted]"; }
    }
    public record LiarsDiceFaceCountView(int face, int rawCount, int effectiveCount) {}
    public record Result(String type, String requestId, boolean accepted, String code,
                         Long roomRevision) implements Message {
        public Result(String requestId, boolean accepted, String code, Long revision) {
            this("COMMAND_RESULT", requestId, accepted, code, revision);
        }
    }
    public record Terminal(String type, String reason) implements Message {}
}
