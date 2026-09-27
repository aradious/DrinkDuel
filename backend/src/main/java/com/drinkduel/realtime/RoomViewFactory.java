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
        RoomProtocol.WhoAmIView game = null;
        if (room.isLobby()) {
            if (!gm) actions.add("LEAVE_ROOM");
            else if (connectedGm) {
                actions.add("GM_KICK_PLAYER");
                if (connected.size() >= Room.MIN_ACTIVE_PLAYERS) actions.add("GM_START_GAME");
            }
        } else if (room.currentSession().orElseThrow().state() instanceof WhoAmIState state) {
            boolean submitting = state.phase() == WhoAmIState.Phase.SUBMIT_NAME;
            boolean submitted = state.submissions().containsKey(recipient);
            boolean ready = state.readyForShuffle(connected);
            if (submitting && state.participantIds().contains(recipient) && !submitted) actions.add("SUBMIT_NAME");
            if (connectedGm) {
                actions.add("GM_KICK_PLAYER");
                if (submitting && !state.submissions().isEmpty()) actions.add("GM_RESET_SUBMISSION");
                if (ready) actions.add("GM_SHUFFLE");
            }
            if (connectedGm) {
                if (state.phase() == WhoAmIState.Phase.PLAYING) actions.add("GM_END_GAME");
                if (state.phase() == WhoAmIState.Phase.ROAST) actions.add("GM_CONTINUE_REVEAL");
                if (state.canBackToRoom()) actions.add("GM_BACK_TO_ROOM");
                if (state.phase() == WhoAmIState.Phase.REVEAL && connected.size() >= Room.MIN_ACTIVE_PLAYERS)
                    actions.add("GM_PLAY_AGAIN");
            }
            if (state.phase() == WhoAmIState.Phase.PLAYING) {
                if (state.results().get(recipient).status() == WhoAmIState.PlayerGameStatus.PLAYING)
                    actions.add("GIVE_UP");
                if (connectedGm) {
                    if (state.results().values().stream().anyMatch(r -> r.status() == WhoAmIState.PlayerGameStatus.PLAYING))
                        actions.add("GM_MARK_GOT_IT");
                    if (state.results().values().stream().anyMatch(r -> r.status() != WhoAmIState.PlayerGameStatus.PLAYING))
                        actions.add("GM_RESET_PLAYER_STATUS");
                }
            }
            game = new RoomProtocol.WhoAmIView(state.gameType().name(), state.phase().name(),
                    submitting ? state.participantIds().stream().map(id -> {
                        var submission = state.submissions().get(id);
                        return new RoomProtocol.ParticipantView(id, submission != null,
                                connectedGm && submission != null ? submission.id() : null);
                    }).toList() : java.util.List.of(), submitting ? state.submissions().size() : 0,
                    state.participantIds().size(), submitting && submitted,
                    ready, connectedGm && ready, state.phase() != WhoAmIState.Phase.PLAYING ? java.util.List.of() :
                    room.players().stream().filter(p -> state.participantIds().contains(p.id()))
                            .map(p -> new RoomProtocol.GameCard(p.id(), p.nickname(), p.avatarId(),
                                    p.connectionState().name(), state.results().get(p.id()).status().name(),
                                    state.results().get(p.id()).version(),
                                    p.id().equals(recipient) ? null : state.assignments().get(p.id()).submission().text()))
                            .toList(), state.phase() == WhoAmIState.Phase.ROAST ? new RoomProtocol.RoastView(
                            state.roastSummary().kind().name(), state.roastSummary().playingPlayerIds(),
                            state.roastSummary().gaveUpPlayerIds()) : null,
                    state.phase() != WhoAmIState.Phase.REVEAL ? java.util.List.of() :
                    room.players().stream().filter(p -> state.participantIds().contains(p.id())).map(p -> {
                        var submission = state.assignments().get(p.id()).submission();
                        return new RoomProtocol.RevealCard(p.id(), p.nickname(), p.avatarId(),
                                state.results().get(p.id()).status().name(), submission.text(), submission.submitterNickname());
                    }).toList());
        }
        if (connectedGm) actions.add("GM_CLOSE_ROOM");
        return new RoomProtocol.State(new RoomProtocol.Snapshot(room.id(), room.revision(),
                room.expiresAt().toString(), room.currentSession().map(s -> s.id()).orElse(null),
                room.isLobby() ? "LOBBY" : "IN_GAME", recipient, room.gmPlayerId(), gm,
                Room.MAX_PLAYERS, room.isLobby() && room.players().size() < Room.MAX_PLAYERS,
                java.util.List.copyOf(actions), room.players().stream().map(p -> new RoomProtocol.PlayerView(
                        p.id(), p.nickname(), p.avatarId(), p.connectionState().name())).toList(), game));
    }
}
