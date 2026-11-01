package com.keyloop.scheduler.batch.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.batch.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/** The relay is on by default (spec-availability-ops); the switch still turns it off. */
@TestPropertySource(properties = "scheduler.outbox.relay.enabled=false")
class OutboxRelayDisabledTest extends IntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void relayDoesNotLoadWhenDisabled() {
        assertThat(context.getBeanNamesForType(OutboxRelay.class)).isEmpty();
    }
}
