package com.drinkduel.realtime;

import com.drinkduel.room.*;
import java.util.ArrayList;
import java.util.UUID;

public final class RoomViewFactory {
    public RoomProtocol.State forPlayer(Room room, UUID recipient) {
        var player = room.players().stream().filter(p -> p.id().equals(recipient)).findFirst()
                .orElseThrow(() -> new DomainException(DomainException.Code.NOT_AUTHORIZED));
        boolean gm = player.id().equals(room.gmPlayerId());
        var actions = new ArrayList<String>();
        actions.add("GET_STATE");
        if (room.isLobby()) {
            if (!gm) actions.add("LEAVE_ROOM");
            else if (player.connectionState() == ConnectionState.CONNECTED) actions.add("GM_KICK_PLAYER");
        }
        return new RoomProtocol.State(new RoomProtocol.Snapshot(room.id(), room.revision(),
                room.expiresAt().toString(), room.currentSession().map(s -> s.id()).orElse(null),
                room.isLobby() ? "LOBBY" : "IN_GAME", recipient, room.gmPlayerId(), gm,
                Room.MAX_PLAYERS, room.isLobby() && room.players().size() < Room.MAX_PLAYERS,
                java.util.List.copyOf(actions), room.players().stream().map(p -> new RoomProtocol.PlayerView(
                        p.id(), p.nickname(), p.avatarId(), p.connectionState().name())).toList()));
    }
}
