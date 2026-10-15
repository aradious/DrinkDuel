package com.drinkduel.game;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Production role shuffler backed by a cryptographically strong random source. */
public final class SecureSheriffRoleShuffler implements SheriffRoleShuffler {
    private final SecureRandom random;

    public SecureSheriffRoleShuffler() {
        this(new SecureRandom());
    }

    SecureSheriffRoleShuffler(SecureRandom random) {
        this.random = Objects.requireNonNull(random);
    }

    @Override
    public List<SheriffRole> shuffle(List<SheriffRole> roles) {
        var shuffled = new ArrayList<>(List.copyOf(Objects.requireNonNull(roles)));
        for (int index = shuffled.size() - 1; index > 0; index--) {
            int swapIndex = random.nextInt(index + 1);
            SheriffRole role = shuffled.get(index);
            shuffled.set(index, shuffled.get(swapIndex));
            shuffled.set(swapIndex, role);
        }
        return List.copyOf(shuffled);
    }
}
