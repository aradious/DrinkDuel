package com.drinkduel.transport;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Explicit local-only adapter. Replaced by verified Google login later; domain authorization stays intact. */
@Component
@Order(10)
@Profile("local-preview & !prod & !production")
@ConditionalOnProperty(name = "drinkduel.dev-identity.enabled", havingValue = "true")
public final class LocalGmIdentityFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean login = path.equals("/api/dev/session");
        if (!login) { chain.doFilter(request, response); return; }
        if (!BrowserOrigin.loopback(request) || !BrowserOrigin.sameOrigin(request)) {
            response.setStatus(403); return;
        }
        response.setHeader("Cache-Control", "no-store");
        if (login) {
            if ("POST".equals(request.getMethod())) {
                HostIdentitySession.create(request);
                response.setStatus(204);
            } else if ("GET".equals(request.getMethod())) {
                response.setContentType("application/json");
                response.getWriter().write("{\"localDevelopment\":true}");
            } else response.setStatus(405);
            return;
        }
    }
}
