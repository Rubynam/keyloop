package com.keyloop.scheduler.api.service.appointment;

import java.time.Instant;
import java.util.UUID;

import com.keyloop.scheduler.api.exception.NotFoundException;
import com.keyloop.scheduler.api.model.appointment.CandidateResult;

/**
 * Picks the technicians a booking should try in the bay the client chose. The booking
 * runs one try per (bay, technician) pair, so a good order means fewer conflicts. It
 * reads PostgreSQL only, never the cache, and the same database state gives the same
 * pairs. candidate-selection.md, DLD §5, Spec04 §6.
 *
 * <pre>
 * Chosen bay | Free techs (fewest jobs that day first) | Pairs
 * B1         | T2 (0 jobs), T1 (2 jobs), T3 (2 jobs)   | (B1,T2) (B1,T1) (B1,T3)
 * B1 busy    | T1                                      | none → NONE_FREE
 * </pre>
 */
public interface CandidateSelector {

    /**
     * Ordered pairs for the window {@code [start, start + service duration)} in bay
     * {@code serviceBayId}, or why there are none.
     *
     * <pre>
     * bay inactive / does not take the type,
     * or no qualified tech                     → NOT_SERVICEABLE  (booking answers 422)
     * bay busy, or every qualified tech busy   → NONE_FREE        (booking answers 409)
     * otherwise                                → PAIRS [(B1,T2), (B1,T1), ...]
     * </pre>
     *
     * @param vehicleTypeId from the vehicle, never from the client
     * @param serviceBayId  the bay the client chose
     * @param start         window start; the end is {@code start + service duration}
     * @throws NotFoundException unknown dealership or service type, or a bay that is
     *                           unknown or belongs to another dealership
     */
    CandidateResult select(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId, UUID serviceBayId,
                           Instant start);
}
