package com.keyloop.scheduler.batch.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Fixed values of the scheduled entry points: ShedLock durations, the job parameter that
 * makes each run unique, and the property keys that switch a schedule on. Values that
 * change per environment (cron, poll delay) stay in {@code application.yml}. Spec05 §6.
 *
 * <pre>
 * JobScheduler: SchedulerLock(name = HEARTBEAT_JOB, lockAtMostFor = PT25S, lockAtLeastFor = PT10S)
 * JobParameters { RUN_AT_PARAM = 2026-11-01T10:00:00Z }   → a new JobInstance per tick
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SchedulingConstants {

    public static final String RUN_AT_PARAM = "run.at";

    public static final String HEARTBEAT_LOCK_AT_MOST_FOR = "PT25S";
    public static final String HEARTBEAT_LOCK_AT_LEAST_FOR = "PT10S";
    public static final String DEFAULT_LOCK_AT_MOST_FOR = "PT5M";

    /** {@code havingValue} of every on/off switch. */
    public static final String ENABLED = "true";
    public static final String HEARTBEAT_ENABLED_PROPERTY = "scheduler.jobs.heartbeat.enabled";
    public static final String HEARTBEAT_CRON = "${scheduler.jobs.heartbeat.cron}";
    public static final String OUTBOX_CLEANUP_ENABLED_PROPERTY = "scheduler.outbox.cleanup.enabled";
}
