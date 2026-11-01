package com.keyloop.scheduler.api.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * HTTP header names the API reads or writes. nginx sets the request id, so the
 * name must match its config (Spec02 API-16).
 *
 * <pre>
 * nginx ──X-Request-Id: abc-123──► RequestIdFilter ──► MDC correlationId=abc-123
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HeaderNames {

    public static final String REQUEST_ID = "X-Request-Id";
}
