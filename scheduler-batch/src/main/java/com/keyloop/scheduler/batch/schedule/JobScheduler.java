package com.keyloop.scheduler.batch.schedule;

import java.time.Instant;

import com.keyloop.scheduler.batch.constant.JobNames;
import com.keyloop.scheduler.batch.constant.SchedulingConstants;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Launches Batch jobs on a schedule; jobs never auto-run at startup (Spec01 BAT-04).
 * ShedLock makes one instance run each tick when scheduler-batch is scaled out.
 *
 * <pre>
 * cron 0 *&#47;5 * * * *   instance A: lock ok → start heartbeatJob(run.at=10:05:00Z) → COMPLETED
 *                   instance B: lock taken → skip
 * </pre>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = SchedulingConstants.HEARTBEAT_ENABLED_PROPERTY,
        havingValue = SchedulingConstants.ENABLED, matchIfMissing = true)
public class JobScheduler {

    private final JobOperator jobOperator;
    private final Job heartbeatJob;

    public JobScheduler(JobOperator jobOperator, @Qualifier(JobNames.HEARTBEAT_JOB) Job heartbeatJob) {
        this.jobOperator = jobOperator;
        this.heartbeatJob = heartbeatJob;
    }

    /**
     * Starts the heartbeat job once per cron tick, on one instance only.
     *
     * <pre>
     * 10:05:00.000 A runs (lock kept at least 10 s)   10:05:00.004 B skips
     * </pre>
     */
    // lockAtLeastFor keeps the lock after a fast run, so another instance
    // firing the same tick a few ms later still skips it
    @Scheduled(cron = SchedulingConstants.HEARTBEAT_CRON)
    @SchedulerLock(name = JobNames.HEARTBEAT_JOB, lockAtMostFor = SchedulingConstants.HEARTBEAT_LOCK_AT_MOST_FOR,
            lockAtLeastFor = SchedulingConstants.HEARTBEAT_LOCK_AT_LEAST_FOR)
    public void runHeartbeat() {
        launch(heartbeatJob);
    }

    private void launch(Job job) {
        // A unique parameter per run → a new JobInstance every tick
        JobParameters params = new JobParametersBuilder()
                .addString(SchedulingConstants.RUN_AT_PARAM, Instant.now().toString())
                .toJobParameters();
        try {
            var execution = jobOperator.start(job, params);
            log.info("{} finished with status {}", job.getName(), execution.getStatus());
        } catch (Exception e) {
            log.error("{} failed to launch", job.getName(), e);
        }
    }
}
