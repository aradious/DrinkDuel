package com.drinkduel.transport;

import com.drinkduel.logging.SafeLog;
import com.drinkduel.realtime.AuthenticatedOwnerPrincipal;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.security.Principal;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Establishes and restores an opaque server-issued host identity for HTTP and WebSocket use. */
@Component
@Order(20)
public final class HostIdentityFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(HostIdentityFilter.class);

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
                String correlationId = SafeLog.correlationId();
                if (!BrowserOrigin.sameOrigin(request)) {
                    LOG.warn("event=host.session.rejected correlationId={} reason=ORIGIN_REJECTED",
                            correlationId);
                    response.setStatus(403);
                    return;
                }
                try {
                    HostIdentitySession.create(request);
                } catch (RuntimeException exception) {
                    SafeLog.unexpected(LOG, "host.session.failed", correlationId, exception);
                    throw exception;
                }
                LOG.info("event=host.session.created correlationId={}", correlationId);
                response.setStatus(204);
                return;
            }
            LOG.warn("event=host.session.rejected correlationId={} reason=METHOD_NOT_ALLOWED",
                    SafeLog.correlationId());
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
