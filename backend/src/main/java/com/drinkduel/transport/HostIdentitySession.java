package com.drinkduel.transport;

import com.drinkduel.realtime.AuthenticatedOwnerPrincipal;
import com.drinkduel.room.PlayerIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.HexFormat;

final class HostIdentitySession {
    static final String OWNER = HostIdentitySession.class.getName() + ".owner";
    private static final SecureRandom RANDOM = new SecureRandom();

    private HostIdentitySession() {}

    static AuthenticatedOwnerPrincipal current(HttpServletRequest request) {
        var session = request.getSession(false);
        Object owner = session == null ? null : session.getAttribute(OWNER);
        return owner instanceof AuthenticatedOwnerPrincipal principal ? principal : null;
    }

    static AuthenticatedOwnerPrincipal create(HttpServletRequest request) {
        var session = request.getSession(true);
        var current = current(request);
        if (current != null) return current;
        request.changeSessionId();
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        var principal = new AuthenticatedOwnerPrincipal(
                new PlayerIdentity.Host(HexFormat.of().formatHex(bytes)));
        session.setAttribute(OWNER, principal);
        return principal;
    }
}
