package com.keyloop.scheduler.batch.service.outbox.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.keyloop.scheduler.batch.service.outbox.OutboxRelayService;
import com.keyloop.scheduler.batch.support.IntegrationTest;
import com.keyloop.scheduler.batch.support.LogCapture;
import com.keyloop.scheduler.batch.support.OutboxRows;
import com.keyloop.scheduler.batch.support.OutboxRows.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Relay on real PostgreSQL + Redis (spec-availability-ops CAP-2, CAP-6; Spec04 §4). Rows
 * come from the {@code docs/contracts/outbox} samples. Ticks are run by the test: the
 * schedule is set to one hour so the background run does not race it.
 */
@TestPropertySource(properties = "scheduler.outbox.relay.poll-delay=1h")
class OutboxRelayIT extends IntegrationTest {

    private static final Duration TWO_DAYS = Duration.ofDays(2);

    @Autowired
    private OutboxRelayService relay;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private OutboxRows outbox;

    @BeforeEach
    void setUp() {
        outbox = new OutboxRows(jdbc);
        // Rows left by earlier tests must not take part in this one's batch
        jdbc.sql("DELETE FROM outbox_events").update();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AppointmentBooked", "AppointmentCancelled", "AppointmentRescheduled",
            "BayBlocked", "BusinessHoursChanged", "TechnicianUnavailable"})
    void sampleBumpsEveryDateOfItsUnionOnce(String eventType) {
        String sample = OutboxRows.sample(eventType);
        OutboxRelayServiceImpl.Payload payload = parse(sample);
        List<Long> before = payload.dates().stream().map(d -> version(payload.dealershipId(), d)).toList();
        UUID id = outbox.insert(eventType, sample);

        assertThat(relay.tick()).isEqualTo(1);

        assertThat(outbox.state(id).processedAt()).isNotNull();
        for (int i = 0; i < payload.dates().size(); i++) {
            String key = key(payload.dealershipId(), payload.dates().get(i));
            assertThat(version(payload.dealershipId(), payload.dates().get(i))).isEqualTo(before.get(i) + 1);
            assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(TWO_DAYS.toSeconds() - 60, TWO_DAYS.toSeconds());
        }
    }

    @Test
    void rescheduledBumpsTheUnionOfDatesOldDatesAndNewDates() {
        assertThat(parse(OutboxRows.sample("AppointmentRescheduled")).dates())
                .containsExactly(LocalDate.parse("2026-11-05"), LocalDate.parse("2026-11-04"));
    }

    @Test
    void cap2BookedEventBumpsTheVersionTheReadPathUses() {
        UUID dealer = UUID.randomUUID();
        UUID id = outbox.insert("AppointmentBooked",
                "{\"appointmentId\":\"" + UUID.randomUUID() + "\",\"dealershipId\":\"" + dealer
                        + "\",\"dates\":[\"2030-01-07\"]}");

        relay.tick();

        assertThat(outbox.state(id).processedAt()).isNotNull();
        // server-api AvailabilityCacheIT reads avail:{dealer}:{date}:v1:… after this bump
        assertThat(redis.opsForValue().get("avail:ver:" + dealer + ":2030-01-07")).isEqualTo("1");
    }

    @Test
    void emptyDateUnionIsProcessedWithoutRedis() {
        UUID dealer = UUID.randomUUID();
        UUID id = outbox.insert("BusinessHoursChanged", "{\"dealershipId\":\"" + dealer + "\",\"dates\":[]}");

        relay.tick();

        State state = outbox.state(id);
        assertThat(state.processedAt()).isNotNull();
        assertThat(state.failedAt()).isNull();
        assertThat(redis.keys("avail:ver:" + dealer + ":*")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"2026-11-05\"]",
            "{\"dates\":[\"2026-11-05\"]}",
            "{\"dealershipId\":\"not-a-uuid\",\"dates\":[]}",
            "{\"dealershipId\":\"0194244f-d800-76ca-b4eb-252b0f21ddb6\",\"dates\":[\"2026-13-40\"]}",
            "{\"dealershipId\":\"0194244f-d800-76ca-b4eb-252b0f21ddb6\",\"dates\":[],\"newDates\":\"2026-11-05\"}"
    })
    void badPayloadIsFailedOnTheFirstTryWithoutRetry(String payload) {
        UUID id = outbox.insert("AppointmentBooked", payload);

        try (LogCapture log = LogCapture.of(OutboxRelayServiceImpl.class)) {
            relay.tick();

            assertThat(log.lines()).anySatisfy(line -> assertThat(line).startsWith(
                    "ERROR [" + id + "] Outbox event " + id + " (AppointmentBooked) has an invalid payload"
                            + " and was marked FAILED without retry: "));
        }
        State state = outbox.state(id);
        assertThat(state.attempts()).isEqualTo(1);
        assertThat(state.failedAt()).isNotNull();
        assertThat(state.processedAt()).isNull();
        assertThat(state.lastError()).isNotBlank();
    }

    @Test
    void rowNotDueYetIsLeftAlone() {
        UUID id = outbox.insert("AppointmentBooked", OutboxRows.sample("AppointmentBooked"));
        jdbc.sql("UPDATE outbox_events SET next_attempt_at = now() + interval '1 minute' WHERE id = :id")
                .param("id", id).update();

        assertThat(relay.tick()).isZero();
        assertThat(outbox.state(id).processedAt()).isNull();
    }

    @Test
    void rowLockedByAnotherInstanceIsSkipped() throws Exception {
        UUID locked = outbox.insert("AppointmentBooked", payload(UUID.randomUUID(), "2030-01-07"));
        UUID free = outbox.insert("AppointmentBooked", payload(UUID.randomUUID(), "2030-01-07"));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        try (ExecutorService other = Executors.newSingleThreadExecutor()) {
            // "Another relay instance" holding the row lock in its own open transaction
            Future<?> instance = other.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                jdbc.sql("SELECT id FROM outbox_events WHERE id = :id FOR UPDATE").param("id", locked)
                        .query(UUID.class).single();
                holding.countDown();
                await(done);
            }));
            holding.await(10, TimeUnit.SECONDS);

            assertThat(relay.tick()).isEqualTo(1);
            assertThat(outbox.state(free).processedAt()).isNotNull();
            assertThat(outbox.state(locked).processedAt()).isNull();

            done.countDown();
            instance.get(10, TimeUnit.SECONDS);
        }
        assertThat(relay.tick()).isEqualTo(1);
        assertThat(outbox.state(locked).processedAt()).isNotNull();
    }

    @Test
    void twoInstancesNeverProcessTheSameRow() throws Exception {
        UUID dealer = UUID.randomUUID();
        LocalDate first = LocalDate.of(2030, 2, 1);
        int rows = 40;
        for (int i = 0; i < rows; i++) {
            outbox.insert("AppointmentBooked", payload(dealer, first.plusDays(i).toString()));
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> instances = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                instances.add(pool.submit(() -> {
                    start.await();
                    int handled = 0;
                    for (int n; (n = relay.tick()) > 0; ) {
                        handled += n;
                    }
                    return handled;
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> instance : instances) {
                total += instance.get(30, TimeUnit.SECONDS);
            }
            assertThat(total).isEqualTo(rows);
        }
        // Each row bumped its own date exactly once
        for (int i = 0; i < rows; i++) {
            assertThat(version(dealer, first.plusDays(i))).isEqualTo(1);
        }
    }

    /** {@code parse} is package-private on the implementation, not part of the service interface. */
    private OutboxRelayServiceImpl.Payload parse(String raw) {
        OutboxRelayServiceImpl impl = AopTestUtils.getTargetObject(relay);
        return impl.parse(raw);
    }

    private static String payload(UUID dealer, String date) {
        return "{\"dealershipId\":\"" + dealer + "\",\"dates\":[\"" + date + "\"]}";
    }

    private long version(UUID dealer, LocalDate date) {
        String value = redis.opsForValue().get(key(dealer, date));
        return value == null ? 0 : Long.parseLong(value);
    }

    private static String key(UUID dealer, LocalDate date) {
        return "avail:ver:" + dealer + ":" + date;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
