package com.drinkduel.game;

import java.util.ArrayList;
import java.util.Objects;

/** Rolls complete five-die candidates until the hand satisfies V1 rules. */
public final class AuthoritativeDiceRoller {
    private final DiceRandomSource random;

    public AuthoritativeDiceRoller(DiceRandomSource random) {
        this.random = Objects.requireNonNull(random);
    }

    public DiceHand rollHand() {
        while (true) {
            var candidate = new ArrayList<Integer>(DiceHand.SIZE);
            for (int index = 0; index < DiceHand.SIZE; index++) {
                candidate.add(random.nextDieValue());
            }
            if (candidate.stream().anyMatch(value -> value < 1 || value > 6)) {
                throw new IllegalStateException("Dice random source returned a value outside 1 through 6");
            }
            if (candidate.stream().distinct().count() < DiceHand.SIZE) {
                return new DiceHand(candidate);
            }
        }
    }
}
