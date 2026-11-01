package com.keyloop.scheduler.api.config.appointment;

import java.time.Clock;

import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes Bean Validation time checks ({@code @Future}, {@code @Past}) read the app
 * {@link Clock} bean, not the JVM clock. Tests that move a {@code MutableClock} then
 * move "now" for the edge checks too. Spec07 D5.
 *
 * <pre>
 * Clock bean (2030-01-01) ──► ClockProvider ──► @Future startTime 2029-12-31  →  400
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
public class ValidationClockConfig {

    /** Hands the {@link Clock} bean to Hibernate Validator as its {@code ClockProvider}. */
    @Bean
    ValidationConfigurationCustomizer clockProviderCustomizer(Clock clock) {
        return configuration -> configuration.clockProvider(() -> clock);
    }
}
