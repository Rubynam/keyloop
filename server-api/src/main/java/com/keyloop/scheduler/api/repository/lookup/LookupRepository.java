package com.keyloop.scheduler.api.repository.lookup;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.keyloop.scheduler.api.model.lookup.NamedResource;
import org.jspecify.annotations.Nullable;

/**
 * Read-only data access for the availability lookup. It finds the bays and the qualified
 * technicians of one dealership that no live appointment holds inside a window. A
 * {@code null} filter means "any". It never writes.
 *
 * <pre>
 * window 09:00 ─────── 10:00                     dealership D1
 *   B1 ░░░░░░░░░░░░░   free     → in findFreeBays
 *   B2 ░░░░███████░░   booked   → left out
 *   T1 ░░░░░░░░░░░░░   free, has OIL skill → in findFreeTechnicians(.., OIL, ..)
 * </pre>
 */
public interface LookupRepository {

    /**
     * True when the bay exists and belongs to the dealership.
     *
     * <pre>
     * (B1, D1) → true     (B1, D2) → false     (unknown, D1) → false
     * </pre>
     */
    boolean bayBelongsTo(UUID serviceBayId, UUID dealershipId);

    /**
     * True when the technician exists and works at the dealership.
     *
     * <pre>
     * (T1, D1) → true     (T1, D2) → false
     * </pre>
     */
    boolean technicianBelongsTo(UUID technicianId, UUID dealershipId);

    /**
     * Active bays of the dealership that take the vehicle type and have no non-cancelled
     * appointment overlapping {@code [from, to)} (half-open), sorted by name.
     *
     * <pre>
     * (D1, null, SUV, 09:00, 10:00) → [Bay 1, Bay 3]   Bay 2 booked 09:30, Bay 4 car-only
     * (D1, B2,   null, 10:00, 11:00) → [Bay 2]          its booking ends at 10:00
     * </pre>
     */
    List<NamedResource> findFreeBays(UUID dealershipId, @Nullable UUID serviceBayId, @Nullable UUID vehicleTypeId,
                                     Instant from, Instant to);

    /**
     * Active technicians of the dealership with the skill and the vehicle-type
     * certification and no non-cancelled appointment overlapping {@code [from, to)},
     * sorted by name.
     *
     * <pre>
     * (D1, null, OIL, SUV, 09:00, 10:00) → [An]   Binh has no SUV certificate
     * </pre>
     */
    List<NamedResource> findFreeTechnicians(UUID dealershipId, @Nullable UUID technicianId,
                                            @Nullable UUID serviceTypeId, @Nullable UUID vehicleTypeId,
                                            Instant from, Instant to);
}
