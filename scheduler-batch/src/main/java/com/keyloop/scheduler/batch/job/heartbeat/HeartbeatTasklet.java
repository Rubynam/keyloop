package com.keyloop.scheduler.batch.job.heartbeat;

import com.keyloop.scheduler.batch.service.heartbeat.HeartbeatService;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * The one step of the heartbeat job. It asks {@link HeartbeatService} to touch
 * PostgreSQL and Redis, and fails the step if either is unreachable. Spec01 BAT-05.
 *
 * <pre>
 * execute()  check ok     → FINISHED
 *            check throws → exception, step FAILED
 * </pre>
 */
@RequiredArgsConstructor
public class HeartbeatTasklet implements Tasklet {

    private final HeartbeatService heartbeatService;

    /** Runs one check; see the class example. */
    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        heartbeatService.check();
        return RepeatStatus.FINISHED;
    }
}
