package com.keyloop.scheduler.batch.config.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Story outbox-relay Task 3, AC3.1: bad relay settings stop the context at startup and
 * the error names the property. No containers: only the properties are bound.
 */
class OutboxRelayPropertiesTest {

    private static final String PREFIX = "scheduler.outbox.relay.";

    /** Same values as the shipped application.yml. */
    private static final List<String> DEFAULTS = List.of(
            PREFIX + "enabled=true",
            PREFIX + "poll-delay=500ms",
            PREFIX + "batch-size=100",
            PREFIX + "max-retries=3",
            PREFIX + "retry-delay=2s",
            PREFIX + "version-key-ttl=2d");

    private static Locale previousLocale;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfig.class);

    /** Hibernate Validator translates messages: pin English so the message checks work on any machine. */
    @BeforeAll
    static void pinEnglish() {
        previousLocale = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
    }

    @AfterAll
    static void restoreLocale() {
        Locale.setDefault(previousLocale);
    }

    @Test
    void defaultsStart() {
        withDefaults().run(context -> {
            assertThat(context).hasNotFailed();
            OutboxRelayProperties properties = context.getBean(OutboxRelayProperties.class);
            assertThat(properties.batchSize()).isEqualTo(100);
            assertThat(properties.maxRetries()).isEqualTo(3);
            assertThat(properties.retryDelay()).isEqualTo(Duration.ofSeconds(2));
        });
    }

    @Test
    void zeroBatchSizeFails() {
        withDefaults("batch-size=0").run(context ->
                assertFailsNaming(context, "batchSize", "must be greater than 0"));
    }

    @Test
    void negativeBatchSizeFails() {
        withDefaults("batch-size=-5").run(context ->
                assertFailsNaming(context, "batchSize", "must be greater than 0"));
    }

    @Test
    void negativeMaxRetriesFails() {
        withDefaults("max-retries=-1").run(context -> assertFailsNaming(context, "maxRetries"));
    }

    @Test
    void zeroMaxRetriesStarts() {
        withDefaults("max-retries=0").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OutboxRelayProperties.class).maxRetries()).isZero();
        });
    }

    @Test
    void zeroRetryDelayFails() {
        withDefaults("retry-delay=0s").run(context -> assertFailsNaming(context, "retryDelay"));
    }

    @Test
    void zeroPollDelayFails() {
        withDefaults("poll-delay=0s").run(context -> assertFailsNaming(context, "pollDelay"));
    }

    @Test
    void zeroVersionKeyTtlFails() {
        withDefaults("version-key-ttl=0s").run(context -> assertFailsNaming(context, "versionKeyTtl"));
    }

    @Test
    void missingDurationsFail() {
        Set<String> durationKeys = Set.of(PREFIX + "poll-delay", PREFIX + "retry-delay", PREFIX + "version-key-ttl");
        List<String> withoutDurations = DEFAULTS.stream()
                .filter(p -> !durationKeys.contains(p.substring(0, p.indexOf('='))))
                .toList();
        runner.withPropertyValues(withoutDurations.toArray(String[]::new)).run(context -> {
            assertFailsNaming(context, "pollDelay");
            assertFailsNaming(context, "retryDelay");
            assertFailsNaming(context, "versionKeyTtl");
        });
    }

    /** Defaults with some values replaced, given as {@code name=value} without the prefix. */
    private ApplicationContextRunner withDefaults(String... overrides) {
        List<String> values = new ArrayList<>(DEFAULTS);
        for (String override : overrides) {
            values.add(PREFIX + override);
        }
        return runner.withPropertyValues(values.toArray(String[]::new));
    }

    private static void assertFailsNaming(AssertableApplicationContext context, String... texts) {
        assertThat(context).hasFailed();
        String message = fullMessage(context.getStartupFailure());
        for (String text : texts) {
            assertThat(message).contains(text);
        }
    }

    private static String fullMessage(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            text.append(t).append('\n');
        }
        return text.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboxRelayProperties.class)
    static class PropertiesConfig {
    }
}
