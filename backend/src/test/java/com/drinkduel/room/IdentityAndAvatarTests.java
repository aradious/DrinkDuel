package com.drinkduel.room;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class IdentityAndAvatarTests {
    @Test void sharedCatalogMatchesEverySuppliedAsset() throws Exception {
        var directory = java.nio.file.Path.of("../frontend/src/assets/drinkduel/avatars");
        var catalog = new tools.jackson.databind.json.JsonMapper().readTree(directory.resolve("catalog.json").toFile());
        var listed = new HashSet<String>();
        for (var entry : catalog.properties()) {
            assertTrue(AvatarCatalog.contains(Integer.parseInt(entry.getKey())));
            assertTrue(java.nio.file.Files.isRegularFile(directory.resolve(entry.getValue().asString())));
            assertTrue(listed.add(entry.getValue().asString()));
        }
        try (var files = java.nio.file.Files.list(directory)) {
            assertEquals(listed, files.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("avatar-[0-9]+\\.(webp|png|avif)"))
                    .collect(java.util.stream.Collectors.toSet()));
        }
    }
    @Test void tokenRoundTripPreservesFingerprintWithoutExposingSecretInToString() {
        GuestToken token = GuestToken.generate();
        assertEquals(token.identity(), GuestToken.parse(token.value()).identity());
        assertNotEquals(token.identity(), GuestToken.generate().identity());
        assertFalse(token.toString().contains(token.value()));
        assertFalse(token.identity().toString().contains(token.identity().fingerprint()));
        assertEquals(64, token.identity().fingerprint().length());
        assertThrows(DomainException.class, () -> GuestToken.parse("nickname"));
    }

    @Test void all30AvatarIdsMapOneToOneFromUniformBoundedDraw() {
        var catalog = new AvatarCatalog(new RandomGenerator() {
            private int next;
            @Override public long nextLong() { throw new AssertionError("Use bounded draw"); }
            @Override public int nextInt(int bound) {
                assertEquals(30, bound);
                return next++;
            }
        });
        var ids = new HashSet<Integer>();
        for (int i = 1; i <= 30; i++) {
            int id = catalog.randomAvatarId();
            assertEquals(i, id);
            ids.add(id);
        }
        assertEquals(30, ids.size());
    }

    @Test void productionAvatarsStayInRangeAndInvalidIdsAreRejected() {
        AvatarCatalog catalog = new AvatarCatalog();
        for (int i = 0; i < 1000; i++) assertTrue(AvatarCatalog.contains(catalog.randomAvatarId()));
        var identity = GuestToken.generate().identity();
        assertThrows(DomainException.class, () -> new Player(java.util.UUID.randomUUID(), "Name", 0,
                identity, ConnectionState.CONNECTED));
        assertThrows(DomainException.class, () -> new Player(java.util.UUID.randomUUID(), "Name", 31,
                identity, ConnectionState.CONNECTED));
    }
}
