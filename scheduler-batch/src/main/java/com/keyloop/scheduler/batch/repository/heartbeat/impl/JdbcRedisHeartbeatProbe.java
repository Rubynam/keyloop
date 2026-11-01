package com.keyloop.scheduler.batch.repository.heartbeat.impl;

import com.keyloop.scheduler.batch.repository.heartbeat.HeartbeatProbe;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@link HeartbeatProbe} with {@link JdbcTemplate} ({@code SELECT 1}) and {@link StringRedisTemplate} ({@code PING}). */
@Repository
public class JdbcRedisHeartbeatProbe implements HeartbeatProbe {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    public JdbcRedisHeartbeatProbe(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Integer pingPostgres() {
        return jdbcTemplate.queryForObject("SELECT 1", Integer.class);
    }

    @Override
    public String pingRedis() {
        return redisTemplate.execute((RedisCallback<String>) RedisConnection::ping);
    }
}
