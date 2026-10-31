-- Domain tables for the scheduler (Spec03). Shapes follow DLD §2 (ERD) and
-- the constraint diagram; anything the DB can guard is guarded here, the rest
-- (skills, business hours) is checked by the booking service.

CREATE TABLE dealerships (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200)  NOT NULL,
    -- IANA zone name; opening hours below are local to it
    timezone    VARCHAR(64)   NOT NULL,
    open_time   TIME          NOT NULL,
    -- '24:00' is allowed so a dealership can be open the whole day
    close_time  TIME          NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_dealerships_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_dealerships_hours CHECK (close_time > open_time)
);

-- vehicle_type / status hold Java enum names (VehicleType, AppointmentStatus).
-- No CHECK list here on purpose: a new value is added in Java only, no migration.

-- Vehicle-type pool shared by bays, technician certifications and vehicles (D2)
CREATE TABLE service_bays (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    dealership_id  UUID         NOT NULL REFERENCES dealerships (id),
    name           VARCHAR(100) NOT NULL,
    vehicle_type   VARCHAR(16)  NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_service_bays_name UNIQUE (dealership_id, name),
    -- Targets for the composite FKs on appointments
    CONSTRAINT uq_service_bays_id_type UNIQUE (id, vehicle_type),
    CONSTRAINT uq_service_bays_id_dealer UNIQUE (id, dealership_id)
);

CREATE INDEX idx_service_bays_pool
    ON service_bays (dealership_id, vehicle_type)
    WHERE active;

CREATE TABLE technicians (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    dealership_id  UUID         NOT NULL REFERENCES dealerships (id),
    name           VARCHAR(200) NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_technicians_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT uq_technicians_id_dealer UNIQUE (id, dealership_id)
);

CREATE INDEX idx_technicians_dealer ON technicians (dealership_id) WHERE active;

CREATE TABLE service_types (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code              VARCHAR(64)  NOT NULL UNIQUE,
    name              VARCHAR(200) NOT NULL,
    duration_minutes  INT          NOT NULL,
    CONSTRAINT ck_service_types_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    -- One job never spans more than a day
    CONSTRAINT ck_service_types_duration CHECK (duration_minutes BETWEEN 1 AND 1440)
);

CREATE TABLE technician_skills (
    technician_id    UUID NOT NULL REFERENCES technicians (id) ON DELETE CASCADE,
    service_type_id  UUID NOT NULL REFERENCES service_types (id) ON DELETE CASCADE,
    PRIMARY KEY (technician_id, service_type_id)
);

-- "Which technicians can do service X" is the availability lookup direction
CREATE INDEX idx_technician_skills_service ON technician_skills (service_type_id);

CREATE TABLE technician_vehicle_types (
    technician_id  UUID        NOT NULL REFERENCES technicians (id) ON DELETE CASCADE,
    vehicle_type   VARCHAR(16) NOT NULL,
    PRIMARY KEY (technician_id, vehicle_type)
);

CREATE TABLE customers (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200)  NOT NULL,
    email       VARCHAR(254),
    -- E.164
    phone       VARCHAR(16),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_customers_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_customers_contact CHECK (email IS NOT NULL OR phone IS NOT NULL),
    CONSTRAINT ck_customers_phone CHECK (phone ~ '^\+[1-9][0-9]{6,14}$')
);

-- Emails are compared case-insensitively; several customers may have none
CREATE UNIQUE INDEX uq_customers_email ON customers (lower(email)) WHERE email IS NOT NULL;

CREATE TABLE vehicles (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id   UUID         NOT NULL REFERENCES customers (id),
    vin           CHAR(17)     NOT NULL UNIQUE,
    make          VARCHAR(64)  NOT NULL,
    model         VARCHAR(64)  NOT NULL,
    year          INT          NOT NULL,
    vehicle_type  VARCHAR(16)  NOT NULL,
    -- ISO 3779: 17 chars, no I / O / Q
    CONSTRAINT ck_vehicles_vin CHECK (vin ~ '^[A-HJ-NPR-Z0-9]{17}$'),
    CONSTRAINT ck_vehicles_year CHECK (year BETWEEN 1900 AND 2100),
    -- Targets for the composite FKs on appointments
    CONSTRAINT uq_vehicles_id_customer UNIQUE (id, customer_id),
    CONSTRAINT uq_vehicles_id_type UNIQUE (id, vehicle_type)
);

CREATE INDEX idx_vehicles_customer ON vehicles (customer_id);

CREATE TABLE appointments (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id      UUID         NOT NULL,
    vehicle_id       UUID         NOT NULL,
    dealership_id    UUID         NOT NULL REFERENCES dealerships (id),
    service_type_id  UUID         NOT NULL REFERENCES service_types (id),
    technician_id    UUID         NOT NULL,
    service_bay_id   UUID         NOT NULL,
    -- Copied from the vehicle so the bay pool can be checked by a FK (D2)
    vehicle_type     VARCHAR(16)  NOT NULL,
    start_time       TIMESTAMPTZ  NOT NULL,
    end_time         TIMESTAMPTZ  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'CONFIRMED',
    idempotency_key  VARCHAR(128) UNIQUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_appointments_window CHECK (end_time > start_time),
    CONSTRAINT ck_appointments_updated CHECK (updated_at >= created_at),
    -- The vehicle belongs to the customer
    CONSTRAINT fk_appointments_vehicle_owner FOREIGN KEY (vehicle_id, customer_id)
        REFERENCES vehicles (id, customer_id),
    -- vehicle_type really is the vehicle's type
    CONSTRAINT fk_appointments_vehicle_type FOREIGN KEY (vehicle_id, vehicle_type)
        REFERENCES vehicles (id, vehicle_type),
    -- The bay is in the vehicle's pool (D2) and at this dealership
    CONSTRAINT fk_appointments_bay_pool FOREIGN KEY (service_bay_id, vehicle_type)
        REFERENCES service_bays (id, vehicle_type),
    CONSTRAINT fk_appointments_bay_dealer FOREIGN KEY (service_bay_id, dealership_id)
        REFERENCES service_bays (id, dealership_id),
    -- The technician works at this dealership
    CONSTRAINT fk_appointments_tech_dealer FOREIGN KEY (technician_id, dealership_id)
        REFERENCES technicians (id, dealership_id),
    -- D1: no double booking. Half-open ranges, so back-to-back jobs are fine.
    CONSTRAINT ex_appointments_technician EXCLUDE USING gist (
        technician_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&
    ) WHERE (status <> 'CANCELLED'),
    CONSTRAINT ex_appointments_bay EXCLUDE USING gist (
        service_bay_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&
    ) WHERE (status <> 'CANCELLED'),
    -- A car cannot be in two places at once
    CONSTRAINT ex_appointments_vehicle EXCLUDE USING gist (
        vehicle_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&
    ) WHERE (status <> 'CANCELLED')
);

CREATE INDEX idx_appointments_dealer_start ON appointments (dealership_id, start_time);
CREATE INDEX idx_appointments_customer ON appointments (customer_id);

-- Relay retry counter (DLD §9 outbox state machine)
ALTER TABLE outbox_events
    ADD COLUMN attempts INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_outbox_events_attempts CHECK (attempts >= 0),
    ADD CONSTRAINT ck_outbox_events_processed CHECK (processed_at IS NULL OR processed_at >= created_at);
