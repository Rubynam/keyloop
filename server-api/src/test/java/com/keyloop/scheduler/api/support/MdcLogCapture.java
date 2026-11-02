package com.keyloop.scheduler.api.support;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/** Collects the MDC of every log event that reaches the root logger config while open (Log4j2 is the backend). */
public final class MdcLogCapture implements AutoCloseable {

    private final List<Map<String, String>> contexts = new CopyOnWriteArrayList<>();
    private final LoggerContext loggerContext;
    private final AbstractAppender appender;

    private MdcLogCapture() {
        loggerContext = (LoggerContext) LogManager.getContext(false);
        appender = new AbstractAppender("mdc-capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                contexts.add(new HashMap<>(event.getContextData().toMap()));
            }
        };
        appender.start();
        rootLogger().addAppender(appender, null, null);
    }

    public static MdcLogCapture open() {
        return new MdcLogCapture();
    }

    /** MDC maps of the events that carry {@code key = value}. */
    public List<Map<String, String>> withEntry(String key, String value) {
        return contexts.stream().filter(context -> value.equals(context.get(key))).toList();
    }

    @Override
    public void close() {
        rootLogger().removeAppender(appender.getName());
        appender.stop();
    }

    private LoggerConfig rootLogger() {
        return loggerContext.getConfiguration().getRootLogger();
    }
}
