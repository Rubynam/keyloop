package com.keyloop.scheduler.api.service.appointment.impl;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.keyloop.scheduler.api.config.availability.AvailabilityProperties;
import com.keyloop.scheduler.api.constant.ErrorMessages;
import com.keyloop.scheduler.api.constant.OutboxEventTypes;
import com.keyloop.scheduler.api.constant.availability.AvailabilityConstants;
import com.keyloop.scheduler.api.dto.appointment.AppointmentResponse;
import com.keyloop.scheduler.api.dto.appointment.BookAppointmentRequest;
import com.keyloop.scheduler.api.dto.appointment.BookingResult;
import com.keyloop.scheduler.api.exception.NotFoundException;
import com.keyloop.scheduler.api.exception.appointment.BookingConflictException;
import com.keyloop.scheduler.api.exception.appointment.DuplicateIdempotencyKeyException;
import com.keyloop.scheduler.api.exception.appointment.IdempotencyKeyMismatchException;
import com.keyloop.scheduler.api.exception.appointment.NotServiceableException;
import com.keyloop.scheduler.api.exception.appointment.OffSlotGridException;
import com.keyloop.scheduler.api.exception.appointment.OutsideBusinessHoursException;
import com.keyloop.scheduler.api.exception.appointment.ResourceGoneException;
import com.keyloop.scheduler.api.exception.appointment.SlotTakenException;
import com.keyloop.scheduler.api.exception.appointment.VehicleBusyException;
import com.keyloop.scheduler.api.exception.appointment.VehicleOwnerMismatchException;
import com.keyloop.scheduler.api.model.appointment.AppointmentBookedPayload;
import com.keyloop.scheduler.api.model.appointment.CandidateResult;
import com.keyloop.scheduler.api.model.appointment.CandidateResult.Pair;
import com.keyloop.scheduler.api.model.appointment.NewAppointment;
import com.keyloop.scheduler.api.model.appointment.StoredAppointment;
import com.keyloop.scheduler.api.model.appointment.VehicleRef;
import com.keyloop.scheduler.api.model.availability.Dealership;
import com.keyloop.scheduler.api.repository.appointment.AppointmentRepository;
import com.keyloop.scheduler.api.repository.appointment.OutboxRepository;
import com.keyloop.scheduler.api.repository.availability.AvailabilityRepository;
import com.keyloop.scheduler.api.service.appointment.AppointmentService;
import com.keyloop.scheduler.api.service.appointment.CandidateSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * {@link AppointmentService} on PostgreSQL (Spec07 §5–§7, Spec04 §6). Checks run in the
 * §5.1 order; then each (bay, technician) pair gets its own short transaction with the
 * appointment and its outbox event. The exclusion constraints decide; this class only
 * reacts to what they say.
 *
 * <p>No {@code @Transactional} here: every pair needs its own transaction, run through
 * {@link TransactionOperations}. The candidate read is not locked: a stale read costs
 * one more try, never a double booking.
 *
 * <pre>
 * key known? ──yes──► same 5 fields → replay  /  other → 422
 *    │no
 * 404s ─► owner 400 ─► grid 422 ─► hours 422 ─► vehicle busy 422 ─► candidates 422 / 409
 *    │
 * for pair in pairs:  BEGIN insert appointment + outbox COMMIT ─► 201
 *                     bay / tech taken, deadlock, resource gone ─► next pair
 *                     vehicle taken ─► 422      same key committed ─► replay
 * all failed ─► 409   (vehicle booked meanwhile ─► 422; same key won ─► replay)
 * </pre>
 */
@Service
public class AppointmentServiceImpl implements AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentServiceImpl.class);

    /** What one pair try ended with; logged once per try. */
    private enum TryOutcome { BOOKED, BAY_TAKEN, TECHNICIAN_TAKEN, VEHICLE_TAKEN, DEADLOCK, RESOURCE_GONE, KEY_TAKEN }

    private final AvailabilityRepository availability;
    private final AppointmentRepository appointments;
    private final OutboxRepository outbox;
    private final CandidateSelector selector;
    private final TransactionOperations transactions;
    private final AvailabilityProperties slots;

    public AppointmentServiceImpl(AvailabilityRepository availability, AppointmentRepository appointments,
                                  OutboxRepository outbox, CandidateSelector selector,
                                  TransactionOperations transactions, AvailabilityProperties slots) {
        this.availability = availability;
        this.appointments = appointments;
        this.outbox = outbox;
        this.selector = selector;
        this.transactions = transactions;
        this.slots = slots;
    }

    @Override
    public BookingResult book(String idempotencyKey, BookAppointmentRequest request) {
        // 0. Cheap lookup first: a retry never re-validates against data that changed since
        Optional<StoredAppointment> known = appointments.findByIdempotencyKey(idempotencyKey);
        if (known.isPresent()) {
            return replay(known.get(), request);
        }
        try {
            return bookNew(idempotencyKey, request);
        } catch (VehicleBusyException | BookingConflictException e) {
            // A parallel call with the same key may have committed first: its own vehicle
            // or pair then blocks us, but the client must get that appointment back
            return appointments.findByIdempotencyKey(idempotencyKey)
                    .map(stored -> replay(stored, request))
                    .orElseThrow(() -> e);
        }
    }

    @Override
    public AppointmentResponse get(UUID appointmentId) {
        return appointments.findById(appointmentId)
                .map(AppointmentServiceImpl::response)
                .orElseThrow(() -> new NotFoundException(ErrorMessages.ENTITY_APPOINTMENT, appointmentId));
    }

    private BookingResult bookNew(String idempotencyKey, BookAppointmentRequest request) {
        // 1. Ids exist
        Dealership dealership = availability.findDealership(request.dealershipId())
                .orElseThrow(() -> new NotFoundException(ErrorMessages.ENTITY_DEALERSHIP, request.dealershipId()));
        int duration = availability.findServiceDurationMinutes(request.serviceTypeId())
                .orElseThrow(() -> new NotFoundException(ErrorMessages.ENTITY_SERVICE_TYPE, request.serviceTypeId()));
        VehicleRef vehicle = appointments.findVehicle(request.vehicleId())
                .orElseThrow(() -> new NotFoundException(ErrorMessages.ENTITY_VEHICLE, request.vehicleId()));
        if (!appointments.customerExists(request.customerId())) {
            throw new NotFoundException(ErrorMessages.ENTITY_CUSTOMER, request.customerId());
        }
        // 2. Owner
        if (!vehicle.customerId().equals(request.customerId())) {
            throw new VehicleOwnerMismatchException();
        }
        // 3.–4. Grid and hours, in the dealership's local time
        ZoneId zone = zoneOf(dealership);
        Instant start = request.startTime().toInstant();
        Instant end = start.plus(Duration.ofMinutes(duration));
        checkGridAndHours(dealership, zone, start, end);
        // 5. The vehicle itself is free (D2): no pair would help otherwise
        if (appointments.vehicleHasOverlap(vehicle.id(), start, end)) {
            throw new VehicleBusyException();
        }

        CandidateResult candidates = selector.select(request.dealershipId(), request.serviceTypeId(),
                vehicle.vehicleTypeId(), start);
        switch (candidates.outcome()) {
            case NOT_SERVICEABLE -> throw new NotServiceableException();
            case NONE_FREE -> throw new BookingConflictException();
            case PAIRS -> {
                // tried below
            }
        }

        List<Pair> pairs = candidates.pairs();
        List<LocalDate> dates = localDates(zone, start, end);
        for (int i = 0; i < pairs.size(); i++) {
            Pair pair = pairs.get(i);
            NewAppointment appointment = new NewAppointment(request.customerId(), vehicle.id(),
                    request.dealershipId(), request.serviceTypeId(), vehicle.vehicleTypeId(),
                    pair.serviceBayId(), pair.technicianId(), start, end, idempotencyKey);
            try {
                StoredAppointment saved = transactions.execute(status -> insertWithEvent(appointment, dates));
                logTry(i, pairs.size(), pair, TryOutcome.BOOKED);
                return new BookingResult(response(saved), false);
            } catch (SlotTakenException e) {
                switch (e.resource()) {
                    case VEHICLE -> {
                        logTry(i, pairs.size(), pair, TryOutcome.VEHICLE_TAKEN);
                        throw new VehicleBusyException();
                    }
                    case BAY -> logTry(i, pairs.size(), pair, TryOutcome.BAY_TAKEN);
                    case TECHNICIAN -> logTry(i, pairs.size(), pair, TryOutcome.TECHNICIAN_TAKEN);
                    case PAIR -> logTry(i, pairs.size(), pair, TryOutcome.DEADLOCK);
                }
            } catch (ResourceGoneException e) {
                logTry(i, pairs.size(), pair, TryOutcome.RESOURCE_GONE);
            } catch (DuplicateIdempotencyKeyException e) {
                logTry(i, pairs.size(), pair, TryOutcome.KEY_TAKEN);
                return appointments.findByIdempotencyKey(idempotencyKey)
                        .map(stored -> replay(stored, request))
                        .orElseThrow(BookingConflictException::new);
            }
        }
        // Lost every pair: if a parallel booking of this vehicle won, say so (D2), not 409
        if (appointments.vehicleHasOverlap(vehicle.id(), start, end)) {
            throw new VehicleBusyException();
        }
        throw new BookingConflictException();
    }

    /** One transaction: both rows commit, or neither does (Spec04 §4, BK-08). */
    private StoredAppointment insertWithEvent(NewAppointment appointment, List<LocalDate> dates) {
        StoredAppointment saved = appointments.insert(appointment);
        outbox.append(OutboxEventTypes.APPOINTMENT, saved.id().toString(), OutboxEventTypes.APPOINTMENT_BOOKED,
                new AppointmentBookedPayload(saved.id(), saved.dealershipId(), dates));
        return saved;
    }

    /**
     * Grid: local start − open is a whole multiple of the slot step (D4). Hours: the local
     * start is not before open and the end is not after local close (same rule as the
     * slot calculator, so every listed slot passes).
     */
    private void checkGridAndHours(Dealership dealership, ZoneId zone, Instant start, Instant end) {
        int step = slots.stepMinutes();
        LocalDateTime local = LocalDateTime.ofInstant(start, zone);
        LocalTime time = local.toLocalTime();
        int minute = time.get(ChronoField.MINUTE_OF_DAY);
        boolean wholeMinute = time.truncatedTo(ChronoUnit.MINUTES).equals(time);
        if (!wholeMinute || (minute - dealership.openMinute()) % step != 0) {
            throw new OffSlotGridException(step);
        }
        Instant close = ZonedDateTime.ofLocal(local.toLocalDate().atStartOfDay().plusMinutes(dealership.closeMinute()),
                zone, null).withLaterOffsetAtOverlap().toInstant();
        if (minute < dealership.openMinute() || end.isAfter(close)) {
            throw new OutsideBusinessHoursException();
        }
    }

    /** Dealership-local dates the window touches; a window over local midnight lists both. */
    private static List<LocalDate> localDates(ZoneId zone, Instant start, Instant end) {
        LocalDate first = LocalDate.ofInstant(start, zone);
        // end is exclusive
        LocalDate last = LocalDate.ofInstant(end.minusNanos(1), zone);
        return first.datesUntil(last.plusDays(1)).toList();
    }

    /** D3: same 5 request fields → the stored appointment again; anything else → 422. */
    private static BookingResult replay(StoredAppointment stored, BookAppointmentRequest request) {
        boolean same = stored.customerId().equals(request.customerId())
                && stored.vehicleId().equals(request.vehicleId())
                && stored.dealershipId().equals(request.dealershipId())
                && stored.serviceTypeId().equals(request.serviceTypeId())
                && stored.start().equals(request.startTime().toInstant());
        if (!same) {
            throw new IdempotencyKeyMismatchException();
        }
        return new BookingResult(response(stored), true);
    }

    private static AppointmentResponse response(StoredAppointment a) {
        return new AppointmentResponse(a.id(), a.status(), a.dealershipId(), a.serviceBayId(), a.technicianId(),
                a.serviceTypeId(), a.vehicleId(), a.start(), a.end());
    }

    /** One line per try: pair and outcome, never customer data (Spec07 §7.2). */
    private static void logTry(int index, int total, Pair pair, TryOutcome outcome) {
        log.info("Booking try {}/{} pair={} outcome={}", index + 1, total, pair, outcome);
    }

    private static ZoneId zoneOf(Dealership dealership) {
        try {
            return ZoneId.of(dealership.timezone());
        } catch (DateTimeException e) {
            // Bad data, not a client error: surfaces as 500
            throw new IllegalStateException(AvailabilityConstants.INVALID_TIME_ZONE.formatted(dealership.timezone()), e);
        }
    }
}
