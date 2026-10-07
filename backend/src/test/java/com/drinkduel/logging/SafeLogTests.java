package com.drinkduel.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class SafeLogTests {
    @Test void unexpectedErrorsKeepStackLocationsButRedactMessagesAndCauses() {
        Logger logger = (Logger) LoggerFactory.getLogger("safe-log-test");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        boolean additive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(appender);
        try {
            var cause = new IllegalArgumentException("guest-token-secret");
            var failure = new IllegalStateException("JSESSIONID=session-secret", cause);

            SafeLog.unexpected(logger, "test.failed", "safe-correlation", failure);

            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.getFirst();
            String rendered = event.getFormattedMessage() + " " + event.getThrowableProxy().getMessage();
            assertTrue(rendered.contains("test.failed"));
            assertTrue(rendered.contains("safe-correlation"));
            assertTrue(rendered.contains(IllegalStateException.class.getName()));
            assertFalse(rendered.contains("JSESSIONID"));
            assertFalse(rendered.contains("session-secret"));
            assertFalse(rendered.contains("guest-token-secret"));
            assertNull(event.getThrowableProxy().getCause());
            assertTrue(event.getThrowableProxy().getStackTraceElementProxyArray().length > 0);
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(additive);
            appender.stop();
        }
    }
}
