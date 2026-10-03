package com.drinkduel.game;

import java.util.List;
import java.util.Objects;

/** Immutable authoritative five-die hand. */
public record DiceHand(List<Integer> values) {
    public static final int SIZE = 5;

    public DiceHand {
        values = List.copyOf(Objects.requireNonNull(values));
        if (values.size() != SIZE) {
            throw new IllegalArgumentException("A dice hand must contain exactly five values");
        }
        if (values.stream().anyMatch(value -> value == null || value < 1 || value > 6)) {
            throw new IllegalArgumentException("Dice values must be between 1 and 6");
        }
        if (values.stream().distinct().count() == SIZE) {
            throw new IllegalArgumentException("A dice hand must contain at least one duplicate");
        }
    }
}
