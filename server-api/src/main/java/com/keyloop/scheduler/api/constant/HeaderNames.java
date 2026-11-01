package com.keyloop.scheduler.api.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * HTTP header names the API reads or writes. nginx sets the request id, so the
 * name must match its config (Spec02 API-16). Clients send the idempotency key on a
 * booking, and a replayed booking is marked in the answer (Spec07 §4).
 *
 * <pre>
 * nginx ──X-Request-Id: abc-123──► RequestIdFilter ──► MDC correlationId=abc-123
 * POST /appointments  Idempotency-Key: k1   →  201 (first)
 * POST /appointments  Idempotency-Key: k1   →  201  Idempotent-Replayed: true
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HeaderNames {

    public static final String REQUEST_ID = "X-Request-Id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
}
