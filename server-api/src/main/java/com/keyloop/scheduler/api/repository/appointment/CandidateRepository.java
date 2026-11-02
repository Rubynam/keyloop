package com.keyloop.scheduler.api.repository.appointment;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.keyloop.scheduler.api.model.appointment.BookableBay;

/**
 * Facts for candidate selection: can the chosen bay take this booking, and how busy is
 * each technician. Technician fit and overlap checks reuse {@code AvailabilityRepository}.
 * candidate-selection.md.
 *
 * <pre>
 * findBookableBay(D1, B1, SUV)                   → BookableBay(B1, active, takes SUV)
 * countAppointments([T1, T2], 11-02 00:00, +1d)  → {T2=4}         T1 (0 jobs) first
 * </pre>
 */
public interface CandidateRepository {

    /**
     * The bay, only when it belongs to the dealership. It says if the bay is active and
     * if it takes the vehicle type; empty when the id is unknown or of another dealership.
     *
     * <pre>
     * (D1, B1, SUV) → BookableBay(B1, true, true)
     * (D1, B9, SUV) → empty      B9 belongs to D2
     * </pre>
     */
    Optional<BookableBay> findBookableBay(UUID dealershipId, UUID serviceBayId, UUID vehicleTypeId);

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
