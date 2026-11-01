package com.keyloop.scheduler.api.support;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.keyloop.scheduler.api.cache.availability.AvailabilityCache;

/**
 * A cache that never touches Redis: every read is computed by the loader, once, with all
 * dates of the lookup in request order (what the old {@code AvailabilityCache.disabled()} did).
 * No dates means no loader call.
 */
public final class PassThroughAvailabilityCache implements AvailabilityCache {

    @Override
    public Lookup lookup(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId, List<LocalDate> dates) {
        List<LocalDate> copy = List.copyOf(dates);
        return loader -> copy.isEmpty() ? Map.of() : loader.apply(new LinkedHashSet<>(copy));
    }
}
