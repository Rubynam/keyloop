package com.keyloop.scheduler.batch.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Names of Spring Batch jobs and steps. The names are stored in the Batch metadata
 * tables and in ShedLock rows, so a rename would split the run history.
 *
 * <pre>
 * JobScheduler ──@SchedulerLock(HEARTBEAT_JOB)──► job HEARTBEAT_JOB ──► step HEARTBEAT_STEP
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JobNames {

    public static final String HEARTBEAT_JOB = "heartbeatJob";
    public static final String HEARTBEAT_STEP = "heartbeatStep";
    public static final String OUTBOX_CLEANUP_JOB = "outboxCleanupJob";
}
