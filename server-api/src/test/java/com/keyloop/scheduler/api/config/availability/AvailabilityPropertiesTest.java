package com.keyloop.scheduler.api.config.availability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Spec07 BK-06: a bad slot-step fails at startup with a binding error, a good one binds. */
class AvailabilityPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AvailabilityConfig.class);

    @ParameterizedTest
    @ValueSource(strings = {"0m", "7m", "-15m", "90s"})
    void badStepFailsAtBoot(String step) {
        runner.withPropertyValues("scheduler.availability.slot-step=" + step)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("slot-step"));
        runner.withPropertyValues("scheduler.availability.slot-step=" + step)
                .run(context -> assertThat(context.getStartupFailure()).hasCauseInstanceOf(BindException.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"15m", "30m", "1h", "1d"})
    void goodStepBinds(String step) {
        runner.withPropertyValues("scheduler.availability.slot-step=" + step)
                .run(context -> assertThat(context.getBean(AvailabilityProperties.class).slotStep())
                        .isEqualTo(Duration.parse("PT" + step.toUpperCase().replace("1D", "24H"))));
    }

    @Test
    void defaultIsFifteenMinutes() {
        runner.run(context -> assertThat(context.getBean(AvailabilityProperties.class).stepMinutes()).isEqualTo(15));
    }
}
