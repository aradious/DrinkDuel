package com.drinkduel.room;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.random.RandomGenerator;

public final class AvatarCatalog {
    public static final int V1_SIZE = 25;
    private final RandomGenerator random;

    public AvatarCatalog() { this(new SecureRandom()); }
    public AvatarCatalog(RandomGenerator random) { this.random = Objects.requireNonNull(random); }

    /** nextInt(bound) gives each catalog entry equal probability, without modulo bias. */
    public synchronized int randomAvatarId() { return 1 + random.nextInt(V1_SIZE); }

    public static boolean contains(int avatarId) { return avatarId >= 1 && avatarId <= V1_SIZE; }
}
