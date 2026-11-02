package com.keyloop.scheduler.api.service.lookup.impl;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.keyloop.scheduler.api.constant.ErrorMessages;
import com.keyloop.scheduler.api.constant.lookup.LookupConstants;
import com.keyloop.scheduler.api.dto.lookup.AvailabilityLookupQuery;
import com.keyloop.scheduler.api.dto.lookup.AvailabilityLookupResponse;
import com.keyloop.scheduler.api.dto.lookup.AvailabilityLookupResponse.Pair;
import com.keyloop.scheduler.api.dto.lookup.WindowProblem;
import com.keyloop.scheduler.api.exception.NotFoundException;
import com.keyloop.scheduler.api.exception.lookup.InvalidWindowException;
import com.keyloop.scheduler.api.model.availability.Dealership;
import com.keyloop.scheduler.api.model.availability.ServiceType;
import com.keyloop.scheduler.api.model.lookup.NamedResource;
import com.keyloop.scheduler.api.repository.availability.AvailabilityRepository;
import com.keyloop.scheduler.api.repository.lookup.LookupRepository;
import com.keyloop.scheduler.api.service.lookup.LookupService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link LookupService} straight over PostgreSQL, no cache, so the answer is real time.
 * PostgreSQL still guards every booking (exclusion constraints): a free pair here is a
 * hint, not a hold. Dealership, service type and vehicle type come from
 * {@link AvailabilityRepository}; free resources from {@link LookupRepository}.
 */
@Service
public class LookupServiceImpl implements LookupService {

    private final AvailabilityRepository catalogue;
    private final LookupRepository lookups;
    private final Clock clock;

    public LookupServiceImpl(AvailabilityRepository catalogue, LookupRepository lookups, Clock clock) {
        this.catalogue = catalogue;
        this.lookups = lookups;
        this.clock = clock;
    }

    @Override
    public AvailabilityLookupResponse lookup(AvailabilityLookupQuery query) {
        Instant from = query.from().toInstant();
        Instant to = query.to().toInstant();
        validateWindow(from, to);

        UUID dealershipId = query.dealershipId();
        Dealership dealership = catalogue.findDealership(dealershipId)
                .orElseThrow(() -> new NotFoundException(ErrorMessages.ENTITY_DEALERSHIP, dealershipId));
        @Nullable ServiceType serviceType = query.serviceTypeId() == null ? null
                : catalogue.findServiceType(query.serviceTypeId())
                .orElseThrow(() -> new NotFoundException(ErrorMessages.ENTITY_SERVICE_TYPE, query.serviceTypeId()));
        @Nullable String vehicleTypeName = query.vehicleTypeId() == null ? null
                : catalogue.findVehicleTypeName(query.vehicleTypeId())
                .orElseThrow(() -> new NotFoundException(ErrorMessages.ENTITY_VEHICLE_TYPE, query.vehicleTypeId()));
        // A bay or technician of another dealership is "not found" here: the search is per dealership
        if (query.serviceBayId() != null && !lookups.bayBelongsTo(query.serviceBayId(), dealershipId)) {
            throw new NotFoundException(ErrorMessages.ENTITY_SERVICE_BAY, query.serviceBayId());
        }
        if (query.technicianId() != null && !lookups.technicianBelongsTo(query.technicianId(), dealershipId)) {
            throw new NotFoundException(ErrorMessages.ENTITY_TECHNICIAN, query.technicianId());
        }
        ZoneId zone = zoneOf(dealership);

        List<WindowProblem> reasons = windowProblems(dealership, zone, serviceType, from, to);
        List<Pair> all = reasons.isEmpty() ? freePairs(query, from, to) : List.of();
        List<Pair> shown = all.size() > LookupConstants.MAX_PAIRS ? all.subList(0, LookupConstants.MAX_PAIRS) : all;

        return new AvailabilityLookupResponse(!all.isEmpty(), local(from, zone), local(to, zone),
                dealershipId, dealership.name(), dealership.timezone(),
                query.serviceTypeId(), serviceType == null ? null : serviceType.name(),
                query.vehicleTypeId(), vehicleTypeName,
                reasons, all.size(), List.copyOf(shown));
    }

    static void validateWindow(Instant from, Instant to) {
        if (!to.isAfter(from)) {
            throw new InvalidWindowException(LookupConstants.WINDOW_TO_NOT_AFTER_FROM.formatted(to, from));
        }
        long minutes = Duration.between(from, to).toMinutes();
        if (minutes > LookupConstants.MAX_WINDOW_MINUTES) {
            throw new InvalidWindowException(LookupConstants.WINDOW_TOO_LONG.formatted(
                    minutes, LookupConstants.MAX_WINDOW_MINUTES));
        }
    }

    private List<WindowProblem> windowProblems(Dealership dealership, ZoneId zone, @Nullable ServiceType serviceType,
                                               Instant from, Instant to) {
        List<WindowProblem> reasons = new ArrayList<>();
        if (from.isBefore(clock.instant())) {
            reasons.add(WindowProblem.STARTS_IN_PAST);
        }
        if (outsideHours(dealership, zone, from, to)) {
            reasons.add(WindowProblem.OUTSIDE_BUSINESS_HOURS);
        }
        if (serviceType != null && Duration.between(from, to).toMinutes() < serviceType.durationMinutes()) {
            reasons.add(WindowProblem.SHORTER_THAN_SERVICE);
        }
        return List.copyOf(reasons);
    }

    /** Every free bay with every free technician; both lists are sorted by name. */
    private List<Pair> freePairs(AvailabilityLookupQuery query, Instant from, Instant to) {
        List<NamedResource> bays = lookups.findFreeBays(query.dealershipId(), query.serviceBayId(),
                query.vehicleTypeId(), from, to);
        if (bays.isEmpty()) {
            return List.of();
        }
        List<NamedResource> technicians = lookups.findFreeTechnicians(query.dealershipId(), query.technicianId(),
                query.serviceTypeId(), query.vehicleTypeId(), from, to);
        List<Pair> pairs = new ArrayList<>(bays.size() * technicians.size());
        for (NamedResource bay : bays) {
            for (NamedResource technician : technicians) {
                pairs.add(new Pair(bay.id(), bay.name(), technician.id(), technician.name()));
            }
        }
        return pairs;
    }

    /**
     * Same rule as the booking check: the local start is not before open and the end is
     * not after local close of the start's date.
     */
    private static boolean outsideHours(Dealership dealership, ZoneId zone, Instant from, Instant to) {
        LocalDate day = LocalDate.ofInstant(from, zone);
        return from.isBefore(atMinute(day, dealership.openMinute(), zone))
                || to.isAfter(atMinute(day, dealership.closeMinute(), zone));
    }

    private static Instant atMinute(LocalDate day, int minute, ZoneId zone) {
        return ZonedDateTime.ofLocal(day.atStartOfDay().plusMinutes(minute), zone, null)
                .withLaterOffsetAtOverlap().toInstant();
    }

    private static OffsetDateTime local(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toOffsetDateTime();
    }

    private static ZoneId zoneOf(Dealership dealership) {
        try {
            return ZoneId.of(dealership.timezone());
        } catch (DateTimeException e) {
            // Bad data, not a client error: surfaces as 500
            throw new IllegalStateException(LookupConstants.INVALID_TIME_ZONE.formatted(dealership.timezone()), e);
        }
    }
}
