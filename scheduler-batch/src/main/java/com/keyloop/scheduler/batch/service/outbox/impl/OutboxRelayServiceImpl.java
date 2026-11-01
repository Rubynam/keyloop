package com.keyloop.scheduler.batch.service.outbox.impl;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.keyloop.scheduler.batch.config.outbox.OutboxRelayProperties;
import com.keyloop.scheduler.batch.constant.outbox.RelayConstants;
import com.keyloop.scheduler.batch.exception.outbox.InvalidPayloadException;
import com.keyloop.scheduler.batch.model.outbox.OutboxEvent;
import com.keyloop.scheduler.batch.repository.outbox.OutboxEventRepository;
import com.keyloop.scheduler.batch.service.outbox.OutboxRelayService;
import com.keyloop.scheduler.batch.store.outbox.AvailabilityVersionStore;
import com.keyloop.scheduler.logging.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link OutboxRelayService} with the Spec04 §4 policy. A plain scheduled worker, not a
 * Batch job (Spec04 Q1).
 *
 * <ul>
 *   <li>One tick = one batch in one transaction, rows taken with
 *       {@code FOR UPDATE SKIP LOCKED}. No ShedLock: several instances get different
 *       rows.</li>
 *   <li>Per row: {@code INCR} + {@code EXPIRE versionKeyTtl} for the union of
 *       {@code dates}, {@code oldDates}, {@code newDates}; empty union = processed.</li>
 *   <li>Redis error: one try plus {@code maxRetries} retries, {@code retryDelay} apart,
 *       then FAILED. Bad payload: FAILED at once, retrying cannot fix it.</li>
 *   <li>All timestamps are PostgreSQL {@code now()}, never the JVM clock.</li>
 *   <li>The relay never changes a booking: FAILED only means the cache may be stale
 *       until its TTL ends.</li>
 * </ul>
 */
@Service
public class OutboxRelayServiceImpl implements OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayServiceImpl.class);

    private static final List<String> DATE_FIELDS =
            List.of(RelayConstants.FIELD_DATES, RelayConstants.FIELD_OLD_DATES, RelayConstants.FIELD_NEW_DATES);

    private final OutboxEventRepository events;
    private final AvailabilityVersionStore versions;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final OutboxRelayProperties properties;

    public OutboxRelayServiceImpl(OutboxEventRepository events, AvailabilityVersionStore versions,
                                  PlatformTransactionManager transactionManager, JsonMapper json,
                                  OutboxRelayProperties properties) {
        this.events = events;
        this.versions = versions;
        this.transaction = new TransactionTemplate(transactionManager);
        this.json = json;
        this.properties = properties;
    }

    @Override
    public int tick() {
        Integer handled = transaction.execute(status -> {
            List<OutboxEvent> rows = events.lockDueBatch(properties.batchSize());
            // After the first Redis error the rest of the batch fails the same way: no extra timeouts under row locks
            String redisError = null;
            for (OutboxEvent row : rows) {
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
                        events.markProcessed(row.id());
                        continue;
                    } catch (DataAccessException e) {
                        redisError = RelayConstants.REDIS_ERROR.formatted(e.getClass().getSimpleName(), e.getMessage());
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
            versions.bump(payload.dealershipId(), date);
        }
    }

    /** Spec04 §4.3: attempts 1..maxRetries wait retryDelay; the try after the last retry is FAILED. */
    private void markRetryOrFailed(OutboxEvent row, Payload payload, String error) {
        int attempts = row.attempts() + 1;
        if (attempts <= properties.maxRetries()) {
            events.scheduleRetry(row.id(), attempts, properties.retryDelay(), error);
            log.warn("Cache invalidation try {} failed for outbox event {} ({}), next try in {}: {}",
                    attempts, row.id(), row.eventType(), properties.retryDelay(), error);
            return;
        }
        events.markFailed(row.id(), attempts, error);
        logWithEventId(row, () -> log.error(RelayConstants.FAILED_AFTER_RETRIES_LOG,
                attempts, row.id(), row.eventType(), payload.dealershipId(), payload.dates(), error));
    }

    private void markInvalid(OutboxEvent row, String reason) {
        events.markFailed(row.id(), row.attempts() + 1, reason);
        logWithEventId(row, () -> log.error(RelayConstants.INVALID_PAYLOAD_LOG, row.id(), row.eventType(), reason));
    }

    /** Spec04 §4.6: the operator message carries the event id as the correlation id. */
    private static void logWithEventId(OutboxEvent row, Runnable logLine) {
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
            throw new InvalidPayloadException(RelayConstants.PAYLOAD_NOT_JSON);
        }
        if (root == null || !root.isObject()) {
            throw new InvalidPayloadException(RelayConstants.PAYLOAD_NOT_OBJECT);
        }
        JsonNode dealership = root.get(RelayConstants.FIELD_DEALERSHIP_ID);
        if (dealership == null || !dealership.isString()) {
            throw new InvalidPayloadException(RelayConstants.DEALERSHIP_MISSING);
        }
        UUID dealershipId;
        try {
            dealershipId = UUID.fromString(dealership.asString());
        } catch (IllegalArgumentException e) {
            throw new InvalidPayloadException(RelayConstants.DEALERSHIP_NOT_UUID.formatted(dealership.asString()));
        }
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (String field : DATE_FIELDS) {
            JsonNode list = root.get(field);
            if (list == null || list.isNull()) {
                continue;
            }
            if (!list.isArray()) {
                throw new InvalidPayloadException(RelayConstants.FIELD_NOT_ARRAY.formatted(field));
            }
            for (JsonNode date : list) {
                try {
                    dates.add(LocalDate.parse(date.asString()));
                } catch (DateTimeParseException | JacksonException e) {
                    throw new InvalidPayloadException(RelayConstants.FIELD_INVALID_DATE.formatted(field, date));
                }
            }
        }
        return new Payload(dealershipId, List.copyOf(dates));
    }

    /** What the relay reads from a payload: the dealership and the union of its local dates. */
    record Payload(UUID dealershipId, List<LocalDate> dates) {}
}
