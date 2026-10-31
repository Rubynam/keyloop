# Spec03 — Domain Tables in PostgreSQL + Seed Data

> Keyloop Technical Assessment · Scenario A: The Unified Service Scheduler
> Roadmap: **Phase 3 — Core Features (data layer)** · Builds on [Spec01](Spec01.md), [Spec02](Spec02.md)
> Status: ✅ Implemented · Owner: Dai Nam

---

## 1. Goal

Create the real domain tables in PostgreSQL and fill them with test data. When Spec03 is done:

1. A Flyway migration (`V2__domain_schema.sql`) creates every table from the ERD (DLD §2).
2. The database itself blocks the dangerous mistakes: double booking, a vehicle in the wrong bay pool, a technician or bay from another dealership.
3. A folder `seeding/` loads **exactly 100 rows into every table**, with all columns filled.
4. The seed data is not only "happy path": it **covers the edge cases** listed in §6, so later specs (availability, booking, outbox relay) can be tested against them.

No API endpoints in this spec.

---

## 2. Scope

### In scope
- Flyway migration `server-api/src/main/resources/db/migration/V2__domain_schema.sql`.
- Java enums for the "type" columns (`VehicleType`, `AppointmentStatus`).
- `seeding/` folder: generator, generated SQL, load script, verify script.

### Out of scope
- JPA entities and repositories (next spec, with the booking feature).
- Seeding through the API, or seeding on application start-up.
- Production data / data migration.

---

## 3. Tables

| Table | Rows | Notes |
|---|---|---|
| `dealerships` | 100 | IANA `timezone`, local `open_time` / `close_time` (`24:00` allowed). |
| `service_types` | 100 | Unique `code`, `duration_minutes` between 1 and 1440. |
| `service_bays` | 100 | Belongs to a dealership; `vehicle_type` = pool (D2); `active` flag. |
| `technicians` | 100 | Belongs to a dealership; `active` flag. |
| `technician_skills` | 100 | Technician ↔ service type (many-to-many). |
| `technician_vehicle_types` | 100 | Technician ↔ vehicle type certification. |
| `customers` | 100 | Email and/or phone (at least one). |
| `vehicles` | 100 | Owned by a customer; unique `vin`; `vehicle_type`. |
| `appointments` | 100 | The booking. `[start_time, end_time)`, `status`. |
| `outbox_events` | 100 | From V1; V2 adds `attempts`. |

Table names are plural (`appointments`, like `outbox_events` in V1 and ROADMAP D1). The ERD diagram uses singular names — same tables.

### 3.1 Constraints the database enforces

| ID | Rule | How |
|---|---|---|
| DB-01 | A technician has no two active jobs at the same time | `EXCLUDE USING gist (technician_id WITH =, tstzrange(start,end,'[)') WITH &&) WHERE status <> 'CANCELLED'` |
| DB-02 | A bay has no two active jobs at the same time | same, on `service_bay_id` |
| DB-03 | A vehicle is not in two jobs at the same time | same, on `vehicle_id` (answers ROADMAP §1.4 "Can one vehicle have overlapping appointments?" → **no**) |
| DB-04 | Back-to-back jobs are allowed | half-open range `[)`: 09:00–10:00 and 10:00–11:00 do not overlap |
| DB-05 | The bay is in the vehicle's pool (D2) | FK `(service_bay_id, vehicle_type) → service_bays(id, vehicle_type)` |
| DB-06 | `appointments.vehicle_type` is the real type of the vehicle | FK `(vehicle_id, vehicle_type) → vehicles(id, vehicle_type)` |
| DB-07 | The vehicle belongs to the customer | FK `(vehicle_id, customer_id) → vehicles(id, customer_id)` |
| DB-08 | Bay and technician work at the booked dealership | FKs `(… , dealership_id) → service_bays / technicians` |
| DB-09 | `end_time > start_time`, `updated_at >= created_at` | `CHECK` |
| DB-10 | Email unique, ignoring case; many customers may have no email | `UNIQUE INDEX ON lower(email) WHERE email IS NOT NULL` |
| DB-11 | VIN format: 17 chars, no `I`, `O`, `Q` | `CHECK` regex |
| DB-12 | Phone in E.164 format | `CHECK` regex |

### 3.2 Rules checked by the application, not by the database

These need data from several rows or a time zone, so a simple constraint cannot check them. The seed generator checks them for every appointment, so the seed contains no booking the API would reject:

- The job is fully inside the dealership's opening hours, in the dealership's **local** time.
- The technician has the skill for the service type, and is certified for the vehicle type.
- Technician and bay are `active` (only for new bookings — history may point to inactive resources).
- `COMPLETED` only for jobs that already ended.
- `timezone` is a valid IANA name.

### 3.3 Enum columns — defined in Java, not in the database

`vehicle_type` and `status` are `VARCHAR` with **no** `CHECK (… IN (…))` list. The allowed values live only in Java:

- `server-api/.../domain/VehicleType.java` — `PRIVATE_CAR`, `TRUCK`, `EV`
- `server-api/.../domain/AppointmentStatus.java` — `CONFIRMED`, `CANCELLED`, `COMPLETED`

Why: adding a new value (for example a `MOTORBIKE` pool) is a Java change only, with no migration and no table lock. Entities must map them with `@Enumerated(EnumType.STRING)` (never `ORDINAL`).

Trade-off: the database will accept any string, so only the application protects these columns. One place still depends on a value name: the exclusion constraints use `WHERE status <> 'CANCELLED'`. **Never rename `CANCELLED`** without a migration.

---

## 4. The `seeding/` folder

```
seeding/
├── generate.py           # creates sql/*.sql (Python 3.10+, standard library only)
├── seed.sh               # loads sql/*.sql in ONE transaction, then runs verify.sql
├── verify.sql            # fails if a table does not have exactly 100 rows
└── sql/
    ├── 00_reset.sql      # TRUNCATE all Spec03 tables → seed can run again
    ├── 01_dealerships.sql … 10_outbox_events.sql
```

- **Literal SQL, generated.** Each row is one line; edge-case rows end with `-- edge: <reason>`, so a reviewer can find them with `grep edge:`.
- **Deterministic.** Fixed RNG seed and readable fixed ids: the first digit is the table (`1…` dealership, `2…` bay, `3…` technician, `4…` service type, `5…` customer, `6…` vehicle, `7…` appointment, `8…` outbox), the last digits are the row number. Example: `70000000-0000-4000-8000-000000000050` = appointment 50.
- **"Now" of the dataset is fixed: `2026-10-31 09:00 UTC`** (`ANCHOR` in `generate.py`). "Past", "future", "pending" and "older than 7 days" are relative to this date. Several edge cases use real calendar dates (US DST ends on 2026-11-01, leap day 2028-02-29), so the anchor is not moved automatically.
- **Safe to re-run.** `00_reset.sql` empties the tables first; everything runs in one transaction, so a failure leaves the old data.

### How to run

```bash
docker compose up -d --build --wait     # server-api runs Flyway V2
seeding/seed.sh                         # or: seeding/seed.sh <database>
python3 seeding/generate.py             # only after changing the generator
```

---

## 5. Implementation steps

1. `V2__domain_schema.sql`: tables, FKs, exclusion constraints, indexes, `outbox_events.attempts`.
2. Java enums `VehicleType`, `AppointmentStatus`.
3. `seeding/generate.py`: build data in memory, check every appointment against §3.2, write SQL.
4. `seed.sh` + `verify.sql`.
5. Run on a scratch database, then on the compose database.

---

## 6. Edge cases covered by the seed data

### 6.1 Time and time zones
- **Different UTC offsets:** dealerships in UTC+14 (Kiritimati), UTC−11 (Pago Pago), UTC−10 (Honolulu), +05:45 (Kathmandu), +07 (Ho Chi Minh City), UTC (24/7 hub).
- **Local date ≠ UTC date:** a job at 06:00 in Ho Chi Minh City is 23:00 UTC the day before; in Pago Pago 15:00 is already the next day in UTC. The cache key and the outbox `dates` must use the **local** date.
- **Daylight saving time (DST):** New York changes from EDT to EST on 1 Nov 2026 — the same local 07:00 is 11:00 UTC one day and 12:00 UTC the next. Lord Howe Island moves the clock by only 30 minutes. Auckland has summer time in November (southern hemisphere).
- **Opening and closing time:** one job starts exactly at opening time, one ends exactly at closing time (both valid).
- **Open 24 hours:** `close_time = 24:00`. One job ends exactly at midnight — it touches only one local date.
- **Half-hour and quarter-hour opening times** (07:30, 08:15).
- **Year change in UTC:** a job ends at 19:00 on 31 Dec in New York = 00:00 on 1 Jan 2027 UTC.
- **Leap day:** a job on 29 Feb 2028.
- **Far future:** a job one year ahead (outside a 14-day search range).

### 6.2 Durations
- **Shortest service:** 1 minute.
- **Longest service:** 24 hours — it fits only the 24/7 dealership.
- **Service longer than opening hours:** a 10-hour engine rebuild cannot be booked at an 8–17 dealership, only at the 24/7 one.
- **Durations not on a 15-minute grid** (25, 50, 95 min): a job ends at 10:35 and the next one starts at 10:35.
- **Very short opening window:** a kiosk open only 09:00–10:00.

### 6.3 Overlap and capacity
- **Back-to-back jobs** on the same technician and bay (end = start) are allowed.
- **A cancelled job overlaps** two confirmed jobs on the same technician and bay — allowed, because cancelled rows are ignored.
- **Cancel and re-book:** the same slot is cancelled twice and then booked again.
- **Same vehicle, two services one after another** with different technicians and bays.
- **One customer, three trucks at the same time** — allowed (the rule is per vehicle, not per customer).
- **One pool full, another pool free:** all 3 usable EV bays at the hub are busy 10:00–12:00, but car bays are free.
- **Fully booked day:** the kiosk's only bay is full from 09:00 to 10:00 (the last job takes the last minute).

### 6.4 Resources that cannot be used
- Dealership with **technicians but no bays**, one with **bays but no technicians**, one where **everything is inactive**, and many **new branches with nothing yet**.
- **EV-only** and **truck-only** dealerships.
- Technician with **no skills**, technician with skills but **no vehicle-type certification**.
- **Inactive** technician and bay that still have **completed history** (history must stay valid).
- A **service type that no technician can do** (never bookable).
- An **"all-rounder" technician**: 12 skills and all 3 vehicle types.

### 6.5 Status and lifecycle
- `CONFIRMED`, `CANCELLED`, `COMPLETED` all present (63 / 9 / 28).
- **Confirmed but already in the past** — the job was never closed.
- **Cancelled in the future.**
- **Later today** (2 hours after "now").
- **Rescheduled:** `updated_at` is later than `created_at`, and the outbox event has old and new dates.
- **Booked 5 minutes before the start**, and **booked 13 months in advance.**
- **Idempotency key:** many `NULL`s (allowed), and one at the maximum length (128).

### 6.6 Text and personal data
- Names with **Vietnamese diacritics, apostrophes, ampersand, em dash, CJK characters, an emoji** (4-byte UTF-8), a **single-word name**, and names at the **maximum length** (200 chars).
- The same bay name ("Bay 01") in different dealerships.
- Customer with **email only**, **phone only**; **plus-addressed** email; **upper-case** email (must be unique ignoring case); phone with the **maximum 15 digits**.
- Customer with **no vehicle**, customer with **6 vehicles of all types**, a **company** with a truck fleet.

### 6.7 Vehicles
- **Classic car** (1965) and **next model year** (2027).
- **Electric pickup** (F-150 Lightning) is an `EV`, not a `TRUCK` — it needs a high-voltage bay.
- VIN made only of digits (valid format); make with a hyphen; non-ASCII make/model; one-character model.

### 6.8 Outbox events
- **Pending** events (12) and **processed** events (88).
- Pending after **3 failed attempts**, and a **"poison" event with 25 attempts** (Redis down for a long time).
- Processed **after 2 retries**; processed with **zero delay** (`processed_at = created_at`).
- Processed **more than 7 days ago** → the cleanup job must delete it.
- **Two events with the same `created_at`** — the relay must order by `created_at, id`.
- Event for an **appointment that does not exist** (no FK on the outbox) — the relay must not fail.
- Events about a **dealership, bay or technician**, not only appointments; a **large payload** (60 dates); an **empty `dates` array**; **non-ASCII text** in the payload.

---

## 7. Acceptance Criteria

- [x] `docker compose up -d --build --wait` → Flyway history shows `1 baseline` and `2 domain schema`, both successful; both `server-api` replicas healthy.
- [x] `seeding/seed.sh` → `verify.sql` passes: every table has exactly 100 rows.
- [x] Running `seeding/seed.sh` a second time works (reset + reload).
- [x] Inserting an overlapping job for the same technician fails with `ex_appointments_technician`.
- [x] Putting an EV into a car bay fails with a foreign key error (`fk_appointments_vehicle_type` / `fk_appointments_bay_pool`).
- [x] `grep -c "edge:" seeding/sql/*.sql` shows tagged edge rows in every table.
- [x] No `CHECK (… IN (…))` for enum columns; values are defined in `VehicleType` / `AppointmentStatus`.

---

## 8. Trade-offs & Open Questions

- **Enum values not guarded by the DB** (§3.3) — chosen for easy change. Any other writer (SQL scripts, another service) can insert a wrong value. Accepted: only `server-api` writes these tables.
- **Business hours, skills and certifications** are checked only in the application (§3.2). A DB trigger could do it, but it would duplicate time-zone logic. Accepted.
- **ERD differences:** the ERD shows `outbox_events.id` as `bigint`; V1 already uses `UUID`, kept as is. The ERD diagram should be updated to plural table names and UUID.
- [ ] Buffer time between jobs (ROADMAP §1.4) — not modelled yet; back-to-back is allowed.
- [ ] Should `timezone` be validated in the DB (e.g. a lookup table from `pg_timezone_names`)?
- [ ] Should the seed move with the real date (relative timestamps) instead of a fixed anchor? Fixed is simpler and keeps the DST / leap-day cases on real dates.
