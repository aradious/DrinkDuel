package com.drinkduel.game;

/** Immutable game-specific state. Extend the permitted types when a future game is added. */
public sealed interface GameState permits WhoAmIState {
    GameType gameType();
    /** Game-specific cleanup when Room membership is revoked. */
    GameState withoutParticipant(java.util.UUID playerId);
}
