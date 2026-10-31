package com.keyloop.scheduler.api.support;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/** Collects "LEVEL message" lines of one logger while open (Log4j2 is the backend). */
public final class LogCapture implements AutoCloseable {

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final Logger logger;
    private final AbstractAppender appender;

    private LogCapture(Class<?> source) {
        logger = (Logger) LogManager.getLogger(source);
        appender = new AbstractAppender("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                lines.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        logger.addAppender(appender);
    }

    public static LogCapture of(Class<?> source) {
        return new LogCapture(source);
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }

    @Override
    public void close() {
        logger.removeAppender(appender);
        appender.stop();
    }
}
