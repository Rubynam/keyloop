package com.keyloop.scheduler.batch.service.outbox.impl;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
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
 *   <li>One row at a time, in two transactions. TX A takes the next due row with
 *       {@code FOR UPDATE SKIP LOCKED} and counts the try, then commits, so a try counts
 *       even when the JVM dies during the work. TX B locks the row again by id and
 *       claimed try, and does the work under that lock. No ShedLock: several instances
 *       get different rows.</li>
 *   <li>Per row: {@code INCR} + {@code EXPIRE versionKeyTtl} for the union of
 *       {@code dates}, {@code oldDates}, {@code newDates}; empty union = processed.</li>
 *   <li>Redis or other runtime error: one try plus {@code maxRetries} retries,
 *       {@code retryDelay} apart, then FAILED; the tick stops. A row whose tries all
 *       started but never finished is FAILED at the next claim. Bad payload: FAILED at
 *       once, retrying cannot fix it.</li>
 *   <li>All timestamps are PostgreSQL {@code now()}, never the JVM clock.</li>
 *   <li>The relay never changes a booking: FAILED only means the cache may be stale
 *       until its TTL ends.</li>
 * </ul>
 *
 * <pre>
 * TX A  lockNextDue → E1 (attempts 0) → countTry → 1 → COMMIT
 * TX B  relock(E1, 1) → parse → INCR avail:ver:D1:11-05 → markProcessed → COMMIT
 * </pre>
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
        int claimed = 0;
        for (int i = 0; i < properties.batchSize(); i++) {
            Optional<Claim> claim = claimNext();
            if (claim.isEmpty()) {
                break;
            }
            claimed++;
            if (claim.get().exhausted()) {
                continue;
            }
            if (!work(claim.get().event())) {
                // After a Redis error the next rows would likely wait for the same timeout: leave them to the next tick
                break;
            }
        }
        return claimed;
    }

    /**
     * TX A: locks the next due row and counts the try, or ends it as FAILED when all its
     * tries were used up without finishing. Commits before any work starts.
     *
     * @return empty when nothing is due; otherwise the row with its claimed {@code attempts}
     */
    Optional<Claim> claimNext() {
        Optional<Claim> claim = transaction.execute(status -> {
            Optional<OutboxEvent> next = events.lockNextDue();
            if (next.isEmpty()) {
                return Optional.<Claim>empty();
            }
            OutboxEvent row = next.get();
            if (row.attempts() >= properties.maxRetries() + 1) {
                events.markFailed(row.id(), RelayConstants.CLAIM_EXHAUSTED_ERROR);
                return Optional.of(new Claim(row, true));
            }
            int attempts = events.countTry(row.id(), properties.retryDelay());
            return Optional.of(new Claim(new OutboxEvent(row.id(), row.eventType(), row.payload(), attempts), false));
        });
        // Logged only after COMMIT, so the ERROR never names a row that is still pending
        claim.filter(Claim::exhausted).map(Claim::event).ifPresent(row -> logWithEventId(row, () ->
                log.error(RelayConstants.CLAIM_EXHAUSTED_LOG, row.id(), row.eventType(), row.attempts())));
        return claim;
    }

    /**
     * TX B: re-locks the claimed row and works on it (parse → bump → processed, or the
     * §4.3 failure path). An {@link Error} is not caught: it rolls TX B back and leaves
     * the try counted.
     *
     * @param claimed the row as TX A left it; {@code attempts} is the claimed try number
     * @return {@code false} when a Redis or other runtime error ended the work, so the tick stops
     */
    boolean work(OutboxEvent claimed) {
        Boolean goOn = transaction.execute(status -> {
            if (!events.relock(claimed.id(), claimed.attempts())) {
                logWithEventId(claimed, () -> log.warn(RelayConstants.RELOCK_SKIPPED_LOG,
                        claimed.id(), claimed.eventType(), claimed.attempts()));
                status.setRollbackOnly();
                return true;
            }
            Payload payload;
            try {
                payload = parse(claimed.payload());
            } catch (InvalidPayloadException e) {
                markInvalid(claimed, e.getMessage());
                return true;
            }
            try {
                bump(payload);
            } catch (RuntimeException e) {
                markRetryOrFailed(claimed, payload,
                        RelayConstants.REDIS_ERROR.formatted(e.getClass().getSimpleName(), e.getMessage()));
                return false;
            }
            // Only after every date is bumped (story §6: at-least-once)
            events.markProcessed(claimed.id());
            return true;
        });
        return !Boolean.FALSE.equals(goOn);
    }

    private void bump(Payload payload) {
        for (LocalDate date : payload.dates()) {
            versions.bump(payload.dealershipId(), date);
        }
    }

    /** Spec04 §4.3: tries 1..maxRetries wait retryDelay; the try after the last retry is FAILED. */
    private void markRetryOrFailed(OutboxEvent row, Payload payload, String error) {
        int attempts = row.attempts();
        if (attempts <= properties.maxRetries()) {
            events.scheduleRetry(row.id(), properties.retryDelay(), error);
            log.warn(RelayConstants.RETRY_SCHEDULED_LOG, attempts, row.id(), row.eventType(), properties.retryDelay(), error);
            return;
        }
        events.markFailed(row.id(), error);
        logWithEventId(row, () -> log.error(RelayConstants.FAILED_AFTER_RETRIES_LOG,
                attempts, row.id(), row.eventType(), payload.dealershipId(), payload.dates(), error));
    }

    private void markInvalid(OutboxEvent row, String reason) {
        events.markFailed(row.id(), reason);
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

    /** Result of TX A: the row with its claimed try, or a row just ended as exhausted. */
    record Claim(OutboxEvent event, boolean exhausted) {}
}
