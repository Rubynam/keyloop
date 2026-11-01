package com.keyloop.scheduler.batch.service.heartbeat;

/**
 * Checks that the worker can reach PostgreSQL and Redis, and logs the answer. The
 * heartbeat job calls it on a schedule, so a broken connection shows up as a failed
 * Batch step. Spec01 BAT-05.
 *
 * <pre>
 * check()  both up     → log heartbeat ok: postgres=1 redis=PONG
 *          either down → throws, the step FAILS
 * </pre>
 */
public interface HeartbeatService {

    /**
     * Touches PostgreSQL, then Redis, once each.
     *
     * <pre>
     * up/up → returns;  Postgres down → DataAccessException (Redis not tried)
     * </pre>
     */
    void check();
}
