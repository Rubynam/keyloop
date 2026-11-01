package com.keyloop.scheduler.api.service.booking;

import java.time.Instant;
import java.util.UUID;

import com.keyloop.scheduler.api.exception.NotFoundException;
import com.keyloop.scheduler.api.model.booking.CandidateResult;

/**
 * Picks the (bay, technician) pairs a booking should try for one time window. The
 * booking runs one try per pair, so a good order means fewer conflicts. It reads
 * PostgreSQL only, never the cache, and the same database state gives the same pairs.
 * candidate-selection.md, DLD §5, Spec04 §6.
 *
 * <pre>
 * Free bays (ordered) | Free techs (ordered) | Pairs
 * B1, B2, B3          | T1, T2, T3, T4       | (B1,T1) (B2,T2) (B3,T3)
 * B1                  | T1, T2, T3           | (B1,T1) (B1,T2) (B1,T3)
 * B1, B2              | T1                   | (B1,T1) (B2,T1)
 * B1, B2              | T1, T2               | (B1,T1) (B2,T2) (B1,T2)
 * </pre>
 */
public interface CandidateSelector {

    /**
     * Ordered pairs for the window {@code [start, start + service duration)}, or why
     * there are none.
     *
     * <pre>
     * no fitting bay or tech      → NOT_SERVICEABLE  (booking answers 422)
     * all busy in the window      → NONE_FREE        (booking answers 409)
     * otherwise                   → PAIRS [(B1,T1), (B2,T2), ...]
     * </pre>
     *
     * @param vehicleTypeId from the vehicle, never from the client
     * @param start         window start; the end is {@code start + service duration}
     * @throws NotFoundException unknown dealership or service type
     */
    CandidateResult select(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId, Instant start);
}
