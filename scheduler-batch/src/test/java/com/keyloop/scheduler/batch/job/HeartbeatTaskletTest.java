package com.keyloop.scheduler.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class HeartbeatTaskletTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final HeartbeatTasklet tasklet = new HeartbeatTasklet(jdbcTemplate, redisTemplate);

    @Test
    @SuppressWarnings("unchecked")
    void finishesWhenPostgresAndRedisAnswer() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");

        assertThat(tasklet.execute(null, null)).isEqualTo(RepeatStatus.FINISHED);
    }

    @Test
    void failsWhenPostgresIsDown() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> tasklet.execute(null, null))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
