package com.keyloop.scheduler.batch.config.outbox;

import static com.keyloop.scheduler.batch.config.outbox.PropertiesTestSupport.assertFailsNaming;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import com.keyloop.scheduler.batch.constant.outbox.RelayConstants;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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

    private static final String NOT_NULL = "must not be null";
    private static final String LESS_OR_EQUAL = "must be less than or equal to ";
    private static final String SHORTER_OR_EQUAL = "must be shorter than or equal to ";

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
        without("poll-delay", "retry-delay", "version-key-ttl").run(context -> {
            assertFailsNaming(context, "pollDelay");
            assertFailsNaming(context, "retryDelay");
            assertFailsNaming(context, "versionKeyTtl");
        });
    }

    @Test
    void missingBatchSizeFails() {
        without("batch-size").run(context -> assertFailsNaming(context, "batchSize", NOT_NULL));
    }

    @Test
    void missingMaxRetriesFails() {
        without("max-retries").run(context -> assertFailsNaming(context, "maxRetries", NOT_NULL));
    }

    @Test
    void countsAtMaxStart() {
        withDefaults("batch-size=" + RelayConstants.MAX_BATCH_SIZE,
                "max-retries=" + RelayConstants.MAX_RETRIES_LIMIT).run(context -> {
            assertThat(context).hasNotFailed();
            OutboxRelayProperties properties = context.getBean(OutboxRelayProperties.class);
            assertThat(properties.batchSize()).isEqualTo(RelayConstants.MAX_BATCH_SIZE);
            assertThat(properties.maxRetries()).isEqualTo(RelayConstants.MAX_RETRIES_LIMIT);
        });
    }

    @Test
    void batchSizeOverMaxFails() {
        withDefaults("batch-size=" + (RelayConstants.MAX_BATCH_SIZE + 1)).run(context ->
                assertFailsNaming(context, "batchSize", LESS_OR_EQUAL + RelayConstants.MAX_BATCH_SIZE));
    }

    @Test
    void maxRetriesOverMaxFails() {
        withDefaults("max-retries=" + (RelayConstants.MAX_RETRIES_LIMIT + 1)).run(context ->
                assertFailsNaming(context, "maxRetries", LESS_OR_EQUAL + RelayConstants.MAX_RETRIES_LIMIT));
    }

    @Test
    void maxRetriesIntMaxFails() {
        withDefaults("max-retries=" + Integer.MAX_VALUE).run(context ->
                assertFailsNaming(context, "maxRetries", LESS_OR_EQUAL + RelayConstants.MAX_RETRIES_LIMIT));
    }

    @Test
    void durationsAtMaxStart() {
        withDefaults(
                "poll-delay=" + maxPollDelay().toMillis() + "ms",
                "retry-delay=" + maxRetryDelay().toMillis() + "ms",
                "version-key-ttl=" + maxVersionKeyTtl().toMillis() + "ms").run(context -> {
            assertThat(context).hasNotFailed();
            OutboxRelayProperties properties = context.getBean(OutboxRelayProperties.class);
            assertThat(properties.pollDelay()).isEqualTo(maxPollDelay());
            assertThat(properties.retryDelay()).isEqualTo(maxRetryDelay());
            assertThat(properties.versionKeyTtl()).isEqualTo(maxVersionKeyTtl());
        });
    }

    @Test
    void pollDelayOverMaxFails() {
        withDefaults("poll-delay=" + (maxPollDelay().toMillis() + 1) + "ms").run(context ->
                assertFailsNaming(context, "pollDelay", SHORTER_OR_EQUAL + "1 hour"));
    }

    @Test
    void retryDelayOverMaxFails() {
        withDefaults("retry-delay=" + (maxRetryDelay().toMillis() + 1) + "ms").run(context ->
                assertFailsNaming(context, "retryDelay", SHORTER_OR_EQUAL + "1 hour"));
    }

    @Test
    void versionKeyTtlOverMaxFails() {
        withDefaults("version-key-ttl=" + (maxVersionKeyTtl().toMillis() + 1) + "ms").run(context ->
                assertFailsNaming(context, "versionKeyTtl", SHORTER_OR_EQUAL + "30 days"));
    }

    private static Duration maxPollDelay() {
        return Duration.ofHours(RelayConstants.MAX_POLL_DELAY_HOURS);
    }

    private static Duration maxRetryDelay() {
        return Duration.ofHours(RelayConstants.MAX_RETRY_DELAY_HOURS);
    }

    private static Duration maxVersionKeyTtl() {
        return Duration.ofDays(RelayConstants.MAX_VERSION_KEY_TTL_DAYS);
    }

    /** Defaults with some values replaced, given as {@code name=value} without the prefix. */
    private ApplicationContextRunner withDefaults(String... overrides) {
        return PropertiesTestSupport.withDefaults(runner, PREFIX, DEFAULTS, overrides);
    }

    /** Defaults with the given keys (without the prefix) removed. */
    private ApplicationContextRunner without(String... keys) {
        return PropertiesTestSupport.without(runner, PREFIX, DEFAULTS, keys);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboxRelayProperties.class)
    static class PropertiesConfig {
    }
}
