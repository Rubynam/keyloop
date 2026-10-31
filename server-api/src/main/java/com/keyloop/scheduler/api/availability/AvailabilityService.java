package com.keyloop.scheduler.api.availability;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.keyloop.scheduler.api.availability.AvailabilityRepository.Booking;
import com.keyloop.scheduler.api.availability.AvailabilityRepository.Dealership;
import com.keyloop.scheduler.api.availability.SlotCalculator.Interval;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Free slots for one (dealership, service type, vehicle type) over a local date range
 * (spec-read-availability, DLD §8). DB-only for now; the Redis cache-aside read path
 * (D1) is a follow-up.
 */
@Service
public class AvailabilityService {

    /** D1 abuse bound: at most this many local dates per request. */
    static final int MAX_DAYS = 14;
    /** Sane date bounds: java.time extremes would overflow on plusDays / atStartOfDay. */
    static final int MIN_YEAR = 1;
    static final int MAX_YEAR = 9999;

    private final AvailabilityRepository repository;
    private final Clock clock;

    @Autowired
    public AvailabilityService(AvailabilityRepository repository) {
        this(repository, Clock.systemUTC());
    }

    AvailabilityService(AvailabilityRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse find(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId,
                                     LocalDate from, LocalDate to) {
        validateRange(from, to);

        Dealership dealership = repository.findDealership(dealershipId)
                .orElseThrow(() -> new NotFoundException("Dealership", dealershipId));
        int duration = repository.findServiceDurationMinutes(serviceTypeId)
                .orElseThrow(() -> new NotFoundException("Service type", serviceTypeId));
        if (!repository.vehicleTypeExists(vehicleTypeId)) {
            throw new NotFoundException("Vehicle type", vehicleTypeId);
        }
        ZoneId zone = zoneOf(dealership);

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

        List<AvailabilityResponse.Day> days = SlotCalculator.compute(from, to, zone,
                dealership.openMinute(), dealership.closeMinute(), duration,
                bayBusy.values(), technicianBusy.values(), clock.instant());
        return new AvailabilityResponse(dealershipId, serviceTypeId, vehicleTypeId,
                dealership.timezone(), duration, days);
    }

    static void validateRange(LocalDate from, LocalDate to) {
        if (outOfBounds(from) || outOfBounds(to)) {
            throw new InvalidRangeException("Dates must be between years " + MIN_YEAR + " and " + MAX_YEAR);
        }
        if (to.isBefore(from)) {
            throw new InvalidRangeException("'to' (" + to + ") is before 'from' (" + from + ")");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_DAYS) {
            throw new InvalidRangeException("Range is " + days + " days; at most " + MAX_DAYS + " are allowed");
        }
    }

    private static boolean outOfBounds(LocalDate date) {
        return date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR;
    }

    private static ZoneId zoneOf(Dealership dealership) {
        try {
            return ZoneId.of(dealership.timezone());
        } catch (DateTimeException e) {
            // Bad data, not a client error: surfaces as 500
            throw new IllegalStateException("Dealership has an invalid time zone: " + dealership.timezone(), e);
        }
    }

    private static Map<UUID, List<Interval>> emptySchedules(List<UUID> ids) {
        Map<UUID, List<Interval>> schedules = new HashMap<>();
        ids.forEach(id -> schedules.put(id, new ArrayList<>()));
        return schedules;
    }
}
