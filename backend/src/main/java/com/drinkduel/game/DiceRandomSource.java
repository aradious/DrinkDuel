package com.drinkduel.game;

/** Injectable source for one unbiased die value. */
@FunctionalInterface
public interface DiceRandomSource {
    int nextDieValue();
}
