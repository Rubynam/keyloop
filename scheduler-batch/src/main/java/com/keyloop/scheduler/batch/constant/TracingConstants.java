package com.keyloop.scheduler.batch.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Framework observation and tag names that the tracing noise filters match. Only the
 * filters in {@code config/tracing} use them. Business span names do not go here
 * (spine AD-7). If a Boot upgrade renames one, the tracing ITs fail first.
 *
 * <pre>
 * tasks.scheduled.execution + target OutboxRelay  → dropped at start
 * http.server.requests  + path /actuator/health   → dropped at start
 * root span + tag jdbc.datasource.name=dataSource → dropped at export
 * root span + tag db.system=redis                 → dropped at export
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TracingConstants {

    /** Observation of one HTTP request (Spring MVC). */
    public static final String HTTP_SERVER_REQUESTS = "http.server.requests";
    /** Separator after the actuator base path: "/actuator" and "/actuator/health" match, "/actuatorx" does not. */
    public static final String PATH_SEPARATOR = "/";
    /** Observation of one {@code @Scheduled} run (Spring Framework). */
    public static final String SCHEDULED_TASK_EXECUTION = "tasks.scheduled.execution";
    /**
     * Target class of the relay tick. A name, not {@code OutboxRelay.class}: config must not
     * depend on the schedule layer (ARC-01). A rename breaks the batch TracingIT (AC5).
     */
    public static final String OUTBOX_RELAY_CLASS = "com.keyloop.scheduler.batch.schedule.OutboxRelay";

    /** Every datasource-micrometer span has tags with this prefix; its span name is only "query". */
    public static final String JDBC_TAG_PREFIX = "jdbc.";
    /** Lettuce names its span after the command ("get", "evalsha"), so it is found by this tag. */
    public static final String DB_SYSTEM_TAG = "db.system";
    public static final String DB_SYSTEM_REDIS = "redis";
}
