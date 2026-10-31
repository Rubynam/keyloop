package com.keyloop.scheduler.batch.job;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Sample job proving the Batch + Postgres + Redis wiring (Spec01 BAT-05). */
@Configuration
public class HeartbeatJobConfig {

    public static final String JOB_NAME = "heartbeatJob";

    @Bean
    public Job heartbeatJob(JobRepository jobRepository, Step heartbeatStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(heartbeatStep)
                .build();
    }

    @Bean
    public Step heartbeatStep(JobRepository jobRepository,
                              PlatformTransactionManager transactionManager,
                              JdbcTemplate jdbcTemplate,
                              StringRedisTemplate redisTemplate) {
        return new StepBuilder("heartbeatStep", jobRepository)
                .tasklet(new HeartbeatTasklet(jdbcTemplate, redisTemplate), transactionManager)
                .build();
    }
}
