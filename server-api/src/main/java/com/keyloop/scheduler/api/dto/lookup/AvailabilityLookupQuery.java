package com.keyloop.scheduler.api.dto.lookup;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Query of {@code GET /api/v1/lookups/availability}. {@code from}, {@code to} and
 * {@code dealershipId} are required; every other id narrows the search, {@code null}
 * means "any".
 *
 * <pre>
 * from=09:00Z to=10:00Z dealershipId=D1                          required
 * serviceBayId=B1 technicianId=T1 serviceTypeId=OIL vehicleTypeId=SUV   optional
 * </pre>
 */
public record AvailabilityLookupQuery(
        OffsetDateTime from,
        OffsetDateTime to,
        UUID dealershipId,
        @Nullable UUID serviceBayId,
        @Nullable UUID technicianId,
        @Nullable UUID serviceTypeId,
        @Nullable UUID vehicleTypeId) {}
