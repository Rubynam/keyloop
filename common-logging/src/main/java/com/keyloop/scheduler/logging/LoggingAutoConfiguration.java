package com.keyloop.scheduler.logging;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Registers the aspect in every app that depends on common-logging (outside their component scan). */
@AutoConfiguration
public class LoggingAutoConfiguration {

    @Bean
    MethodLoggingAspect methodLoggingAspect() {
        return new MethodLoggingAspect();
    }
}
