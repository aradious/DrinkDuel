package com.drinkduel.game;

import java.util.List;

/** Injectable role permutation boundary for authoritative Sheriff assignment. */
@FunctionalInterface
public interface SheriffRoleShuffler {
    List<SheriffRole> shuffle(List<SheriffRole> roles);
}
