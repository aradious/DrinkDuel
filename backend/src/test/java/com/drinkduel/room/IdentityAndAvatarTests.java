package com.drinkduel.room;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class IdentityAndAvatarTests {
    @Test void tokenRoundTripPreservesFingerprintWithoutExposingSecretInToString() {
        GuestToken token = GuestToken.generate();
        assertEquals(token.identity(), GuestToken.parse(token.value()).identity());
        assertNotEquals(token.identity(), GuestToken.generate().identity());
        assertFalse(token.toString().contains(token.value()));
        assertFalse(token.identity().toString().contains(token.identity().fingerprint()));
        assertEquals(64, token.identity().fingerprint().length());
        assertThrows(DomainException.class, () -> GuestToken.parse("nickname"));
    }

    @Test void all25AvatarIdsMapOneToOneFromUniformBoundedDraw() {
        var catalog = new AvatarCatalog(new RandomGenerator() {
            private int next;
            @Override public long nextLong() { throw new AssertionError("Use bounded draw"); }
            @Override public int nextInt(int bound) {
                assertEquals(25, bound);
                return next++;
            }
        });
        var ids = new HashSet<Integer>();
        for (int i = 1; i <= 25; i++) {
            int id = catalog.randomAvatarId();
            assertEquals(i, id);
            ids.add(id);
        }
        assertEquals(25, ids.size());
    }

    @Test void productionAvatarsStayInRangeAndInvalidIdsAreRejected() {
        AvatarCatalog catalog = new AvatarCatalog();
        for (int i = 0; i < 1000; i++) assertTrue(AvatarCatalog.contains(catalog.randomAvatarId()));
        var identity = GuestToken.generate().identity();
        assertThrows(DomainException.class, () -> new Player(java.util.UUID.randomUUID(), "Name", 0,
                identity, ConnectionState.CONNECTED));
        assertThrows(DomainException.class, () -> new Player(java.util.UUID.randomUUID(), "Name", 26,
                identity, ConnectionState.CONNECTED));
    }
}
