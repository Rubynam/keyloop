package com.keyloop.scheduler.api.dto.lookup;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Body of {@code GET /api/v1/lookups/availability}: every (bay, technician) pair that is
 * free for the whole window. {@code available} is true when at least one pair is listed.
 * Times use the dealership's UTC offset. Optional ids not asked come back as {@code null}.
 *
 * <pre>
 * { "available": true, "from": "2026-11-03T09:00:00+07:00", "to": "2026-11-03T10:00:00+07:00",
 *   "dealershipId": D1, "dealershipName": "Keyloop Hub", "timezone": "Asia/Ho_Chi_Minh",
 *   "serviceTypeId": S1, "serviceTypeName": "Oil change", "vehicleTypeId": null, "vehicleTypeName": null,
 *   "reasons": [], "totalPairs": 2,
 *   "pairs": [ { "serviceBayId": B1, "serviceBayName": "Bay 1", "technicianId": T1, "technicianName": "An" },
 *              { "serviceBayId": B2, "serviceBayName": "Bay 2", "technicianId": T1, "technicianName": "An" } ] }
 * </pre>
 *
 * @param reasons    window problems; when not empty, {@code pairs} is empty
 * @param totalPairs number of free pairs; {@code pairs} holds at most the first 200
 */
public record AvailabilityLookupResponse(
        boolean available,
        OffsetDateTime from,
        OffsetDateTime to,
        UUID dealershipId,
        String dealershipName,
        String timezone,
        @Nullable UUID serviceTypeId,
        @Nullable String serviceTypeName,
        @Nullable UUID vehicleTypeId,
        @Nullable String vehicleTypeName,
        List<WindowProblem> reasons,
        int totalPairs,
        List<Pair> pairs) {

    /** One bay and one technician that are both free over {@code [from, to)}. */
    public record Pair(UUID serviceBayId, String serviceBayName, UUID technicianId, String technicianName) {}
}
