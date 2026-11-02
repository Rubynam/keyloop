package com.keyloop.scheduler.batch.config.outbox;

import static com.keyloop.scheduler.batch.config.outbox.PropertiesTestSupport.assertFailsNaming;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Bad cleanup settings stop the context at startup and the error names the property,
 * even when cleanup is disabled. No containers: only the properties are bound.
 */
class OutboxCleanupPropertiesTest {

    private static final String PREFIX = "scheduler.outbox.cleanup.";

    /** Same values as the shipped application.yml. */
    private static final List<String> DEFAULTS = List.of(
            PREFIX + "enabled=false",
            PREFIX + "cron=0 0 3 * * *",
            PREFIX + "processed-retention=7d",
            PREFIX + "failed-retention=30d",
            PREFIX + "batch-metadata-retention=7d");

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
            OutboxCleanupProperties properties = context.getBean(OutboxCleanupProperties.class);
            assertThat(properties.cron()).isEqualTo("0 0 3 * * *");
            assertThat(properties.failedRetention()).isEqualTo(Duration.ofDays(30));
        });
    }

    @Test
    void blankCronFails() {
        withDefaults("cron=").run(context -> assertFailsNaming(context, "cron", "must not be blank"));
    }

    @Test
    void missingCronFails() {
        without("cron").run(context -> assertFailsNaming(context, "cron", "must not be blank"));
    }

    @ParameterizedTest
    @CsvSource({
        "processed-retention, processedRetention",
        "failed-retention, failedRetention",
        "batch-metadata-retention, batchMetadataRetention"
    })
    void zeroRetentionFails(String key, String field) {
        withDefaults(key + "=0s").run(context -> assertFailsNaming(context, field));
    }

    @ParameterizedTest
    @CsvSource({
        "processed-retention, processedRetention",
        "failed-retention, failedRetention",
        "batch-metadata-retention, batchMetadataRetention"
    })
    void missingRetentionFails(String key, String field) {
        without(key).run(context -> assertFailsNaming(context, field, "must not be null"));
    }

    @Test
    void disabledStillValidated() {
        withDefaults("enabled=false", "failed-retention=0s").run(context ->
                assertFailsNaming(context, "failedRetention"));
    }

    private ApplicationContextRunner withDefaults(String... overrides) {
        return PropertiesTestSupport.withDefaults(runner, PREFIX, DEFAULTS, overrides);
    }

    private ApplicationContextRunner without(String... keys) {
        return PropertiesTestSupport.without(runner, PREFIX, DEFAULTS, keys);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboxCleanupProperties.class)
    static class PropertiesConfig {
    }
}
