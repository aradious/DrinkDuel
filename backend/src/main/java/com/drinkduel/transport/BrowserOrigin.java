package com.drinkduel.transport;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;

/** Cookie-authenticated mutations and development identity must originate from this browser origin. */
public final class BrowserOrigin {
    private BrowserOrigin() {}
    public static boolean sameOrigin(HttpServletRequest request) {
        try {
            var origin = URI.create(request.getHeader("Origin"));
            int port = origin.getPort() < 0 ? ("https".equals(origin.getScheme()) ? 443 : 80) : origin.getPort();
            return origin.getRawUserInfo() == null && origin.getRawQuery() == null && origin.getRawFragment() == null
                    && (origin.getPath() == null || origin.getPath().isEmpty())
                    && request.getScheme().equals(origin.getScheme())
                    && request.getServerName().equalsIgnoreCase(origin.getHost()) && request.getServerPort() == port;
        } catch (RuntimeException ignored) { return false; }
    }
    public static boolean loopback(HttpServletRequest request) {
        return local(request.getRemoteAddr()) && local(request.getServerName());
    }
    private static boolean local(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host) || "0:0:0:0:0:0:0:1".equals(host);
    }
}
