package com.keyloop.scheduler.batch.schedule;

import java.time.Instant;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Launches Batch jobs on a schedule; jobs never auto-run at startup (Spec01 BAT-04). */
@Component
@ConditionalOnProperty(name = "scheduler.jobs.heartbeat.enabled", havingValue = "true", matchIfMissing = true)
public class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JobOperator jobOperator;
    private final Job heartbeatJob;

    public JobScheduler(JobOperator jobOperator, @Qualifier("heartbeatJob") Job heartbeatJob) {
        this.jobOperator = jobOperator;
        this.heartbeatJob = heartbeatJob;
    }

    // lockAtLeastFor keeps the lock after a fast run, so another instance
    // firing the same tick a few ms later still skips it
    @Scheduled(cron = "${scheduler.jobs.heartbeat.cron}")
    @SchedulerLock(name = "heartbeatJob", lockAtMostFor = "PT25S", lockAtLeastFor = "PT10S")
    public void runHeartbeat() {
        launch(heartbeatJob);
    }

    private void launch(Job job) {
        // A unique parameter per run → a new JobInstance every tick
        JobParameters params = new JobParametersBuilder()
                .addString("run.at", Instant.now().toString())
                .toJobParameters();
        try {
            var execution = jobOperator.start(job, params);
            log.info("{} finished with status {}", job.getName(), execution.getStatus());
        } catch (Exception e) {
            log.error("{} failed to launch", job.getName(), e);
        }
    }
}
