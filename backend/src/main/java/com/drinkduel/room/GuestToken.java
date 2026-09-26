package com.drinkduel.room;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Bearer secret. Only its fingerprint is retained in room state. */
public final class GuestToken {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String value;

    private GuestToken(String value) { this.value = value; }

    public static GuestToken generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new GuestToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    public static GuestToken parse(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}"))
            throw new DomainException(DomainException.Code.INVALID_INPUT);
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value))
            throw new DomainException(DomainException.Code.INVALID_INPUT);
        return new GuestToken(value);
    }

    /** Explicit access for credential delivery only; never log this value. */
    public String value() { return value; }

    public PlayerIdentity.Guest identity() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
            return new PlayerIdentity.Guest(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @Override public String toString() { return "GuestToken[redacted]"; }
}
