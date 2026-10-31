package com.keyloop.scheduler.batch.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.keyloop.scheduler.batch.support.IntegrationTest;
import com.keyloop.scheduler.batch.support.LogCapture;
import com.keyloop.scheduler.batch.support.OutboxRows;
import com.keyloop.scheduler.batch.support.OutboxRows.State;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * spec-availability-ops CAP-6, Spec04 §4.3: with Redis stopped, a row is tried once plus
 * 3 retries, 2 s apart, then FAILED with the §4.6 ERROR message. The test skips the
 * 2 s waits by making the row due again after checking {@code next_attempt_at}.
 * Stops the Redis container of its own context, which is thrown away afterwards.
 */
@TestPropertySource(properties = "scheduler.outbox.relay.poll-delay=1h")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxRelayRedisDownIT extends IntegrationTest {

    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);

    @Autowired
    private OutboxRelay relay;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private RedisContainer redisContainer;

    @Test
    void redisErrorsRetryThreeTimesTwoSecondsApartThenFail() {
        OutboxRows outbox = new OutboxRows(jdbc);
        UUID id = outbox.insert("AppointmentBooked", OutboxRows.sample("AppointmentBooked"));
        redisContainer.stop();

        for (int attempt = 1; attempt <= 3; attempt++) {
            Instant before = outbox.dbNow();
            relay.tick();
            Instant after = outbox.dbNow();

            State state = outbox.state(id);
            assertThat(state.attempts()).isEqualTo(attempt);
            assertThat(state.failedAt()).isNull();
            assertThat(state.processedAt()).isNull();
            assertThat(state.lastError()).isNotBlank();
            assertThat(state.nextAttemptAt()).isBetween(before.plus(RETRY_DELAY), after.plus(RETRY_DELAY));

            // Not due before its 2 s: a tick now leaves it alone
            assertThat(relay.tick()).isZero();
            outbox.makeDue(id);
        }

        try (LogCapture log = LogCapture.of(OutboxRelay.class)) {
            relay.tick();

            assertThat(log.lines()).anySatisfy(line -> assertThat(line).startsWith(
                    "ERROR [" + id + "] Cache invalidation failed after 4 attempts for outbox event " + id
                            + " (AppointmentBooked, dealership 0194244f-d800-76ca-b4eb-252b0f21ddb6, dates [2026-10-06]);"
                            + " availability may be stale until cache TTL expiry. Last error: "));
        }
        State state = outbox.state(id);
        assertThat(state.attempts()).isEqualTo(4);
        assertThat(state.failedAt()).isNotNull();
        assertThat(state.processedAt()).isNull();
        assertThat(state.lastError()).isNotBlank();

        // FAILED is final
        outbox.makeDue(id);
        assertThat(relay.tick()).isZero();
    }
}
