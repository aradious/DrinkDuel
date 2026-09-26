package com.drinkduel.realtime;

import com.drinkduel.room.PlayerIdentity;
import java.security.Principal;
import java.util.Objects;

/** Only a trusted server authentication adapter may populate this handshake principal.
 * No header, query parameter, or command payload is converted into an owner principal.
 * Google authentication and room creation endpoints are deferred.
 */
public record AuthenticatedOwnerPrincipal(PlayerIdentity.Google identity) implements Principal {
    public AuthenticatedOwnerPrincipal { Objects.requireNonNull(identity); }
    @Override public String getName() { return identity.subject(); }
    @Override public String toString() { return "AuthenticatedOwnerPrincipal[redacted]"; }
}
