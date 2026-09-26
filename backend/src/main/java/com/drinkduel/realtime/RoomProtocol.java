package com.drinkduel.realtime;

import java.util.List;
import java.util.UUID;

/** Explicit wire allowlist. No domain aggregates or credentials in outgoing records. */
public final class RoomProtocol {
    private RoomProtocol() {}

    public record Command(String type, String requestId, String roomId, String nickname,
                          String guestToken, String targetPlayerId) {
        @Override public String toString() { return "RoomCommand[redacted]"; }
    }

    public sealed interface Message permits State, Result, Terminal {}
    public record State(String type, Snapshot room) implements Message {
        public State(Snapshot room) { this("STATE", room); }
    }
    public record Snapshot(String roomId, long roomRevision, String expiresAt, UUID sessionId,
                           String lifecycle, UUID currentPlayerId, UUID gmPlayerId, boolean isGm,
                           int capacity, boolean joinable, List<String> allowedActions,
                           List<PlayerView> players) {}
    public record PlayerView(UUID playerId, String nickname, int avatarId, String connectionStatus) {}
    public record Result(String type, String requestId, boolean accepted, String code,
                         Long roomRevision) implements Message {
        public Result(String requestId, boolean accepted, String code, Long revision) {
            this("COMMAND_RESULT", requestId, accepted, code, revision);
        }
    }
    public record Terminal(String type, String reason) implements Message {}
}
