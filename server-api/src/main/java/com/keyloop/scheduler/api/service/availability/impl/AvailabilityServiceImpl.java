package com.keyloop.scheduler.api.service.availability.impl;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.keyloop.scheduler.api.cache.availability.AvailabilityCache;
import com.keyloop.scheduler.api.constant.ErrorMessages;
import com.keyloop.scheduler.api.constant.availability.AvailabilityConstants;
import com.keyloop.scheduler.api.dto.availability.AvailabilityResponse;
import com.keyloop.scheduler.api.dto.availability.AvailabilityResponse.Slot;
import com.keyloop.scheduler.api.exception.NotFoundException;
import com.keyloop.scheduler.api.exception.availability.InvalidRangeException;
import com.keyloop.scheduler.api.model.availability.Booking;
import com.keyloop.scheduler.api.model.availability.Dealership;
import com.keyloop.scheduler.api.model.availability.Interval;
import com.keyloop.scheduler.api.repository.availability.AvailabilityRepository;
import com.keyloop.scheduler.api.service.availability.AvailabilityService;
import org.springframework.stereotype.Service;

/**
 * {@link AvailabilityService} over the versioned cache and PostgreSQL (spec-read-availability,
 * DLD §8). Each local date is read through the cache ({@link AvailabilityCache},
 * spec-availability-ops); a miss computes the full day from PostgreSQL. Slots that already
 * started are dropped per request, after the cache.
 *
 * <p>No surrounding transaction: a request may wait for another request's cache rebuild,
 * and it must not hold a database connection while it waits.
 */
@Service
public class AvailabilityServiceImpl implements AvailabilityService {

    private final AvailabilityRepository repository;
    private final AvailabilityCache cache;
    private final Clock clock;

    public AvailabilityServiceImpl(AvailabilityRepository repository, AvailabilityCache cache, Clock clock) {
        this.repository = repository;
        this.cache = cache;
        this.clock = clock;
    }

    @Override
    public AvailabilityResponse find(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId,
                                     LocalDate from, LocalDate to) {
        validateRange(from, to);
        List<LocalDate> dates = from.datesUntil(to.plusDays(1)).toList();
        // Versions first, before any PostgreSQL read (cache-read-path.md step 1)
        AvailabilityCache.Lookup lookup = cache.lookup(dealershipId, serviceTypeId, vehicleTypeId, dates);

        Dealership dealership = repository.findDealership(dealershipId)
                .orElseThrow(() -> new NotFoundException(ErrorMessages.ENTITY_DEALERSHIP, dealershipId));
        int duration = repository.findServiceDurationMinutes(serviceTypeId)
                .orElseThrow(() -> new NotFoundException(ErrorMessages.ENTITY_SERVICE_TYPE, serviceTypeId));
        if (!repository.vehicleTypeExists(vehicleTypeId)) {
            throw new NotFoundException(ErrorMessages.ENTITY_VEHICLE_TYPE, vehicleTypeId);
        }
        ZoneId zone = zoneOf(dealership);

        Map<LocalDate, List<Slot>> fullDays = lookup.slots(missing ->
                computeFullDays(dealershipId, serviceTypeId, vehicleTypeId, dealership, zone, duration, missing));

        Instant now = clock.instant();
        List<AvailabilityResponse.Day> days = dates.stream()
                .map(date -> new AvailabilityResponse.Day(date, SlotCalculator.notStartedBefore(fullDays.get(date), now)))
                .toList();
        return new AvailabilityResponse(dealershipId, serviceTypeId, vehicleTypeId,
                dealership.timezone(), duration, days);
    }

    /** Full-day slot lists (no now-cut) for the given dates, from PostgreSQL. */
    private Map<LocalDate, List<Slot>> computeFullDays(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId,
                                                       Dealership dealership, ZoneId zone, int duration,
                                                       Set<LocalDate> wanted) {
        // One pass over the smallest range that holds every wanted date
        LocalDate from = Collections.min(wanted);
        LocalDate to = Collections.max(wanted);

        List<UUID> bayIds = repository.findFittingBayIds(dealershipId, vehicleTypeId);
        List<UUID> technicianIds = repository.findQualifiedTechnicianIds(dealershipId, serviceTypeId, vehicleTypeId);

        Map<UUID, List<Interval>> bayBusy = emptySchedules(bayIds);
        Map<UUID, List<Interval>> technicianBusy = emptySchedules(technicianIds);
        if (!bayIds.isEmpty() && !technicianIds.isEmpty()) {
            // One load for the whole range: local from 00:00 to local (to + 1) 00:00
            Instant spanStart = from.atStartOfDay(zone).toInstant();
            Instant spanEnd = to.plusDays(1).atStartOfDay(zone).toInstant();
            for (Booking b : repository.findBookings(dealershipId, bayIds, technicianIds, spanStart, spanEnd)) {
                Interval busy = new Interval(b.start(), b.end());
                // A booking may hold one fitting resource and one that does not fit this query
                List<Interval> bay = bayBusy.get(b.serviceBayId());
                if (bay != null) {
                    bay.add(busy);
                }
                List<Interval> tech = technicianBusy.get(b.technicianId());
                if (tech != null) {
                    tech.add(busy);
                }
            }
        }

        Map<LocalDate, List<Slot>> result = new HashMap<>();
        for (AvailabilityResponse.Day day : SlotCalculator.compute(from, to, zone,
                dealership.openMinute(), dealership.closeMinute(), duration,
                bayBusy.values(), technicianBusy.values())) {
            if (wanted.contains(day.date())) {
                result.put(day.date(), day.slots());
            }
        }
        return result;
    }

    static void validateRange(LocalDate from, LocalDate to) {
        if (outOfBounds(from) || outOfBounds(to)) {
            throw new InvalidRangeException(AvailabilityConstants.RANGE_YEARS_OUT_OF_BOUNDS.formatted(
                    AvailabilityConstants.MIN_YEAR, AvailabilityConstants.MAX_YEAR));
        }
        if (to.isBefore(from)) {
            throw new InvalidRangeException(AvailabilityConstants.RANGE_TO_BEFORE_FROM.formatted(to, from));
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > AvailabilityConstants.MAX_DAYS) {
            throw new InvalidRangeException(AvailabilityConstants.RANGE_TOO_LONG.formatted(
                    days, AvailabilityConstants.MAX_DAYS));
        }
    }

    private static boolean outOfBounds(LocalDate date) {
        return date.getYear() < AvailabilityConstants.MIN_YEAR || date.getYear() > AvailabilityConstants.MAX_YEAR;
    }

    private static ZoneId zoneOf(Dealership dealership) {
        try {
            return ZoneId.of(dealership.timezone());
        } catch (DateTimeException e) {
            // Bad data, not a client error: surfaces as 500
            throw new IllegalStateException(AvailabilityConstants.INVALID_TIME_ZONE.formatted(dealership.timezone()), e);
        }
    }

    private static Map<UUID, List<Interval>> emptySchedules(List<UUID> ids) {
        Map<UUID, List<Interval>> schedules = new HashMap<>();
        ids.forEach(id -> schedules.put(id, new ArrayList<>()));
        return schedules;
    }
}
