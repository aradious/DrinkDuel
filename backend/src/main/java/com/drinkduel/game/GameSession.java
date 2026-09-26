package com.drinkduel.game;

import java.util.Objects;
import java.util.UUID;

public record GameSession(UUID id, GameState state) {
    public GameSession {
        Objects.requireNonNull(id);
        Objects.requireNonNull(state);
    }
    public GameType gameType() { return state.gameType(); }
}
