-- ShedLock lock table (created in the "batch" schema via currentSchema).
-- Plain TIMESTAMP on purpose: usingDbTime() writes UTC values without a zone,
-- TIMESTAMPTZ would reinterpret them in each JVM's session time zone.
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
