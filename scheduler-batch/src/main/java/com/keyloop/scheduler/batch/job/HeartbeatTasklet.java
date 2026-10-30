package com.keyloop.scheduler.batch.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** Touches Postgres and Redis once, failing the step if either is unreachable. */
public class HeartbeatTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatTasklet.class);

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    public HeartbeatTasklet(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Integer db = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        String redis = redisTemplate.execute((RedisCallback<String>) RedisConnection::ping);
        log.info("heartbeat ok: postgres={} redis={}", db, redis);
        return RepeatStatus.FINISHED;
    }
}
