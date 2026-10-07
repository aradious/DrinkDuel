package com.drinkduel.logging;

import java.util.UUID;
import org.slf4j.Logger;

/** Logging helpers that preserve stack locations without recording exception messages or causes. */
public final class SafeLog {
    private SafeLog() {}

    public static String correlationId() {
        return UUID.randomUUID().toString();
    }

    public static void unexpected(Logger logger, String event, String correlationId, Throwable exception) {
        var redacted = new RuntimeException("Redacted " + exception.getClass().getName());
        redacted.setStackTrace(exception.getStackTrace());
        logger.error("event={} correlationId={} exceptionType={}",
                event, correlationId, exception.getClass().getName(), redacted);
    }
}
