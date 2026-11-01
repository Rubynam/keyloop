package com.keyloop.scheduler.batch.job.heartbeat;

import com.keyloop.scheduler.batch.constant.JobNames;
import com.keyloop.scheduler.batch.service.heartbeat.HeartbeatService;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Sample job proving the Batch + Postgres + Redis wiring (Spec01 BAT-05). */
@Configuration
public class HeartbeatJobConfig {

    @Bean(JobNames.HEARTBEAT_JOB)
    public Job heartbeatJob(JobRepository jobRepository, Step heartbeatStep) {
        return new JobBuilder(JobNames.HEARTBEAT_JOB, jobRepository)
                .start(heartbeatStep)
                .build();
    }

    @Bean(JobNames.HEARTBEAT_STEP)
    public Step heartbeatStep(JobRepository jobRepository,
                              PlatformTransactionManager transactionManager,
                              HeartbeatService heartbeatService) {
        return new StepBuilder(JobNames.HEARTBEAT_STEP, jobRepository)
                .tasklet(new HeartbeatTasklet(heartbeatService), transactionManager)
                .build();
    }
}
