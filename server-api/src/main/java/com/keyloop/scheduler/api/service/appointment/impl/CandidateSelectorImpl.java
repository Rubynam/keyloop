package com.keyloop.scheduler.api.service.appointment.impl;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
import com.keyloop.scheduler.api.model.appointment.CandidateResult;
import com.keyloop.scheduler.api.model.appointment.CandidateResult.Pair;
import com.keyloop.scheduler.api.repository.availability.AvailabilityRepository;
import com.keyloop.scheduler.api.repository.appointment.CandidateRepository;
import com.keyloop.scheduler.api.service.appointment.CandidateSelector;
import org.springframework.stereotype.Service;

/**
 * {@link CandidateSelector} on PostgreSQL (candidate-selection.md, DLD §5, Spec04 §6).
 * Steps: fitting resources, free in the window, bays with fewer vehicle types first,
 * technicians with fewer jobs that local day first, then pairs (diagonal first).
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
    public CandidateResult select(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId, Instant start) {
        Dealership dealership = availability.findDealership(dealershipId)
                .orElseThrow(() -> new NotFoundException(ErrorMessages.ENTITY_DEALERSHIP, dealershipId));
        int duration = availability.findServiceDurationMinutes(serviceTypeId)
                .orElseThrow(() -> new NotFoundException(ErrorMessages.ENTITY_SERVICE_TYPE, serviceTypeId));
        Instant end = start.plus(Duration.ofMinutes(duration));

        // 1. Fitting resources
        List<UUID> bays = availability.findFittingBayIds(dealershipId, vehicleTypeId);
        List<UUID> technicians = availability.findQualifiedTechnicianIds(dealershipId, serviceTypeId, vehicleTypeId);
        if (bays.isEmpty() || technicians.isEmpty()) {
            return CandidateResult.notServiceable();
        }

        // 2. Free in [start, end)
        Set<UUID> busy = new HashSet<>();
        for (Booking b : availability.findBookings(dealershipId, bays, technicians, start, end)) {
            busy.add(b.serviceBayId());
            busy.add(b.technicianId());
        }
        List<UUID> freeBays = bays.stream().filter(id -> !busy.contains(id)).toList();
        List<UUID> freeTechnicians = technicians.stream().filter(id -> !busy.contains(id)).toList();
        if (freeBays.isEmpty() || freeTechnicians.isEmpty()) {
            return CandidateResult.noneFree();
        }

        // 3. Bays taking fewer vehicle types first: keep flexible bays for vehicles only they can take (D2)
        Map<UUID, Integer> types = candidates.countVehicleTypes(freeBays);
        List<UUID> orderedBays = freeBays.stream()
                .sorted(Comparator.<UUID>comparingInt(types::get).thenComparing(BY_ID))
                .toList();

        // 4. Technicians with fewer jobs that local day first: spread the work
        ZoneId zone = zoneOf(dealership);
        LocalDate day = start.atZone(zone).toLocalDate();
        Map<UUID, Integer> jobs = candidates.countAppointments(freeTechnicians,
                day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant());
        List<UUID> orderedTechnicians = freeTechnicians.stream()
                .sorted(Comparator.<UUID>comparingInt(id -> jobs.getOrDefault(id, 0)).thenComparing(BY_ID))
                .toList();

        return CandidateResult.of(pairs(orderedBays, orderedTechnicians, properties.maxPairs()));
    }

    /**
     * Step 5: diagonal first, so every pair differs in both bay and technician and one
     * busy resource fails one try, not all of them; then fill from the cross product in
     * (bay index, technician index) order, up to {@code maxPairs} ({@code scheduler.booking.max-pairs}).
     */
    static List<Pair> pairs(List<UUID> bays, List<UUID> technicians, int maxPairs) {
        List<Pair> pairs = new ArrayList<>();
        int diagonal = Math.min(Math.min(bays.size(), technicians.size()), maxPairs);
        for (int i = 0; i < diagonal; i++) {
            pairs.add(new Pair(bays.get(i), technicians.get(i)));
        }
        for (int b = 0; b < bays.size() && pairs.size() < maxPairs; b++) {
            for (int t = 0; t < technicians.size() && pairs.size() < maxPairs; t++) {
                Pair pair = new Pair(bays.get(b), technicians.get(t));
                if (!pairs.contains(pair)) {
                    pairs.add(pair);
                }
            }
        }
        return List.copyOf(pairs);
    }

    private static ZoneId zoneOf(Dealership dealership) {
        try {
            return ZoneId.of(dealership.timezone());
        } catch (DateTimeException e) {
            throw new IllegalStateException(AvailabilityConstants.INVALID_TIME_ZONE.formatted(dealership.timezone()), e);
        }
    }
}
