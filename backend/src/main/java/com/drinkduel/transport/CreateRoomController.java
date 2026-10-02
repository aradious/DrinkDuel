package com.drinkduel.transport;

import com.drinkduel.realtime.AuthenticatedOwnerPrincipal;
import com.drinkduel.room.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public final class CreateRoomController {
    private final RoomService rooms;
    public CreateRoomController(RoomService rooms) { this.rooms = rooms; }
    public record CreateRoom(String nickname) {}
    public record Created(String roomId) {}
    public record Failure(String code) {}

    @PostMapping("/api/rooms")
    public ResponseEntity<?> create(@RequestBody CreateRoom input, HttpServletRequest request) {
        if (!(request.getUserPrincipal() instanceof AuthenticatedOwnerPrincipal owner))
            return ResponseEntity.status(401).cacheControl(CacheControl.noStore()).body(new Failure("NOT_AUTHORIZED"));
        if (!BrowserOrigin.sameOrigin(request))
            return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).body(new Failure("NOT_AUTHORIZED"));
        try {
            var room = rooms.createRoom(owner.identity(), input.nickname());
            // HTTP creation is not a live WebSocket connection. Attachment restores presence.
            rooms.disconnectPlayer(room.id(), owner.identity());
            return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(new Created(room.id()));
        } catch (DomainException exception) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(new Failure(exception.code().name()));
        }
    }
}
