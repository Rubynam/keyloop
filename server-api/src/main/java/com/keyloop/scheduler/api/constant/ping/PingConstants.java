package com.keyloop.scheduler.api.constant.ping;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Fixed values of the smoke endpoint. nginx and smoke tests check this text after a
 * deploy, so do not change it. Spec01 API-07.
 *
 * <pre>
 * GET /api/v1/ping  →  200 {"status": STATUS_OK}  =  {"status":"ok"}
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PingConstants {

    public static final String STATUS_OK = "ok";
}
