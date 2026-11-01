package com.keyloop.scheduler.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MethodLoggingAspectTest {

    private final MethodLoggingAspect aspect = new MethodLoggingAspect();
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final Logger logger = (Logger) LogManager.getLogger(MethodLoggingAspect.class);
    private final AbstractAppender appender =
            new AbstractAppender("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    lines.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
                }
            };

    private Level previousLevel;

    @BeforeEach
    void capture() {
        // No log4j2 config in this module's tests: the default root level (ERROR) would drop WARN
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.removeAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    @Test
    void clientErrorIsLoggedAtWarn() {
        assertThatThrownBy(() -> aspect.logCall(failingCall(new UnknownThing())))
                .isInstanceOf(UnknownThing.class);

        assertThat(lines).singleElement().satisfies(line -> assertThat(line)
                .startsWith("WARN [Shop][find][").endsWith("[UnknownThing: thing T1 not found]"));
    }

    @Test
    void otherFailureStaysAtError() {
        assertThatThrownBy(() -> aspect.logCall(failingCall(new IllegalStateException("broken"))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(lines).singleElement().satisfies(line -> assertThat(line)
                .startsWith("ERROR [Shop][find][").endsWith("[IllegalStateException: broken]"));
    }

    private static ProceedingJoinPoint failingCall(Throwable error) throws Throwable {
        ProceedingJoinPoint call = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(call.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn(Shop.class);
        when(signature.getName()).thenReturn("find");
        when(call.getArgs()).thenReturn(new Object[] {"T1"});
        when(call.proceed()).thenThrow(error);
        return call;
    }

    /** Stands in for the type the aspect wraps; only its simple name is logged. */
    private static final class Shop {
    }

    private static final class UnknownThing extends RuntimeException implements ClientError {
        UnknownThing() {
            super("thing T1 not found");
        }
    }
}
