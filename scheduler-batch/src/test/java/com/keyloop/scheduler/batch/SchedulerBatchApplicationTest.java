package com.keyloop.scheduler.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import com.keyloop.scheduler.batch.config.outbox.OutboxRelayProperties;
import com.keyloop.scheduler.batch.schedule.OutboxRelay;
import com.keyloop.scheduler.batch.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class SchedulerBatchApplicationTest extends IntegrationTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private JobOperator jobOperator;
    @Autowired
    @Qualifier("heartbeatJob")
    private Job heartbeatJob;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private OutboxRelayProperties relayProperties;

    @Test
    void heartbeatJobCompletesAgainstPostgresAndRedis() throws Exception {
        var params = new JobParametersBuilder()
                .addString("run.at", Instant.now().toString())
                .toJobParameters();

        assertThat(jobOperator.start(heartbeatJob, params).getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void relayIsOnByDefaultWithTheDecidedRetryPolicy() {
        assertThat(context.getBeanNamesForType(OutboxRelay.class)).hasSize(1);
        assertThat(relayProperties.maxRetries()).isEqualTo(3);
        assertThat(relayProperties.retryDelay()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void idleTransactionsAreCutOff() {
        assertThat(jdbcTemplate.queryForObject("SHOW idle_in_transaction_session_timeout", String.class))
                .isEqualTo("30s");
    }
}
