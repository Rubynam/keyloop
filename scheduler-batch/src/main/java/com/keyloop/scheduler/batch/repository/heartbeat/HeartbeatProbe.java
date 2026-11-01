package com.keyloop.scheduler.batch.repository.heartbeat;

/**
 * Touches PostgreSQL and Redis once each, to prove the worker can reach both. The
 * heartbeat job uses it, so a broken connection fails a step that operators can see.
 * Spec01 BAT-05.
 *
 * <pre>
 * pingPostgres()  → 1          (SELECT 1)
 * pingRedis()     → PONG       (PING)
 * Postgres down   → DataAccessException
 * </pre>
 */
public interface HeartbeatProbe {

    /**
     * Runs a trivial query.
     *
     * <pre>
     * up → 1;  down → DataAccessException
     * </pre>
     */
    Integer pingPostgres();

    /**
     * Sends {@code PING}.
     *
     * <pre>
     * up → PONG;  down → DataAccessException
     * </pre>
     */
    String pingRedis();
}
