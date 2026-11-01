package com.keyloop.scheduler.api.config.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/** Spec07 §9: {@code scheduler.booking.max-pairs} binds, defaults to 3, and must be positive. */
class BookingPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(AppointmentConfig.class);

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void notPositiveFailsAtBoot(String maxPairs) {
        runner.withPropertyValues("scheduler.booking.max-pairs=" + maxPairs)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasCauseInstanceOf(BindException.class);
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("maxPairs");
                });
    }

    @Test
    void twoBinds() {
        runner.withPropertyValues("scheduler.booking.max-pairs=2")
                .run(context -> assertThat(context.getBean(BookingProperties.class).maxPairs()).isEqualTo(2));
    }

    @Test
    void defaultIsThree() {
        runner.run(context -> assertThat(context.getBean(BookingProperties.class).maxPairs()).isEqualTo(3));
    }
}
