package com.keyloop.scheduler.api.service.appointment.impl;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.keyloop.scheduler.api.config.appointment.BookingProperties;
import com.keyloop.scheduler.api.constant.ErrorMessages;
import com.keyloop.scheduler.api.constant.availability.AvailabilityConstants;
import com.keyloop.scheduler.api.exception.NotFoundException;
import com.keyloop.scheduler.api.model.availability.Booking;
import com.keyloop.scheduler.api.model.availability.Dealership;
import com.keyloop.scheduler.api.model.appointment.BookableBay;
import com.keyloop.scheduler.api.model.appointment.CandidateResult;
import com.keyloop.scheduler.api.model.appointment.CandidateResult.Pair;
import com.keyloop.scheduler.api.repository.availability.AvailabilityRepository;
import com.keyloop.scheduler.api.repository.appointment.CandidateRepository;
import com.keyloop.scheduler.api.service.appointment.CandidateSelector;
import org.springframework.stereotype.Service;

/**
 * {@link CandidateSelector} on PostgreSQL (candidate-selection.md, DLD §5, Spec04 §6).
 * Steps: the chosen bay fits, bay and technicians free in the window, then technicians
 * with fewer jobs that local day first, up to {@code scheduler.booking.max-pairs}.
 */
@Service
public class CandidateSelectorImpl implements CandidateSelector {

    /** Same order as PostgreSQL {@code ORDER BY id}: unsigned bytes, which is the hex text order. */
    private static final Comparator<UUID> BY_ID = Comparator.comparing(UUID::toString);

    private final AvailabilityRepository availability;
    private final CandidateRepository candidates;
    private final BookingProperties properties;

    public CandidateSelectorImpl(AvailabilityRepository availability, CandidateRepository candidates,
                                 BookingProperties properties) {
        this.availability = availability;
        this.candidates = candidates;
        this.properties = properties;
    }

    @Override
    public CandidateResult select(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId, UUID serviceBayId,
                                  Instant start) {
        Dealership dealership = availability.findDealership(dealershipId)
                .orElseThrow(() -> new NotFoundException(ErrorMessages.ENTITY_DEALERSHIP, dealershipId));
        int duration = availability.findServiceType(serviceTypeId)
                .orElseThrow(() -> new NotFoundException(ErrorMessages.ENTITY_SERVICE_TYPE, serviceTypeId))
                .durationMinutes();
        Instant end = start.plus(Duration.ofMinutes(duration));

        // 1. The chosen bay fits, and qualified technicians exist
        BookableBay bay = candidates.findBookableBay(dealershipId, serviceBayId, vehicleTypeId)
                .orElseThrow(() -> new NotFoundException(ErrorMessages.ENTITY_SERVICE_BAY, serviceBayId));
        List<UUID> technicians = availability.findQualifiedTechnicianIds(dealershipId, serviceTypeId, vehicleTypeId);
        if (!bay.bookable() || technicians.isEmpty()) {
            return CandidateResult.notServiceable();
        }

        // 2. Free in [start, end): a busy bay blocks every pair
        Set<UUID> busy = new HashSet<>();
        for (Booking b : availability.findBookings(dealershipId, List.of(serviceBayId), technicians, start, end)) {
            busy.add(b.serviceBayId());
            busy.add(b.technicianId());
        }
        List<UUID> freeTechnicians = technicians.stream().filter(id -> !busy.contains(id)).toList();
        if (busy.contains(serviceBayId) || freeTechnicians.isEmpty()) {
            return CandidateResult.noneFree();
        }

        // 3. Technicians with fewer jobs that local day first: spread the work
        ZoneId zone = zoneOf(dealership);
        LocalDate day = start.atZone(zone).toLocalDate();
        Map<UUID, Integer> jobs = candidates.countAppointments(freeTechnicians,
                day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant());
        return CandidateResult.of(freeTechnicians.stream()
                .sorted(Comparator.<UUID>comparingInt(id -> jobs.getOrDefault(id, 0)).thenComparing(BY_ID))
                .limit(properties.maxPairs())
                .map(technician -> new Pair(serviceBayId, technician))
                .toList());
    }

    private static ZoneId zoneOf(Dealership dealership) {
        try {
            return ZoneId.of(dealership.timezone());
        } catch (DateTimeException e) {
            throw new IllegalStateException(AvailabilityConstants.INVALID_TIME_ZONE.formatted(dealership.timezone()), e);
        }
    }
}
