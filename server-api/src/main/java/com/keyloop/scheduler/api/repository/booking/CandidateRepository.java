package com.keyloop.scheduler.api.repository.booking;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Ordering facts for candidate selection: how flexible each bay is and how busy each
 * technician is. Fit and overlap checks reuse {@code AvailabilityRepository}.
 * candidate-selection.md steps 3–4.
 *
 * <pre>
 * countVehicleTypes([B1, B2])                    → {B1=1, B2=3}   B1 first
 * countAppointments([T1, T2], 11-02 00:00, +1d)  → {T2=4}         T1 (0 jobs) first
 * </pre>
 */
public interface CandidateRepository {

    /**
     * Number of vehicle types each bay takes. Every id must be a bay.
     *
     * <pre>
     * [B1, B2] → {B1=1, B2=3}
     * </pre>
     */
    Map<UUID, Integer> countVehicleTypes(Collection<UUID> bayIds);

    /**
     * Non-cancelled appointments per technician that start in {@code [from, to)}.
     * A technician without appointments is absent from the map (count 0).
     *
     * <pre>
     * ([T1, T2], 11-02 00:00, 11-03 00:00) → {T2=4}
     * </pre>
     */
    Map<UUID, Integer> countAppointments(Collection<UUID> technicianIds, Instant from, Instant to);
}
