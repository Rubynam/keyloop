-- Free-text place of a bay inside the dealership ("Floor 2, zone B"), set
-- from the admin tool so a workshop can grow zone by zone. Nullable: bays
-- created before this column have no location yet.
ALTER TABLE service_bays
    ADD COLUMN location TEXT,
    ADD CONSTRAINT ck_service_bays_location
        CHECK (location IS NULL OR (btrim(location) <> '' AND length(location) <= 500));
