package com.drinkduel.room;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class RoomStorageConfiguration {
    @Bean Clock roomClock() { return Clock.systemUTC(); }
    @Bean AvatarCatalog avatarCatalog() { return new AvatarCatalog(); }
    @Bean RoomStore roomStore(Clock roomClock, AvatarCatalog avatars) {
        return new InMemoryRoomStore(roomClock, avatars);
    }
    @Bean ExpirationCleanup expirationCleanup(RoomStore store) { return new ExpirationCleanup(store); }

    static final class ExpirationCleanup {
        private final RoomStore store;
        ExpirationCleanup(RoomStore store) { this.store = store; }

        // Passive rooms are reclaimed too; every access enforces the exact deadline.
        @Scheduled(fixedDelay = 1000)
        public void expireRooms() { store.expireRooms(); }
    }
}
