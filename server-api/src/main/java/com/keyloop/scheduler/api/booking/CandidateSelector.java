package com.keyloop.scheduler.api.booking;

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

import com.keyloop.scheduler.api.availability.AvailabilityRepository;
import com.keyloop.scheduler.api.availability.AvailabilityRepository.Booking;
import com.keyloop.scheduler.api.availability.AvailabilityRepository.Dealership;
import com.keyloop.scheduler.api.availability.NotFoundException;
import com.keyloop.scheduler.api.booking.CandidateResult.Pair;
import org.springframework.stereotype.Service;

/**
 * Picks the (bay, technician) pairs a booking should try for one window
 * (candidate-selection.md, DLD §5, Spec04 §6). Reads PostgreSQL only, never the cache:
 * the database decides bookings. Same database state → same pairs.
 *
 * <pre>
 * Free bays (ordered) | Free techs (ordered) | Pairs
 * B1, B2, B3          | T1, T2, T3, T4       | (B1,T1) (B2,T2) (B3,T3)
 * B1                  | T1, T2, T3           | (B1,T1) (B1,T2) (B1,T3)
 * B1, B2              | T1                   | (B1,T1) (B2,T1)
 * B1, B2              | T1, T2               | (B1,T1) (B2,T2) (B1,T2)
 * </pre>
 */
@Service
public class CandidateSelector {

    /** Spec04 §6: a booking tries at most this many pairs. */
    public static final int MAX_PAIRS = 3;

    /** Same order as PostgreSQL {@code ORDER BY id}: unsigned bytes, which is the hex text order. */
    private static final Comparator<UUID> BY_ID = Comparator.comparing(UUID::toString);

    private final AvailabilityRepository availability;
    private final CandidateRepository candidates;

    public CandidateSelector(AvailabilityRepository availability, CandidateRepository candidates) {
        this.availability = availability;
        this.candidates = candidates;
    }

    /**
     * @param vehicleTypeId from the vehicle, never from the client
     * @param start         window start; the end is {@code start + service duration}
     */
    public CandidateResult select(UUID dealershipId, UUID serviceTypeId, UUID vehicleTypeId, Instant start) {
        Dealership dealership = availability.findDealership(dealershipId)
                .orElseThrow(() -> new NotFoundException("Dealership", dealershipId));
        int duration = availability.findServiceDurationMinutes(serviceTypeId)
                .orElseThrow(() -> new NotFoundException("Service type", serviceTypeId));
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

        return CandidateResult.of(pairs(orderedBays, orderedTechnicians));
    }

    /**
     * Step 5: diagonal first, so every pair differs in both bay and technician and one
     * busy resource fails one try, not all of them; then fill from the cross product in
     * (bay index, technician index) order, up to {@value #MAX_PAIRS}.
     */
    static List<Pair> pairs(List<UUID> bays, List<UUID> technicians) {
        List<Pair> pairs = new ArrayList<>();
        int diagonal = Math.min(Math.min(bays.size(), technicians.size()), MAX_PAIRS);
        for (int i = 0; i < diagonal; i++) {
            pairs.add(new Pair(bays.get(i), technicians.get(i)));
        }
        for (int b = 0; b < bays.size() && pairs.size() < MAX_PAIRS; b++) {
            for (int t = 0; t < technicians.size() && pairs.size() < MAX_PAIRS; t++) {
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
            throw new IllegalStateException("Dealership has an invalid time zone: " + dealership.timezone(), e);
        }
    }
}
