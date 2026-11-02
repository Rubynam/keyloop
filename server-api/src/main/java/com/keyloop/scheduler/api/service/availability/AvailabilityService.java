package com.keyloop.scheduler.api.service.availability;

import java.time.LocalDate;
import java.util.UUID;

import com.keyloop.scheduler.api.dto.availability.AvailabilityResponse;
import com.keyloop.scheduler.api.exception.NotFoundException;
import com.keyloop.scheduler.api.exception.availability.InvalidRangeException;

/**
 * Finds free booking slots for one bay of a dealership, for one service type and
 * vehicle type. Customers call it before they book, to pick a time on the bay they
 * chose. It only reads data and never holds a slot. DLD §8, spec-read-availability.
 *
 * <pre>
 * find(D1, B1, OIL_CHANGE, SUV, 2026-11-02, 2026-11-02)
 *
 *   bay   B1 ██░░░░                   █ busy  ░ free
 *   techs T1 ░░██░░   (also busy on other bays)
 *         09 10 11 12 13 14
 *   slots → 11:00, 12:00, 13:00, 14:00   (60 min, B1 AND one tech free)
 * </pre>
 */
public interface AvailabilityService {

    /**
     * Free slots of every local date in {@code [from, to]}. Every date is listed, even
     * without slots. Slots that already started are left out.
     *
     * <pre>
     * (D1, B1, OIL, SUV, 11-02, 11-03) → days [ {11-02, [09:00-10:00, 11:00-12:00]}, {11-03, []} ]
     * (D1, B4 car-only, OIL, SUV, ...) → every day with no slots
     * </pre>
     *
     * @throws NotFoundException     unknown dealership, service type or vehicle type, or a bay
     *                               that is unknown or belongs to another dealership
     * @throws InvalidRangeException {@code to < from}, more than 14 days, or a year outside 1..9999
     */
    AvailabilityResponse find(UUID dealershipId, UUID serviceBayId, UUID serviceTypeId, UUID vehicleTypeId,
                              LocalDate from, LocalDate to);
}
