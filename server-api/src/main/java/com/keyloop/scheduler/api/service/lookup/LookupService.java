package com.keyloop.scheduler.api.service.lookup;

import com.keyloop.scheduler.api.dto.lookup.AvailabilityLookupQuery;
import com.keyloop.scheduler.api.dto.lookup.AvailabilityLookupResponse;
import com.keyloop.scheduler.api.exception.NotFoundException;
import com.keyloop.scheduler.api.exception.lookup.InvalidWindowException;

/**
 * Real-time availability check: which service bay AND qualified technician pairs of one
 * dealership are free for the whole service window? With every id given it answers one
 * exact slot (1-1 check before confirm). It only reads data and never holds anything.
 *
 * <pre>
 * lookup(D1, 09:00 → 10:00, serviceType OIL)
 *
 *   bays  B1 ░░░░░░░░  B2 ░░████░░        █ booked  ░ free
 *   techs T1 ░░░░░░░░  T2 (no OIL skill)
 *         09:00   10:00
 *   pairs → [(B1, T1)]
 * </pre>
 */
public interface LookupService {

    /**
     * Free (bay, technician) pairs over {@code [from, to)}. Window problems (past, outside
     * hours, shorter than the service) are listed in {@code reasons} and give no pairs.
     *
     * <pre>
     * (D1, 09:00-10:00)                    → pairs [(B1,T1), (B1,T3)]
     * (D1, B1, T1, 09:00-10:00), T1 booked → available false, pairs []
     * (D1, 18:00-19:00), close 17:00       → reasons [OUTSIDE_BUSINESS_HOURS]
     * </pre>
     *
     * @throws NotFoundException      unknown dealership, service type or vehicle type, or a bay /
     *                                technician that is not in the dealership
     * @throws InvalidWindowException {@code to <= from} or a window longer than 24 hours
     */
    AvailabilityLookupResponse lookup(AvailabilityLookupQuery query);
}
