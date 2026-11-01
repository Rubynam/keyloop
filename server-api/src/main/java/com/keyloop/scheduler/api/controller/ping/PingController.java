package com.keyloop.scheduler.api.controller.ping;

import com.keyloop.scheduler.api.constant.ApiPaths;
import com.keyloop.scheduler.api.dto.ping.PingResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Smoke endpoint that proves the web layer is up. nginx and smoke tests call it
 * after a deploy. It touches no database or cache. Spec01 API-07.
 *
 * <pre>
 * GET /api/v1/ping  →  200 {"status":"ok"}
 * </pre>
 */
@RestController
@RequestMapping(ApiPaths.API_V1)
public class PingController {

    private static final String STATUS_OK = "ok";

    /** Always answers {@code ok} while the app can serve HTTP. */
    @GetMapping(ApiPaths.PING)
    public PingResponse ping() {
        return new PingResponse(STATUS_OK);
    }
}
