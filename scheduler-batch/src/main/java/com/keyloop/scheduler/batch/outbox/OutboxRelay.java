package com.keyloop.scheduler.batch.outbox;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.keyloop.scheduler.logging.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drains {@code outbox_events} and bumps the Redis availability version keys
 * {@code avail:ver:{dealershipId}:{date}} (Spec04 §4, ROADMAP D1). A plain scheduled
 * worker, not a Batch job (Spec04 Q1).
 *
 * <ul>
 *   <li>One tick = one batch in one transaction, rows taken with
 *       {@code FOR UPDATE SKIP LOCKED}. No ShedLock: several instances get different
 *       rows. A full batch starts the next one at once.</li>
 *   <li>Per row: {@code INCR} + {@code EXPIRE versionKeyTtl} for the union of
 *       {@code dates}, {@code oldDates}, {@code newDates}; empty union = processed.</li>
 *   <li>Redis error: one try plus {@code maxRetries} retries, {@code retryDelay} apart,
 *       then FAILED. Bad payload: FAILED at once, retrying cannot fix it.</li>
 *   <li>All timestamps are PostgreSQL {@code now()}, never the JVM clock.</li>
 *   <li>The relay never changes a booking: FAILED only means the cache may be stale
 *       until its TTL ends.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "scheduler.outbox.relay.enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final OutboxRelayProperties properties;

    public OutboxRelay(JdbcClient jdbc, StringRedisTemplate redis, PlatformTransactionManager transactionManager,
                       JsonMapper json, OutboxRelayProperties properties) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.transaction = new TransactionTemplate(transactionManager);
        this.json = json;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${scheduler.outbox.relay.poll-delay}")
    public void relay() {
        // A full batch means more rows are probably due: go on without waiting for the next tick
        while (tick() == properties.batchSize()) {
            // next batch
        }
    }

    /** One batch in one transaction (Spec04 §4.2). Returns the number of rows taken. */
    public int tick() {
        Integer handled = transaction.execute(status -> {
            List<Row> rows = jdbc.sql("""
                            SELECT id, event_type, payload::text AS payload, attempts
                            FROM outbox_events
                            WHERE processed_at IS NULL AND failed_at IS NULL
                              AND next_attempt_at <= now()
                            ORDER BY created_at, id
                            LIMIT :batchSize
                            FOR UPDATE SKIP LOCKED
                            """)
                    .param("batchSize", properties.batchSize())
                    .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("event_type"),
                            rs.getString("payload"), rs.getInt("attempts")))
                    .list();
            // After the first Redis error the rest of the batch fails the same way: no extra timeouts under row locks
            String redisError = null;
            for (Row row : rows) {
                Payload payload;
                try {
                    payload = parse(row.payload());
                } catch (InvalidPayloadException e) {
                    markInvalid(row, e.getMessage());
                    continue;
                }
                if (redisError == null) {
                    try {
                        bump(payload);
                        markProcessed(row);
                        continue;
                    } catch (DataAccessException e) {
                        redisError = e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                }
                markRetryOrFailed(row, payload, redisError);
            }
            return rows.size();
        });
        return handled == null ? 0 : handled;
    }

    private void bump(Payload payload) {
        for (LocalDate date : payload.dates()) {
            String key = "avail:ver:" + payload.dealershipId() + ":" + date;
            redis.opsForValue().increment(key);
            redis.expire(key, properties.versionKeyTtl());
        }
    }

    private void markProcessed(Row row) {
        jdbc.sql("UPDATE outbox_events SET processed_at = now() WHERE id = :id")
                .param("id", row.id())
                .update();
    }

    /** Spec04 §4.3: attempts 1..maxRetries wait retryDelay; the try after the last retry is FAILED. */
    private void markRetryOrFailed(Row row, Payload payload, String error) {
        int attempts = row.attempts() + 1;
        if (attempts <= properties.maxRetries()) {
            jdbc.sql("""
                            UPDATE outbox_events
                            SET attempts = :attempts,
                                next_attempt_at = now() + :delayMs * interval '1 millisecond',
                                last_error = :error
                            WHERE id = :id
                            """)
                    .param("attempts", attempts)
                    .param("delayMs", properties.retryDelay().toMillis())
                    .param("error", error)
                    .param("id", row.id())
                    .update();
            log.warn("Cache invalidation try {} failed for outbox event {} ({}), next try in {}: {}",
                    attempts, row.id(), row.eventType(), properties.retryDelay(), error);
            return;
        }
        markFailed(row, attempts, error);
        logWithEventId(row, () -> log.error("Cache invalidation failed after {} attempts for outbox event {} ({}, "
                        + "dealership {}, dates {}); availability may be stale until cache TTL expiry. Last error: {}",
                attempts, row.id(), row.eventType(), payload.dealershipId(), payload.dates(), error));
    }

    private void markInvalid(Row row, String reason) {
        markFailed(row, row.attempts() + 1, reason);
        logWithEventId(row, () -> log.error(
                "Outbox event {} ({}) has an invalid payload and was marked FAILED without retry: {}",
                row.id(), row.eventType(), reason));
    }

    private void markFailed(Row row, int attempts, String error) {
        jdbc.sql("""
                        UPDATE outbox_events
                        SET attempts = :attempts, failed_at = now(), last_error = :error
                        WHERE id = :id
                        """)
                .param("attempts", attempts)
                .param("error", error)
                .param("id", row.id())
                .update();
    }

    /** Spec04 §4.6: the operator message carries the event id as the correlation id. */
    private static void logWithEventId(Row row, Runnable logLine) {
        String previous = CorrelationId.current();
        CorrelationId.set(row.id().toString());
        try {
            logLine.run();
        } finally {
            if (previous == null) {
                CorrelationId.clear();
            } else {
                CorrelationId.set(previous);
            }
        }
    }

    /** Spec04 §4.4. Fields other than these are ignored; the aggregate is never loaded. */
    Payload parse(String raw) {
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (JacksonException e) {
            throw new InvalidPayloadException("payload is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidPayloadException("payload is not a JSON object");
        }
        JsonNode dealership = root.get("dealershipId");
        if (dealership == null || !dealership.isString()) {
            throw new InvalidPayloadException("dealershipId is missing");
        }
        UUID dealershipId;
        try {
            dealershipId = UUID.fromString(dealership.asString());
        } catch (IllegalArgumentException e) {
            throw new InvalidPayloadException("dealershipId is not a UUID: " + dealership.asString());
        }
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (String field : List.of("dates", "oldDates", "newDates")) {
            JsonNode list = root.get(field);
            if (list == null || list.isNull()) {
                continue;
            }
            if (!list.isArray()) {
                throw new InvalidPayloadException(field + " is not an array");
            }
            for (JsonNode date : list) {
                try {
                    dates.add(LocalDate.parse(date.asString()));
                } catch (DateTimeParseException | JacksonException e) {
                    throw new InvalidPayloadException(field + " has an invalid date: " + date);
                }
            }
        }
        return new Payload(dealershipId, List.copyOf(dates));
    }

    private record Row(UUID id, String eventType, String payload, int attempts) {}

    /** What the relay reads from a payload: the dealership and the union of its local dates. */
    record Payload(UUID dealershipId, List<LocalDate> dates) {}

    /** Retrying cannot fix it: the event goes to FAILED on the first try. */
    static class InvalidPayloadException extends RuntimeException {
        InvalidPayloadException(String reason) {
            super(reason);
        }
    }
}
