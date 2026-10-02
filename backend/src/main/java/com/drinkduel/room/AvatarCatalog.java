package com.drinkduel.room;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import tools.jackson.databind.json.JsonMapper;
import java.util.random.RandomGenerator;

public final class AvatarCatalog {
    private static final List<Integer> IDS = loadIds();
    private final RandomGenerator random;

    public AvatarCatalog() { this(new SecureRandom()); }
    public AvatarCatalog(RandomGenerator random) { this.random = Objects.requireNonNull(random); }

    /** nextInt(bound) gives each catalog entry equal probability, without modulo bias. */
    public synchronized int randomAvatarId() { return IDS.get(random.nextInt(IDS.size())); }

    public static boolean contains(int avatarId) { return IDS.contains(avatarId); }

    private static List<Integer> loadIds() {
        try (var input = AvatarCatalog.class.getResourceAsStream("/avatars/catalog.json")) {
            var root = new JsonMapper().readTree(Objects.requireNonNull(input, "Missing avatar catalog"));
            var ids = new ArrayList<Integer>();
            for (var entry : root.properties()) {
                int id = Integer.parseInt(entry.getKey());
                if (id < 1 || !entry.getValue().asString().matches("avatar-[0-9]+\\.(webp|png|avif)"))
                    throw new IllegalStateException("Invalid avatar catalog entry");
                ids.add(id);
            }
            if (ids.isEmpty()) throw new IllegalStateException("Empty avatar catalog");
            return ids.stream().sorted().toList();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load avatar catalog", exception);
        }
    }
}
