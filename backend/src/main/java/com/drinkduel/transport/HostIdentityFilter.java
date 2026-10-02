package com.drinkduel.transport;

import com.drinkduel.realtime.AuthenticatedOwnerPrincipal;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.security.Principal;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Establishes and restores an opaque server-issued host identity for HTTP and WebSocket use. */
@Component
@Order(20)
public final class HostIdentityFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean sessionEndpoint = path.equals("/api/host/session");
        boolean identityPath = sessionEndpoint || path.equals("/api/rooms") || path.equals("/ws/rooms");
        if (!identityPath) {
            chain.doFilter(request, response);
            return;
        }

        response.setHeader("Cache-Control", "no-store");
        if (sessionEndpoint) {
            if ("GET".equals(request.getMethod())) {
                response.setStatus(204);
                return;
            }
            if ("POST".equals(request.getMethod())) {
                if (!BrowserOrigin.sameOrigin(request)) {
                    response.setStatus(403);
                    return;
                }
                HostIdentitySession.create(request);
                response.setStatus(204);
                return;
            }
            response.setStatus(405);
            return;
        }

        AuthenticatedOwnerPrincipal owner = HostIdentitySession.current(request);
        if (owner == null) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public Principal getUserPrincipal() { return owner; }
            @Override public String getRemoteUser() { return owner.getName(); }
        }, response);
    }
}
