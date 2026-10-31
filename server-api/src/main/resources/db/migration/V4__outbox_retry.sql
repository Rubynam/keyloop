-- Relay retry schedule and terminal failure (Spec04 §4.3, §5).
-- One try + max 3 retries, 2 s apart; then the event is FAILED, never retried.
ALTER TABLE outbox_events
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN failed_at       TIMESTAMPTZ,
    ADD COLUMN last_error      TEXT,
    -- An event ends either processed or failed, never both
    ADD CONSTRAINT ck_outbox_events_terminal CHECK (processed_at IS NULL OR failed_at IS NULL),
    ADD CONSTRAINT ck_outbox_events_failed   CHECK (failed_at IS NULL OR failed_at >= created_at);

-- The relay scans only due, unfinished events
DROP INDEX idx_outbox_events_pending;
CREATE INDEX idx_outbox_events_pending
    ON outbox_events (next_attempt_at)
    WHERE processed_at IS NULL AND failed_at IS NULL;
