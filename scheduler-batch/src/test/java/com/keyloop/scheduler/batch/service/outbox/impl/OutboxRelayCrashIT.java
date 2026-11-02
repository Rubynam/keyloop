package com.keyloop.scheduler.batch.service.outbox.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.keyloop.scheduler.batch.config.outbox.OutboxRelayProperties;
import com.keyloop.scheduler.batch.model.outbox.OutboxEvent;
import com.keyloop.scheduler.batch.service.outbox.OutboxRelayService;
import com.keyloop.scheduler.batch.store.outbox.AvailabilityVersionStore;
import com.keyloop.scheduler.batch.store.outbox.impl.RedisAvailabilityVersionStore;
import com.keyloop.scheduler.batch.support.IntegrationTest;
import com.keyloop.scheduler.batch.support.LogCapture;
import com.keyloop.scheduler.batch.support.OutboxRows;
import com.keyloop.scheduler.batch.support.OutboxRows.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;

/**
 * Story Task 1 (OP2), AC1.1–AC1.3: a try counts even when the JVM dies during the work.
 * The "crash" is an {@link Error} thrown by a store stub for one dealership. The test
 * skips the retry wait with {@code makeDue}, no sleeps.
 */
@TestPropertySource(properties = {
        "scheduler.outbox.relay.poll-delay=1h",
        "scheduler.outbox.relay.batch-size=" + OutboxRelayCrashIT.BATCH_SIZE
})
class OutboxRelayCrashIT extends IntegrationTest {

    static final int BATCH_SIZE = 5;
    static final UUID CRASH_DEALER = UUID.fromString("0194244f-d800-76ca-b4eb-00000000c4a5");
    static final UUID BROKEN_DEALER = UUID.fromString("0194244f-d800-76ca-b4eb-00000000b40c");

    @Autowired
    private OutboxRelayService relay;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private CrashingStore store;
    @Autowired
    private OutboxRelayProperties properties;

    private OutboxRows outbox;

    @BeforeEach
    void setUp() {
        outbox = new OutboxRows(jdbc);
        jdbc.sql("DELETE FROM outbox_events").update();
        store.crashes.set(0);
    }

    /** AC1.1: four crashed tries, then the claim ends the row; no 5th try. */
    @Test
    void rowThatAlwaysCrashesIsFailedAtTheClaimAfterFourTries() {
        UUID id = outbox.insert("AppointmentBooked", payload(CRASH_DEALER, "2030-03-01"));

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThatThrownBy(relay::tick).isInstanceOf(SimulatedCrash.class);

            State state = outbox.state(id);
            assertThat(state.attempts()).isEqualTo(attempt);
            assertThat(state.processedAt()).isNull();
            assertThat(state.failedAt()).isNull();
            outbox.makeDue(id);
        }

        try (LogCapture log = LogCapture.of(OutboxRelayServiceImpl.class)) {
            assertThat(relay.tick()).isEqualTo(1);

            assertThat(log.lines()).anySatisfy(line -> assertThat(line).isEqualTo(
                    "ERROR [" + id + "] Outbox event " + id + " (AppointmentBooked) was marked FAILED after 4"
                            + " attempts that did not finish (the relay stopped during the work);"
                            + " availability may be stale until cache TTL expiry"));
        }
        State state = outbox.state(id);
        assertThat(state.attempts()).isEqualTo(4);
        assertThat(state.failedAt()).isNotNull();
        assertThat(state.processedAt()).isNull();
        assertThat(state.lastError()).isEqualTo("CLAIM_EXHAUSTED: tries started but never finished");
        assertThat(store.crashes).hasValue(4);

        // FAILED is final
        outbox.makeDue(id);
        assertThat(relay.tick()).isZero();
        assertThat(store.crashes).hasValue(4);
    }

    /** AC1.2: only the crashing row loses a try; the rows around it are processed. */
    @Test
    void crashOnOneRowLeavesTheOtherRowsUntouched() {
        UUID before = outbox.insert("AppointmentBooked", payload(UUID.randomUUID(), "2030-03-02"));
        UUID crash = outbox.insert("AppointmentBooked", payload(CRASH_DEALER, "2030-03-02"));
        UUID after = outbox.insert("AppointmentBooked", payload(UUID.randomUUID(), "2030-03-02"));

        assertThatThrownBy(relay::tick).isInstanceOf(SimulatedCrash.class);
        // The crashed row is under its lease now, so the next tick goes past it
        assertThat(relay.tick()).isEqualTo(1);

        for (UUID ok : new UUID[] {before, after}) {
            State state = outbox.state(ok);
            assertThat(state.processedAt()).isNotNull();
            assertThat(state.attempts()).isEqualTo(1);
        }
        State crashed = outbox.state(crash);
        assertThat(crashed.attempts()).isEqualTo(1);
        assertThat(crashed.processedAt()).isNull();
        assertThat(crashed.failedAt()).isNull();
    }

    /** AC1.3: exhausted rows count toward the batch, so a full tick lets the relay go on. */
    @Test
    void exhaustedRowsCountTowardTheBatchSize() {
        for (int i = 0; i < 3; i++) {
            UUID id = outbox.insert("AppointmentBooked", payload(UUID.randomUUID(), "2030-03-03"));
            jdbc.sql("UPDATE outbox_events SET attempts = 4 WHERE id = :id").param("id", id).update();
        }
        for (int i = 0; i < BATCH_SIZE; i++) {
            outbox.insert("AppointmentBooked", payload(UUID.randomUUID(), "2030-03-03"));
        }

        assertThat(relay.tick()).isEqualTo(BATCH_SIZE);
        assertThat(relay.tick()).isEqualTo(3);
        assertThat(relay.tick()).isZero();

        assertThat(count("failed_at IS NOT NULL AND last_error = 'CLAIM_EXHAUSTED: tries started but never finished'"))
                .isEqualTo(3);
        assertThat(count("processed_at IS NOT NULL AND attempts = 1")).isEqualTo(BATCH_SIZE);
    }

    /** Story §4.3: a non-Redis RuntimeException in the work follows the Redis retry path and stops the tick. */
    @Test
    void otherRuntimeExceptionIsRetriedLikeARedisError() {
        UUID broken = outbox.insert("AppointmentBooked", payload(BROKEN_DEALER, "2030-03-04"));
        UUID next = outbox.insert("AppointmentBooked", payload(UUID.randomUUID(), "2030-03-04"));

        assertThat(relay.tick()).isEqualTo(1);

        State state = outbox.state(broken);
        assertThat(state.attempts()).isEqualTo(1);
        assertThat(state.failedAt()).isNull();
        assertThat(state.processedAt()).isNull();
        assertThat(state.lastError()).startsWith("IllegalStateException: ");
        assertThat(state.nextAttemptAt()).isAfter(outbox.dbNow());
        // The tick stopped after the error: the next row waits for the next tick
        assertThat(outbox.state(next).processedAt()).isNull();
    }

    /** The retry wait comes from the failed work, not from the claim lease. */
    @Test
    void failedWorkSetsItsOwnRetryDelayAfterTheLease() {
        UUID id = outbox.insert("AppointmentBooked", payload(BROKEN_DEALER, "2030-03-04"));
        OutboxRelayServiceImpl impl = AopTestUtils.getTargetObject(relay);
        OutboxEvent claimed = impl.claimNext().orElseThrow().event();
        // The lease ran out before the work failed
        jdbc.sql("UPDATE outbox_events SET next_attempt_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", id).update();
        Instant before = outbox.dbNow();

        assertThat(impl.work(claimed)).isFalse();

        State state = outbox.state(id);
        assertThat(state.attempts()).isEqualTo(1);
        assertThat(state.failedAt()).isNull();
        assertThat(state.lastError()).isNotBlank();
        assertThat(state.nextAttemptAt()).isAfterOrEqualTo(before.plus(properties.retryDelay()));
    }

    private long count(String where) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE " + where).query(Long.class).single();
    }

    private static String payload(UUID dealer, String date) {
        return "{\"dealershipId\":\"" + dealer + "\",\"dates\":[\"" + date + "\"]}";
    }

    /** Stands for a JVM crash: an {@link Error}, which the relay must not catch. */
    static final class SimulatedCrash extends Error {
        SimulatedCrash() {
            super("simulated JVM crash during the work");
        }
    }

    /** Real Redis store, except that {@link #CRASH_DEALER} always "crashes". */
    static final class CrashingStore implements AvailabilityVersionStore {

        final AtomicInteger crashes = new AtomicInteger();
        private final AvailabilityVersionStore real;

        CrashingStore(AvailabilityVersionStore real) {
            this.real = real;
        }

        @Override
        public void bump(UUID dealershipId, LocalDate date) {
            if (CRASH_DEALER.equals(dealershipId)) {
                crashes.incrementAndGet();
                throw new SimulatedCrash();
            }
            if (BROKEN_DEALER.equals(dealershipId)) {
                throw new IllegalStateException("not a Redis error");
            }
            real.bump(dealershipId, date);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CrashingStoreConfig {

        @Bean
        @Primary
        CrashingStore crashingStore(StringRedisTemplate redis, OutboxRelayProperties properties) {
            return new CrashingStore(new RedisAvailabilityVersionStore(redis, properties));
        }
    }
}
