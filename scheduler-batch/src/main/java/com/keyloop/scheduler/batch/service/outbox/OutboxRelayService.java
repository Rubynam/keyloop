package com.keyloop.scheduler.batch.service.outbox;

/**
 * Moves due outbox events to Redis: for each event it bumps the availability version
 * of every affected dealership day, so the API cache drops stale slots. It takes one
 * row at a time: one transaction counts the try, a second one does the work. It never
 * changes a booking. Spec04 §4, ROADMAP D1.
 *
 * <pre>
 * tick()
 *   E1 {D1, dates [11-05]}       INCR avail:ver:D1:11-05   → PROCESSED, attempts 1
 *   E2 {dates: []} no dealership → FAILED at once (bad payload)
 *   E3 Redis down, attempts 0→1  → PENDING, retry in 2 s; tick stops here
 *   returns 3
 * </pre>
 */
public interface OutboxRelayService {

    /**
     * Claims and works up to {@code batch-size} due rows, one by one (Spec04 §4.2). Rows
     * ended as exhausted at the claim count too. A result equal to the batch size means
     * more rows are probably due.
     *
     * <pre>
     * 0 due → 0;   250 due, batch-size 100 → 100;   Redis error on row 7 → 7
     * </pre>
     *
     * @return the number of rows claimed
     */
    int tick();
}
