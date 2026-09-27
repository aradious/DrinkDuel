package com.drinkduel.room;

public final class DomainException extends RuntimeException {
    public enum Code {
        ROOM_NOT_FOUND, ROOM_EXPIRED, ROOM_FULL, NICKNAME_TAKEN,
        IDENTITY_ALREADY_JOINED, NOT_AUTHORIZED, NOT_ENOUGH_PLAYERS,
        GAME_IN_PROGRESS, INVALID_INPUT, STALE_STATE,
        PLAYER_NOT_FOUND, PLAYER_KICKED, GM_CANNOT_LEAVE, GM_CANNOT_KICK_SELF,
        INVALID_GAME_PHASE, PLAYER_NOT_IN_GAME, ALREADY_SUBMITTED,
        INVALID_SUBMISSION, SUBMISSION_NOT_FOUND, STALE_COMMAND, GAME_NOT_READY
    }

    private final Code code;

    public DomainException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() { return code; }

    static String requireText(String value) {
        if (value == null || value.isBlank()) throw new DomainException(Code.INVALID_INPUT);
        return value;
    }
}
