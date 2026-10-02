package com.drinkduel.realtime;

import com.drinkduel.room.PlayerIdentity;
import java.security.Principal;
import java.util.Objects;

/** Only a trusted server authentication adapter may populate this handshake principal.
 * No header, query parameter, or command payload is converted into an owner principal.
 * The concrete verified owner mechanism is outside the room domain.
 */
public record AuthenticatedOwnerPrincipal(PlayerIdentity.Owner identity) implements Principal {
    public AuthenticatedOwnerPrincipal { Objects.requireNonNull(identity); }
    @Override public String getName() { return "authenticated-owner"; }
    @Override public String toString() { return "AuthenticatedOwnerPrincipal[redacted]"; }
}
