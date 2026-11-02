package com.keyloop.scheduler.batch.service.outbox.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.keyloop.scheduler.batch.service.outbox.OutboxRelayService;
import com.keyloop.scheduler.batch.service.outbox.impl.OutboxRelayCrashIT.CrashingStore;
import com.keyloop.scheduler.batch.service.outbox.impl.OutboxRelayCrashIT.SimulatedCrash;
import com.keyloop.scheduler.batch.support.IntegrationTest;
import com.keyloop.scheduler.batch.support.LogCapture;
import com.keyloop.scheduler.batch.support.OutboxRows;
import com.keyloop.scheduler.batch.support.OutboxRows.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * Story outbox-relay Task 3, AC3.2 crash path: the claim follows {@code max-retries} and
 * {@code retry-delay} from config, not the shipped 3 / 2 s. With {@code max-retries=1} a
 * row that always crashes gets 2 tries, its lease is 500 ms, and the next claim ends it.
 */
@TestPropertySource(properties = {
        "scheduler.outbox.relay.poll-delay=1h",
        "scheduler.outbox.relay.max-retries=1",
        "scheduler.outbox.relay.retry-delay=500ms"
})
@Import(OutboxRelayCrashIT.CrashingStoreConfig.class)
class OutboxRelayRetrySettingsCrashIT extends IntegrationTest {

    private static final Duration RETRY_DELAY = Duration.ofMillis(500);
    private static final int MAX_TRIES = 2;

    @Autowired
    private OutboxRelayService relay;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private CrashingStore store;

    private OutboxRows outbox;

    @BeforeEach
    void setUp() {
        outbox = new OutboxRows(jdbc);
        jdbc.sql("DELETE FROM outbox_events").update();
        store.crashes.set(0);
    }

    @Test
    void crashingRowGetsTwoTriesThenIsFailedAtTheClaim() {
        UUID id = outbox.insert("AppointmentBooked",
                "{\"dealershipId\":\"" + OutboxRelayCrashIT.CRASH_DEALER + "\",\"dates\":[\"2030-04-01\"]}");

        for (int attempt = 1; attempt <= MAX_TRIES; attempt++) {
            Instant before = outbox.dbNow();
            assertThatThrownBy(relay::tick).isInstanceOf(SimulatedCrash.class);
            Instant after = outbox.dbNow();

            State state = outbox.state(id);
            assertThat(state.attempts()).isEqualTo(attempt);
            assertThat(state.processedAt()).isNull();
            assertThat(state.failedAt()).isNull();
            // The claim lease is the configured retry delay
            assertThat(state.nextAttemptAt()).isBetween(before.plus(RETRY_DELAY), after.plus(RETRY_DELAY));
            outbox.makeDue(id);
        }

        try (LogCapture log = LogCapture.of(OutboxRelayServiceImpl.class)) {
            assertThat(relay.tick()).isEqualTo(1);

            assertThat(log.lines()).anySatisfy(line -> assertThat(line).startsWith(
                    "ERROR [" + id + "] Outbox event " + id + " (AppointmentBooked) was marked FAILED after 2"
                            + " attempts that did not finish"));
        }
        State state = outbox.state(id);
        assertThat(state.attempts()).isEqualTo(MAX_TRIES);
        assertThat(state.failedAt()).isNotNull();
        assertThat(state.processedAt()).isNull();
        assertThat(state.lastError()).isEqualTo("CLAIM_EXHAUSTED: tries started but never finished");
        // No 3rd try ran
        assertThat(store.crashes).hasValue(MAX_TRIES);

        outbox.makeDue(id);
        assertThat(relay.tick()).isZero();
        assertThat(store.crashes).hasValue(MAX_TRIES);
    }
}
