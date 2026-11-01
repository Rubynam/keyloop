package com.keyloop.scheduler.batch.service.outbox;

/**
 * Moves due outbox events to Redis: for each event it bumps the availability version
 * of every affected dealership day, so the API cache drops stale slots. One call is one
 * batch in one transaction. It never changes a booking. Spec04 §4, ROADMAP D1.
 *
 * <pre>
 * tick()
 *   E1 {D1, dates [11-05]}       INCR avail:ver:D1:11-05   → PROCESSED
 *   E2 Redis down, attempts 0    → PENDING, retry in 2 s
 *   E3 {dates: []} no dealership → FAILED at once (bad payload)
 *   returns 3
 * </pre>
 */
public interface OutboxRelayService {

    /**
     * Handles one batch of due events in one transaction (Spec04 §4.2). A result equal
     * to the batch size means more rows are probably due.
     *
     * <pre>
     * 0 due → 0;   250 due, batch-size 100 → 100
     * </pre>
     *
     * @return the number of rows taken
     */
    int tick();
}
