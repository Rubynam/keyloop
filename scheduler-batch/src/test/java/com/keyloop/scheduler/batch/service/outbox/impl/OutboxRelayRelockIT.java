package com.keyloop.scheduler.batch.service.outbox.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story Task 2, AC2.1–AC2.3: the work transaction locks the claimed row again by id and
 * claimed try, and skips it when the row is no longer ours. Rows are locked or changed by
 * hand in another transaction, the same shape as {@code rowLockedByAnotherInstanceIsSkipped}.
 */
@TestPropertySource(properties = {
        "scheduler.outbox.relay.poll-delay=1h",
        "scheduler.outbox.relay.retry-delay=" + OutboxRelayRelockIT.RETRY_DELAY_MS + "ms"
})
class OutboxRelayRelockIT extends IntegrationTest {

    static final long RETRY_DELAY_MS = 300;
    static final UUID SLOW_DEALER = UUID.fromString("0194244f-d800-76ca-b4eb-000000005104");
    static final LocalDate SLOW_DATE = LocalDate.parse("2030-04-03");

    @Autowired
    private OutboxRelayService relay;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private SlowStore store;

    private OutboxRows outbox;
    private OutboxRelayServiceImpl impl;

    @BeforeEach
    void setUp() {
        outbox = new OutboxRows(jdbc);
        impl = AopTestUtils.getTargetObject(relay);
        jdbc.sql("DELETE FROM outbox_events").update();
        redis.delete(key(SLOW_DEALER, SLOW_DATE));
        store.reset();
    }

    /** AC2.1: another connection holds the row lock when TX B starts. */
    @Test
    void rowLockedElsewhereIsSkippedAndLeftUntouched() throws Exception {
        UUID dealer = UUID.randomUUID();
        UUID id = outbox.insert("AppointmentBooked", payload(dealer, "2030-04-01"));
        OutboxEvent claimed = claim();
        State afterClaim = outbox.state(id);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        try (ExecutorService other = Executors.newSingleThreadExecutor()) {
            Future<?> instance = other.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                jdbc.sql("SELECT id FROM outbox_events WHERE id = :id FOR UPDATE").param("id", id)
                        .query(UUID.class).single();
                holding.countDown();
                await(done, Duration.ofSeconds(10));
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

            try (LogCapture log = LogCapture.of(OutboxRelayServiceImpl.class)) {
                assertThat(impl.work(claimed)).isTrue();

                assertThat(log.lines()).anySatisfy(line -> assertThat(line).startsWith("WARN ").endsWith(
                        "Outbox event " + id + " (AppointmentBooked) skipped: another relay instance owns try 1"));
            }
            done.countDown();
            instance.get(10, TimeUnit.SECONDS);
        }
        assertThat(outbox.state(id)).isEqualTo(afterClaim);
        assertThat(redis.hasKey(key(dealer, LocalDate.parse("2030-04-01")))).isFalse();
    }

    /** AC2.2 and the matrix row "terminal state changed": the row changed after the claim. */
    @ParameterizedTest
    @ValueSource(strings = {
            "attempts = attempts + 1",
            "processed_at = now()",
            "failed_at = now(), last_error = 'by hand'"
    })
    void rowChangedAfterTheClaimIsSkipped(String change) {
        UUID dealer = UUID.randomUUID();
        UUID id = outbox.insert("AppointmentBooked", payload(dealer, "2030-04-02"));
        OutboxEvent claimed = claim();
        jdbc.sql("UPDATE outbox_events SET " + change + " WHERE id = :id").param("id", id).update();
        State changed = outbox.state(id);

        try (LogCapture log = LogCapture.of(OutboxRelayServiceImpl.class)) {
            assertThat(impl.work(claimed)).isTrue();

            assertThat(log.lines()).anySatisfy(line -> assertThat(line).startsWith("WARN ").endsWith(
                    "Outbox event " + id + " (AppointmentBooked) skipped: another relay instance owns try 1"));
        }
        assertThat(outbox.state(id)).isEqualTo(changed);
        assertThat(redis.hasKey(key(dealer, LocalDate.parse("2030-04-02")))).isFalse();
    }

    /**
     * AC2.3: the Redis call outlives the lease ({@code retry-delay}). Instance B ticks
     * after the lease ended, but the row lock of TX B still keeps it away.
     */
    @Test
    void slowRedisCallOutlivesTheLeaseWithoutASecondClaim() throws Exception {
        UUID id = outbox.insert("AppointmentBooked", payload(SLOW_DEALER, SLOW_DATE.toString()));

        try (ExecutorService instanceA = Executors.newSingleThreadExecutor()) {
            Future<Integer> a = instanceA.submit(relay::tick);
            assertThat(store.entered.await(10, TimeUnit.SECONDS)).isTrue();

            // Wait until the lease set by the claim is over (PostgreSQL clock)
            Instant leaseEnd = outbox.state(id).nextAttemptAt();
            Duration left = Duration.between(outbox.dbNow(), leaseEnd);
            if (!left.isNegative()) {
                Thread.sleep(left.toMillis() + 50);
            }
            assertThat(outbox.dbNow()).isAfter(leaseEnd);

            // Instance B, while A still holds the row lock in TX B
            assertThat(a.isDone()).isFalse();
            assertThat(relay.tick()).isZero();

            store.release.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        State state = outbox.state(id);
        assertThat(state.processedAt()).isNotNull();
        assertThat(state.failedAt()).isNull();
        assertThat(state.attempts()).isEqualTo(1);
        assertThat(store.slowCalls).isEqualTo(1);
        assertThat(redis.opsForValue().get(key(SLOW_DEALER, SLOW_DATE))).isEqualTo("1");
    }

    /** TX A only: the single due row, with its claimed try. */
    private OutboxEvent claim() {
        OutboxRelayServiceImpl.Claim claim = impl.claimNext().orElseThrow();
        assertThat(claim.exhausted()).isFalse();
        assertThat(claim.event().attempts()).isEqualTo(1);
        return claim.event();
    }

    private static String payload(UUID dealer, String date) {
        return "{\"dealershipId\":\"" + dealer + "\",\"dates\":[\"" + date + "\"]}";
    }

    private static String key(UUID dealer, LocalDate date) {
        return "avail:ver:" + dealer + ":" + date;
    }

    private static void await(CountDownLatch latch, Duration max) {
        try {
            latch.await(max.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Real Redis store; for {@link #SLOW_DEALER} it waits for {@code release} first (10 s cap). */
    static final class SlowStore implements AvailabilityVersionStore {

        private final AvailabilityVersionStore real;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        volatile int slowCalls;

        SlowStore(AvailabilityVersionStore real) {
            this.real = real;
            reset();
        }

        void reset() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            slowCalls = 0;
        }

        @Override
        public void bump(UUID dealershipId, LocalDate date) {
            if (SLOW_DEALER.equals(dealershipId)) {
                slowCalls++;
                entered.countDown();
                await(release, Duration.ofSeconds(10));
            }
            real.bump(dealershipId, date);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SlowStoreConfig {

        @Bean
        @Primary
        SlowStore slowStore(StringRedisTemplate redis, OutboxRelayProperties properties) {
            return new SlowStore(new RedisAvailabilityVersionStore(redis, properties));
        }
    }
}
