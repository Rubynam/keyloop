package com.keyloop.scheduler.batch.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.batch.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/** Turning the relay on registers its @Scheduled method with the configured poll delay. */
@TestPropertySource(properties = "scheduler.outbox.relay.enabled=true")
class OutboxRelayEnabledTest extends IntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void relayStartsWhenEnabled() {
        assertThat(context.getBeanNamesForType(OutboxRelay.class)).hasSize(1);
    }
}
