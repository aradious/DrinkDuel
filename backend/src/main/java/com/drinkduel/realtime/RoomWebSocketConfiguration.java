package com.drinkduel.realtime;

import com.drinkduel.room.RoomService;
import com.drinkduel.room.RoomStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class RoomWebSocketConfiguration implements WebSocketConfigurer {
    private final RoomSocketHandler handler;

    public RoomWebSocketConfiguration(RoomStore store, RoomService service, Clock roomClock) {
        handler = new RoomSocketHandler(new RoomRealtime(store, service, roomClock));
    }

    @Bean RoomSocketHandler roomSocketHandler() { return handler; }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Spring's default same-origin policy; no wildcard origins or URL credentials.
        registry.addHandler(handler, "/ws/rooms");
    }
}
