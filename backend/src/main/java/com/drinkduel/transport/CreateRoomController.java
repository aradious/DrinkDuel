package com.drinkduel.transport;

import com.drinkduel.logging.SafeLog;
import com.drinkduel.realtime.AuthenticatedOwnerPrincipal;
import com.drinkduel.room.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
public final class CreateRoomController {
    private static final Logger LOG = LoggerFactory.getLogger(CreateRoomController.class);
    private final RoomService rooms;
    public CreateRoomController(RoomService rooms) { this.rooms = rooms; }
    public record CreateRoom(String nickname) {}
    public record Created(String roomId) {}
    public record Failure(String code) {}

    @PostMapping("/api/rooms")
    public ResponseEntity<?> create(@RequestBody CreateRoom input, HttpServletRequest request) {
        String correlationId = SafeLog.correlationId();
        if (!(request.getUserPrincipal() instanceof AuthenticatedOwnerPrincipal owner)) {
            LOG.warn("event=rest.create-room.rejected correlationId={} reason=MISSING_HOST_SESSION", correlationId);
            return ResponseEntity.status(401).cacheControl(CacheControl.noStore()).body(new Failure("NOT_AUTHORIZED"));
        }
        if (!BrowserOrigin.sameOrigin(request)) {
            LOG.warn("event=rest.create-room.rejected correlationId={} reason=ORIGIN_REJECTED", correlationId);
            return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).body(new Failure("NOT_AUTHORIZED"));
        }
        try {
            var room = rooms.createRoom(owner.identity(), input.nickname());
            // HTTP creation is not a live WebSocket connection. Attachment restores presence.
            rooms.disconnectPlayer(room.id(), owner.identity());
            return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(new Created(room.id()));
        } catch (DomainException exception) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(new Failure(exception.code().name()));
        } catch (RuntimeException exception) {
            SafeLog.unexpected(LOG, "rest.create-room.failed", correlationId, exception);
            throw exception;
        }
    }
}
