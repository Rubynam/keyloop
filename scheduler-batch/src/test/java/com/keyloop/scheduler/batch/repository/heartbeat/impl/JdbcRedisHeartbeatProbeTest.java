package com.keyloop.scheduler.batch.repository.heartbeat.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** The probe is what the heartbeat tasklet used to do inline: {@code SELECT 1} and {@code PING}. */
class JdbcRedisHeartbeatProbeTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final JdbcRedisHeartbeatProbe probe = new JdbcRedisHeartbeatProbe(jdbcTemplate, redisTemplate);

    @Test
    @SuppressWarnings("unchecked")
    void answersWhenPostgresAndRedisAnswer() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");

        assertThat(probe.pingPostgres()).isEqualTo(1);
        assertThat(probe.pingRedis()).isEqualTo("PONG");
    }

    @Test
    void failsWhenPostgresIsDown() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(probe::pingPostgres)
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
