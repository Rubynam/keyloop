package com.keyloop.scheduler.api.service.availability;

import java.time.LocalDate;
import java.util.UUID;

import com.keyloop.scheduler.api.dto.availability.AvailabilityResponse;
import com.keyloop.scheduler.api.exception.NotFoundException;
import com.keyloop.scheduler.api.exception.availability.InvalidRangeException;

/**
 * Finds free booking slots for one dealership, service type and vehicle type.
 * Customers call it before they book, to pick a time. It only reads data and
 * never holds a slot. DLD §8, spec-read-availability.
 *
 * <pre>
 * find(D1, OIL_CHANGE, SUV, 2026-11-02, 2026-11-02)
 *
 *   bays  B1 ██░░░░  B2 ░░░░██        █ busy  ░ free
 *   techs T1 ░░██░░
 *         09 10 11 12 13 14
 *   slots → 09:00, 11:00, 12:00   (60 min, one bay AND one tech free)
 * </pre>
 */
public interface AvailabilityService {

    /**
     * Free slots of every local date in {@code [from, to]}. Every date is listed, even
     * without slots. Slots that already started are left out.
     *
     * <pre>
     * (D1, OIL, SUV, 11-02, 11-03) → days [ {11-02, [09:00-10:00, 11:00-12:00]}, {11-03, []} ]
     * </pre>
     *
     * @throws NotFoundException     unknown dealership, service type or vehicle type
     * @throws InvalidRangeException {@code to < from}, more than 14 days, or a year outside 1..9999
     */
    AvailabilityResponse find(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId,
                              LocalDate from, LocalDate to);
}
