package com.drinkduel.game;

import java.security.SecureRandom;

/** Production server-side dice randomness. */
public final class SecureDiceRandomSource implements DiceRandomSource {
    private final SecureRandom random;

    public SecureDiceRandomSource() {
        this(new SecureRandom());
    }

    SecureDiceRandomSource(SecureRandom random) {
        this.random = random;
    }

    @Override
    public int nextDieValue() {
        return random.nextInt(6) + 1;
    }
}
