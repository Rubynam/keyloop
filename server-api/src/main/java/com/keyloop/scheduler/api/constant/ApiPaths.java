package com.keyloop.scheduler.api.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * URL paths of the public API. Controllers take their mappings from here, so a
 * version bump or a renamed resource is one edit (CLAUDE.md §2, §3).
 *
 * <pre>
 * API_V1 + AVAILABILITY  →  /api/v1/availability
 * API_V1 + APPOINTMENTS  →  /api/v1/appointments
 * API_V1 + APPOINTMENTS + APPOINTMENT_ID  →  /api/v1/appointments/{appointmentId}
 * API_V1 + PING          →  /api/v1/ping
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiPaths {

    public static final String API_V1 = "/api/v1";
    public static final String AVAILABILITY = "/availability";
    public static final String APPOINTMENTS = "/appointments";
    public static final String APPOINTMENT_ID = "/{appointmentId}";
    public static final String PING = "/ping";
}
