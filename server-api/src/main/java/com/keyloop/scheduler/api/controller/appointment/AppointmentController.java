package com.keyloop.scheduler.api.controller.appointment;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.keyloop.scheduler.api.constant.ApiPaths;
import com.keyloop.scheduler.api.constant.HeaderNames;
import com.keyloop.scheduler.api.constant.appointment.AppointmentConstants;
import com.keyloop.scheduler.api.dto.appointment.AppointmentResponse;
import com.keyloop.scheduler.api.dto.appointment.BookAppointmentRequest;
import com.keyloop.scheduler.api.dto.appointment.BookingResult;
import com.keyloop.scheduler.api.service.appointment.AppointmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * HTTP edge of the booking. A customer sends the slot they picked from availability,
 * and gets back the appointment with its bay and technician. Bad input is 400 here,
 * before any database read; every error body is built by {@code ApiExceptionHandler}.
 * No class-level {@code @Validated}: it would switch the header checks to AOP and a
 * {@code ConstraintViolationException}; Spring MVC's built-in method validation gives
 * the {@code HandlerMethodValidationException} that Spec07 §6 maps. Spec07 §4, §5.2.
 *
 * <pre>
 * POST /api/v1/appointments   Idempotency-Key: k1
 * { customerId, vehicleId, dealershipId, serviceTypeId, startTime: 2026-11-02T09:00+07:00 }
 *
 * 201  Location: /api/v1/appointments/A1
 * { id: A1, status: CONFIRMED, serviceBayId: B2, technicianId: T2,
 *   startTime: 2026-11-02T02:00:00Z, endTime: 2026-11-02T03:00:00Z, ... }
 * </pre>
 */
@RestController
@RequestMapping(ApiPaths.API_V1)
public class AppointmentController {

    private final AppointmentService service;

    public AppointmentController(AppointmentService service) {
        this.service = service;
    }

    /**
     * Books one appointment. A retry with the same key and body gets the same
     * appointment back, marked with {@code Idempotent-Replayed: true}.
     *
     * <pre>
     * first call  → 201  Location: /api/v1/appointments/A1
     * same key    → 201  Location: /api/v1/appointments/A1   Idempotent-Replayed: true
     * </pre>
     */
    @PostMapping(ApiPaths.APPOINTMENTS)
    public ResponseEntity<AppointmentResponse> book(
            @RequestHeader(HeaderNames.IDEMPOTENCY_KEY)
            @NotBlank @Size(max = AppointmentConstants.IDEMPOTENCY_KEY_MAX) String idempotencyKey,
            @Valid @RequestBody BookAppointmentRequest request) {
        BookingResult result = service.book(idempotencyKey, request);
        URI location = UriComponentsBuilder.fromPath(ApiPaths.API_V1 + ApiPaths.APPOINTMENTS)
                .pathSegment(result.appointment().id().toString())
                .build()
                .toUri();
        ResponseEntity.BodyBuilder response = ResponseEntity.created(location);
        if (result.replayed()) {
            response.header(HeaderNames.IDEMPOTENT_REPLAYED, Boolean.TRUE.toString());
        }
        return response.body(result.appointment());
    }

    /**
     * Reads one appointment by id. This is the resource the {@code Location} header of
     * {@link #book} points to. Any status is returned, cancelled too.
     *
     * <pre>
     * GET /api/v1/appointments/A1      → 200 { id: A1, status: CONFIRMED, serviceBayId: B2, ... }
     * GET /api/v1/appointments/unknown → 404
     * GET /api/v1/appointments/abc     → 400  errors[{field: appointmentId}]
     * </pre>
     */
    @GetMapping(ApiPaths.APPOINTMENTS + ApiPaths.APPOINTMENT_ID)
    public AppointmentResponse get(@PathVariable UUID appointmentId) {
        return service.get(appointmentId);
    }
}
