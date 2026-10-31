-- Also created by the Postgres init script; repeated here so the schema is
-- correct on any database, not only on a fresh docker volume.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Transactional outbox (ROADMAP D1). Rows are written in the same transaction
-- as the appointment change and drained by scheduler-batch.
CREATE TABLE outbox_events (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at   TIMESTAMPTZ
);

-- The relay only ever scans pending events, oldest first
CREATE INDEX idx_outbox_events_pending
    ON outbox_events (created_at)
    WHERE processed_at IS NULL;
