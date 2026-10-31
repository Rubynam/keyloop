-- Domain tables for the scheduler (Spec03). Shapes follow DLD §2 (ERD) and
-- the constraint diagram; anything the DB can guard is guarded here, the rest
-- (business hours, active flags) is checked by the booking service.
--
-- Every id is a UUIDv7 (time-ordered, Postgres 18 uuidv7()): new rows land at
-- the right edge of the primary-key index instead of random pages.

CREATE TABLE dealerships (
    id          UUID          PRIMARY KEY DEFAULT uuidv7(),
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

-- Lookup of vehicle categories (D2). A row, not an enum: a new category is
-- data, not a code change or a migration.
CREATE TABLE vehicle_types (
    id    UUID          PRIMARY KEY DEFAULT uuidv7(),
    code  VARCHAR(32)   NOT NULL UNIQUE,
    name  VARCHAR(100)  NOT NULL,
    CONSTRAINT ck_vehicle_types_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$')
);

-- Catalogue of models: "Vios" is a gasoline-powered car made by TOYOTA
CREATE TABLE car_info (
    id               UUID          PRIMARY KEY DEFAULT uuidv7(),
    name             VARCHAR(100)  NOT NULL,
    vehicle_type_id  UUID          NOT NULL REFERENCES vehicle_types (id),
    brand            VARCHAR(64)   NOT NULL,
    CONSTRAINT ck_car_info_name_not_blank CHECK (btrim(name) <> ''),
    -- Same name may exist as a gasoline and a hybrid version of one brand
    CONSTRAINT uq_car_info_model UNIQUE (brand, name, vehicle_type_id),
    -- Target for vehicles' composite FK
    CONSTRAINT uq_car_info_id_type UNIQUE (id, vehicle_type_id)
);

CREATE TABLE service_bays (
    id             UUID         PRIMARY KEY DEFAULT uuidv7(),
    dealership_id  UUID         NOT NULL REFERENCES dealerships (id),
    name           VARCHAR(100) NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_service_bays_name UNIQUE (dealership_id, name),
    CONSTRAINT uq_service_bays_id_dealer UNIQUE (id, dealership_id)
);

CREATE INDEX idx_service_bays_dealer ON service_bays (dealership_id) WHERE active;

-- Which vehicle types a bay can take (D2, M:N): a standard bay takes
-- gasoline, diesel and hybrid cars; a high-voltage bay takes EVs and hybrids
CREATE TABLE service_bay_vehicle_types (
    service_bay_id   UUID NOT NULL REFERENCES service_bays (id) ON DELETE CASCADE,
    vehicle_type_id  UUID NOT NULL REFERENCES vehicle_types (id),
    PRIMARY KEY (service_bay_id, vehicle_type_id)
);

-- Availability looks up "bays for this vehicle type"
CREATE INDEX idx_service_bay_vehicle_types_type ON service_bay_vehicle_types (vehicle_type_id);

CREATE TABLE technicians (
    id             UUID         PRIMARY KEY DEFAULT uuidv7(),
    dealership_id  UUID         NOT NULL REFERENCES dealerships (id),
    name           VARCHAR(200) NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_technicians_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT uq_technicians_id_dealer UNIQUE (id, dealership_id)
);

CREATE INDEX idx_technicians_dealer ON technicians (dealership_id) WHERE active;

CREATE TABLE service_types (
    id                UUID         PRIMARY KEY DEFAULT uuidv7(),
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
    technician_id    UUID NOT NULL REFERENCES technicians (id) ON DELETE CASCADE,
    vehicle_type_id  UUID NOT NULL REFERENCES vehicle_types (id),
    PRIMARY KEY (technician_id, vehicle_type_id)
);

CREATE INDEX idx_technician_vehicle_types_type ON technician_vehicle_types (vehicle_type_id);

CREATE TABLE customers (
    id          UUID          PRIMARY KEY DEFAULT uuidv7(),
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
    id               UUID     PRIMARY KEY DEFAULT uuidv7(),
    customer_id      UUID     NOT NULL REFERENCES customers (id),
    car_info_id      UUID     NOT NULL,
    -- Copy of car_info.vehicle_type_id, kept equal by the FK below, so
    -- appointments can check the type with a plain composite FK
    vehicle_type_id  UUID     NOT NULL,
    vin              CHAR(17) NOT NULL UNIQUE,
    year             INT      NOT NULL,
    -- ISO 3779: 17 chars, no I / O / Q
    CONSTRAINT ck_vehicles_vin CHECK (vin ~ '^[A-HJ-NPR-Z0-9]{17}$'),
    CONSTRAINT ck_vehicles_year CHECK (year BETWEEN 1900 AND 2100),
    CONSTRAINT fk_vehicles_car_info FOREIGN KEY (car_info_id, vehicle_type_id)
        REFERENCES car_info (id, vehicle_type_id),
    -- Targets for the composite FKs on appointments
    CONSTRAINT uq_vehicles_id_customer UNIQUE (id, customer_id),
    CONSTRAINT uq_vehicles_id_type UNIQUE (id, vehicle_type_id)
);

CREATE INDEX idx_vehicles_customer ON vehicles (customer_id);
CREATE INDEX idx_vehicles_car_info ON vehicles (car_info_id);

-- status holds AppointmentStatus enum names (Java); no CHECK list on purpose
CREATE TABLE appointments (
    id               UUID         PRIMARY KEY DEFAULT uuidv7(),
    customer_id      UUID         NOT NULL,
    vehicle_id       UUID         NOT NULL,
    dealership_id    UUID         NOT NULL REFERENCES dealerships (id),
    service_type_id  UUID         NOT NULL REFERENCES service_types (id),
    technician_id    UUID         NOT NULL,
    service_bay_id   UUID         NOT NULL,
    -- Copied from the vehicle so bay / technician fit can be checked by FKs (D2)
    vehicle_type_id  UUID         NOT NULL,
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
    -- vehicle_type_id really is the vehicle's type
    CONSTRAINT fk_appointments_vehicle_type FOREIGN KEY (vehicle_id, vehicle_type_id)
        REFERENCES vehicles (id, vehicle_type_id),
    -- The bay can take this vehicle type and is at this dealership
    CONSTRAINT fk_appointments_bay_type FOREIGN KEY (service_bay_id, vehicle_type_id)
        REFERENCES service_bay_vehicle_types (service_bay_id, vehicle_type_id),
    CONSTRAINT fk_appointments_bay_dealer FOREIGN KEY (service_bay_id, dealership_id)
        REFERENCES service_bays (id, dealership_id),
    -- The technician is qualified: certified for the type, skilled for the
    -- service, and works at this dealership
    CONSTRAINT fk_appointments_tech_type FOREIGN KEY (technician_id, vehicle_type_id)
        REFERENCES technician_vehicle_types (technician_id, vehicle_type_id),
    CONSTRAINT fk_appointments_tech_skill FOREIGN KEY (technician_id, service_type_id)
        REFERENCES technician_skills (technician_id, service_type_id),
    CONSTRAINT fk_appointments_tech_dealer FOREIGN KEY (technician_id, dealership_id)
        REFERENCES technicians (id, dealership_id),
    -- D1: no double booking. Half-open ranges, so back-to-back jobs are fine.
    -- 'CANCELLED' must match AppointmentStatus.CANCELLED.
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

-- V1 created the outbox with random (v4) ids; new rows get time-ordered ids.
-- Relay retry counter: DLD §9 outbox state machine.
ALTER TABLE outbox_events
    ALTER COLUMN id SET DEFAULT uuidv7(),
    ADD COLUMN attempts INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_outbox_events_attempts CHECK (attempts >= 0),
    ADD CONSTRAINT ck_outbox_events_processed CHECK (processed_at IS NULL OR processed_at >= created_at);
