package com.keyloop.scheduler.batch.job.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.keyloop.scheduler.batch.service.heartbeat.HeartbeatService;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.dao.DataAccessResourceFailureException;

class HeartbeatTaskletTest {

    private final HeartbeatService heartbeatService = mock(HeartbeatService.class);
    private final HeartbeatTasklet tasklet = new HeartbeatTasklet(heartbeatService);

    @Test
    void finishesWhenPostgresAndRedisAnswer() {
        assertThat(tasklet.execute(null, null)).isEqualTo(RepeatStatus.FINISHED);
        verify(heartbeatService).check();
    }

    @Test
    void failsWhenPostgresIsDown() {
        doThrow(new DataAccessResourceFailureException("down")).when(heartbeatService).check();

        assertThatThrownBy(() -> tasklet.execute(null, null))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
