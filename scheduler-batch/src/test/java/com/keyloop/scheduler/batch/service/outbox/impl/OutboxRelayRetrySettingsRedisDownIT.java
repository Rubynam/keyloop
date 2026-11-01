package com.keyloop.scheduler.batch.service.outbox.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.keyloop.scheduler.batch.service.outbox.OutboxRelayService;
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
 * Story outbox-relay Task 3, AC3.2 Redis-down path: the retry policy follows
 * {@code max-retries} and {@code retry-delay} from config. With {@code max-retries=1} and
 * {@code retry-delay=500ms} a row gets 2 tries 500 ms apart, then is FAILED.
 * Stops the Redis container of its own context, which is thrown away afterwards.
 */
@TestPropertySource(properties = {
        "scheduler.outbox.relay.poll-delay=1h",
        "scheduler.outbox.relay.max-retries=1",
        "scheduler.outbox.relay.retry-delay=500ms"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxRelayRetrySettingsRedisDownIT extends IntegrationTest {

    private static final Duration RETRY_DELAY = Duration.ofMillis(500);

    @Autowired
    private OutboxRelayService relay;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private RedisContainer redisContainer;

    @Test
    void redisErrorsRetryOnce500MillisApartThenFail() {
        OutboxRows outbox = new OutboxRows(jdbc);
        jdbc.sql("DELETE FROM outbox_events").update();
        UUID id = outbox.insert("AppointmentBooked", OutboxRows.sample("AppointmentBooked"));
        redisContainer.stop();

        Instant before = outbox.dbNow();
        relay.tick();
        Instant after = outbox.dbNow();

        State first = outbox.state(id);
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.failedAt()).isNull();
        assertThat(first.processedAt()).isNull();
        assertThat(first.lastError()).isNotBlank();
        assertThat(first.nextAttemptAt()).isBetween(before.plus(RETRY_DELAY), after.plus(RETRY_DELAY));
        outbox.makeDue(id);

        try (LogCapture log = LogCapture.of(OutboxRelayServiceImpl.class)) {
            relay.tick();

            assertThat(log.lines()).anySatisfy(line -> assertThat(line).startsWith(
                    "ERROR [" + id + "] Cache invalidation failed after 2 attempts for outbox event " + id
                            + " (AppointmentBooked, dealership 0194244f-d800-76ca-b4eb-252b0f21ddb6, dates [2026-10-06]);"
                            + " availability may be stale until cache TTL expiry. Last error: "));
        }
        State state = outbox.state(id);
        assertThat(state.attempts()).isEqualTo(2);
        assertThat(state.failedAt()).isNotNull();
        assertThat(state.processedAt()).isNull();
        assertThat(state.lastError()).isNotBlank();

        // FAILED is final
        outbox.makeDue(id);
        assertThat(relay.tick()).isZero();
    }
}
