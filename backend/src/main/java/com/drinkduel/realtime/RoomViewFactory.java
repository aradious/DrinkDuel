package com.drinkduel.realtime;

import com.drinkduel.room.*;
import com.drinkduel.game.WhoAmIState;
import java.util.ArrayList;
import java.util.UUID;

public final class RoomViewFactory {
    public RoomProtocol.State forPlayer(Room room, UUID recipient) {
        var player = room.players().stream().filter(p -> p.id().equals(recipient)).findFirst()
                .orElseThrow(() -> new DomainException(DomainException.Code.NOT_AUTHORIZED));
        boolean gm = player.id().equals(room.gmPlayerId());
        var actions = new ArrayList<String>();
        boolean connectedGm = gm && player.connectionState() == ConnectionState.CONNECTED;
        var connected = room.players().stream().filter(p -> p.connectionState() == ConnectionState.CONNECTED)
                .map(Player::id).collect(java.util.stream.Collectors.toSet());
        actions.add("GET_STATE");
        RoomProtocol.SubmitNameView game = null;
        if (room.isLobby()) {
            if (!gm) actions.add("LEAVE_ROOM");
            else if (connectedGm) {
                actions.add("GM_KICK_PLAYER");
                if (connected.size() >= Room.MIN_ACTIVE_PLAYERS) actions.add("GM_START_GAME");
            }
        } else if (room.currentSession().orElseThrow().state() instanceof WhoAmIState state) {
            boolean submitted = state.submissions().containsKey(recipient);
            boolean ready = state.readyForShuffle(connected);
            if (state.participantIds().contains(recipient) && !submitted) actions.add("SUBMIT_NAME");
            if (connectedGm) {
                actions.add("GM_KICK_PLAYER");
                if (!state.submissions().isEmpty()) actions.add("GM_RESET_SUBMISSION");
            }
            game = new RoomProtocol.SubmitNameView(state.gameType().name(), state.phase().name(),
                    state.participantIds().stream().map(id -> {
                        var submission = state.submissions().get(id);
                        return new RoomProtocol.ParticipantView(id, submission != null,
                                connectedGm && submission != null ? submission.id() : null);
                    }).toList(), state.submissions().size(), state.participantIds().size(), submitted,
                    ready, connectedGm && ready);
        }
        return new RoomProtocol.State(new RoomProtocol.Snapshot(room.id(), room.revision(),
                room.expiresAt().toString(), room.currentSession().map(s -> s.id()).orElse(null),
                room.isLobby() ? "LOBBY" : "IN_GAME", recipient, room.gmPlayerId(), gm,
                Room.MAX_PLAYERS, room.isLobby() && room.players().size() < Room.MAX_PLAYERS,
                java.util.List.copyOf(actions), room.players().stream().map(p -> new RoomProtocol.PlayerView(
                        p.id(), p.nickname(), p.avatarId(), p.connectionState().name())).toList(), game));
    }
}
