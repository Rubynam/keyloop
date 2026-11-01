package com.keyloop.scheduler.api.dto.ping;

/** Body of {@code GET /api/v1/ping}: {@code {"status":"ok"}}. */
public record PingResponse(String status) {
}
