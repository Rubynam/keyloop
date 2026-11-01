package com.keyloop.scheduler.batch.service.heartbeat.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.batch.repository.heartbeat.HeartbeatProbe;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class HeartbeatServiceImplTest {

    private final HeartbeatProbe probe = mock(HeartbeatProbe.class);
    private final HeartbeatServiceImpl service = new HeartbeatServiceImpl(probe);

    @Test
    void postgresDownFailsTheCheckWithoutTryingRedis() {
        when(probe.pingPostgres()).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(service::check).isInstanceOf(DataAccessResourceFailureException.class);
        verify(probe, never()).pingRedis();
    }

    @Test
    void redisDownFailsTheCheck() {
        when(probe.pingPostgres()).thenReturn(1);
        when(probe.pingRedis()).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(service::check).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
