package com.drinkduel.realtime;

import com.drinkduel.logging.SafeLog;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.OriginHandshakeInterceptor;

/** Records safe handshake outcomes while retaining Spring's same-origin decision. */
final class LoggingOriginHandshakeInterceptor implements HandshakeInterceptor {
    static final String CORRELATION_ATTRIBUTE = LoggingOriginHandshakeInterceptor.class.getName() + ".correlation";
    private static final Logger LOG = LoggerFactory.getLogger(LoggingOriginHandshakeInterceptor.class);
    private final OriginHandshakeInterceptor origin = new OriginHandshakeInterceptor();

    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                              WebSocketHandler handler, Map<String, Object> attributes) throws Exception {
        String correlationId = SafeLog.correlationId();
        attributes.put(CORRELATION_ATTRIBUTE, correlationId);
        try {
            boolean accepted = origin.beforeHandshake(request, response, handler, attributes);
            if (!accepted)
                LOG.warn("event=ws.handshake.rejected correlationId={} reason=ORIGIN_REJECTED", correlationId);
            return accepted;
        } catch (RuntimeException exception) {
            SafeLog.unexpected(LOG, "ws.handshake.failed", correlationId, exception);
            throw exception;
        }
    }

    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                         WebSocketHandler handler, Exception exception) {
        origin.afterHandshake(request, response, handler, exception);
        if (exception != null)
            SafeLog.unexpected(LOG, "ws.handshake.failed", SafeLog.correlationId(), exception);
    }
}
