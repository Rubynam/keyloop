# Spec03 — Domain Tables in PostgreSQL + Seed Data

> Keyloop Technical Assessment · Scenario A: The Unified Service Scheduler
> Roadmap: **Phase 3 — Core Features (data layer)** · Builds on [Spec01](Spec01.md), [Spec02](Spec02.md)
> Status: ✅ Implemented · Owner: Dai Nam

---

## 1. Goal

Create the real domain tables in PostgreSQL and fill them with test data. When Spec03 is done:

1. A Flyway migration (`V2__domain_schema.sql`) creates every table from the ERD (DLD §2).
2. Every id is a **UUIDv7** (time-ordered).
3. Vehicle types are **data**, not code: a `vehicle_types` lookup table and a `car_info` model catalogue (e.g. *Vios — gasoline-powered car — TOYOTA*).
4. The database itself blocks the dangerous mistakes: double booking, a vehicle in a bay that cannot take it, a technician without the skill or certification, resources from another dealership.
5. A folder `seeding/` fills every table with test data, **100 rows per main table**, all columns filled.
6. The seed data is not only "happy path": it **covers the edge cases** listed in §6, so later specs (availability, booking, outbox relay) can be tested against them.

No API endpoints in this spec.

---

## 2. Scope

### In scope
- Flyway migration `server-api/src/main/resources/db/migration/V2__domain_schema.sql`.
- Java enum `AppointmentStatus`.
- `seeding/` folder: generator, generated SQL, load script, verify script.
- Updating the design: ERD, constraints, availability / booking sequences, Redis keys, API contract, ROADMAP D2.

### Out of scope
- JPA entities and repositories (next spec, with the booking feature).
- Seeding through the API, or seeding on application start-up.
- Production data / data migration.

---

## 3. Tables

| Table | Rows in seed | Notes |
|---|---|---|
| `dealerships` | 100 | IANA `timezone`, local `open_time` / `close_time` (`24:00` allowed). |
| `vehicle_types` | 8 | **Lookup**: `code` + `name`, e.g. `GASOLINE_CAR` "Gasoline-powered car". Small on purpose (a real list, not invented rows). |
| `car_info` | 100 | Model catalogue: `name` ("Vios"), `vehicle_type_id`, `brand` ("TOYOTA"). |
| `service_types` | 100 | Unique `code`, `duration_minutes` between 1 and 1440. |
| `service_bays` | 100 | Belongs to a dealership; `active` flag. No type column — see the next row. |
| `service_bay_vehicle_types` | 258 | Bay ↔ vehicle type (M:N): which types a bay can take. |
| `technicians` | 100 | Belongs to a dealership; `active` flag. |
| `technician_skills` | 100 | Technician ↔ service type (M:N). |
| `technician_vehicle_types` | 193 | Technician ↔ vehicle type certification (M:N). |
| `customers` | 100 | Email and/or phone (at least one). |
| `vehicles` | 100 | Owned by a customer; `car_info_id` (the model); unique `vin`; `year`. |
| `appointments` | 100 | The booking. `[start_time, end_time)`, `status`. |
| `outbox_events` | 100 | From V1; V2 adds `attempts` and the UUIDv7 default. |

**Row-count rule:** main tables and `car_info` have exactly 100 rows. `vehicle_types` is a small lookup list. Join tables have as many rows as the data needs (at least 100) — forcing exactly 100 would make bays and technicians unrealistic.

Table names are plural (`appointments`, like `outbox_events` in V1 and ROADMAP D1).

### 3.1 Ids: UUIDv7

- Every `id` column has `DEFAULT uuidv7()` (built into PostgreSQL 18). V2 also switches `outbox_events.id` from `gen_random_uuid()` (v4) to `uuidv7()`.
- Format: the first 48 bits are the Unix time in milliseconds, then version `7`. Example: `019a3d24-9900-7bc9-8435-7f850bf3d0a7`.
- Why: ids grow with time, so new rows go to the end of the primary-key index (less page splitting than random v4), and `ORDER BY id` is close to creation order.
- The seed generator builds v7 ids itself (Python 3.10 has no `uuid7()`), using the row's own time: `created_at` when the table has one, otherwise a fixed seed time. So in the seed, too, id order = creation order.

### 3.2 Vehicle types (ROADMAP D2)

```
vehicle_types  1 ── n  car_info  1 ── n  vehicles
      │ n                                    (vehicle_type_id = copy of car_info's)
      ├── m  service_bay_vehicle_types  n ── service_bays
      └── m  technician_vehicle_types   n ── technicians
```

- A vehicle's type comes from its model (`car_info`). `vehicles.vehicle_type_id` is a copy, kept equal by a composite FK, so `appointments` can check the type with plain FKs.
- Bays take **several** types: standard bay = gasoline + diesel + hybrid cars; high-voltage (EV) bay = electric + hybrid cars; heavy bay = diesel trucks; heavy HV bay = diesel + electric trucks.
- Adding a new category (for example `MOTORBIKE`) is an `INSERT`, not a code change or migration.

### 3.3 Constraints the database enforces

| ID | Rule | How |
|---|---|---|
| DB-01 | A technician has no two active jobs at the same time | `EXCLUDE USING gist (technician_id WITH =, tstzrange(start,end,'[)') WITH &&) WHERE status <> 'CANCELLED'` |
| DB-02 | A bay has no two active jobs at the same time | same, on `service_bay_id` |
| DB-03 | A vehicle is not in two jobs at the same time | same, on `vehicle_id` (answers ROADMAP §1.4) |
| DB-04 | Back-to-back jobs are allowed | half-open range `[)`: 09:00–10:00 and 10:00–11:00 do not overlap |
| DB-05 | The bay can take the vehicle's type | FK `(service_bay_id, vehicle_type_id) → service_bay_vehicle_types` |
| DB-06 | The technician is certified for the vehicle's type | FK `(technician_id, vehicle_type_id) → technician_vehicle_types` |
| DB-07 | The technician has the skill for the service | FK `(technician_id, service_type_id) → technician_skills` |
| DB-08 | `appointments.vehicle_type_id` is the real type of the vehicle | FK `(vehicle_id, vehicle_type_id) → vehicles`, and `vehicles` → `car_info` the same way |
| DB-09 | The vehicle belongs to the customer | FK `(vehicle_id, customer_id) → vehicles(id, customer_id)` |
| DB-10 | Bay and technician work at the booked dealership | FKs `(… , dealership_id) → service_bays / technicians` |
| DB-11 | `end_time > start_time`, `updated_at >= created_at` | `CHECK` |
| DB-12 | Email unique, ignoring case; many customers may have no email | `UNIQUE INDEX ON lower(email) WHERE email IS NOT NULL` |
| DB-13 | VIN format: 17 chars, no `I`, `O`, `Q` | `CHECK` regex |
| DB-14 | Phone in E.164 format | `CHECK` regex |
| DB-15 | One model per brand + name + type | `UNIQUE (brand, name, vehicle_type_id)` on `car_info` |

### 3.4 Rules checked by the application, not by the database

These need data from several rows or a time zone, so a simple constraint cannot check them. The seed generator checks them for every appointment, so the seed contains no booking the API would reject:

- The job is fully inside the dealership's opening hours, in the dealership's **local** time.
- Technician and bay are `active` (only for new bookings — history may point to inactive resources).
- `COMPLETED` only for jobs that already ended.
- `timezone` is a valid IANA name.

### 3.5 `status` — a Java enum

`appointments.status` is `VARCHAR` with **no** `CHECK (… IN (…))` list; the values live in `server-api/.../domain/AppointmentStatus.java` (`CONFIRMED`, `CANCELLED`, `COMPLETED`). Map it with `@Enumerated(EnumType.STRING)`, never `ORDINAL`.

One place still depends on a value name: the exclusion constraints use `WHERE status <> 'CANCELLED'`. **Never rename `CANCELLED`** without a migration.

(The first version of this spec also had a `VehicleType` Java enum. It is gone: vehicle types are now rows, see §3.2.)

---

## 4. The `seeding/` folder

```
seeding/
├── generate.py           # creates sql/*.sql (Python 3.10+, standard library only)
├── seed.sh               # loads sql/*.sql in ONE transaction, then runs verify.sql
├── verify.sql            # row counts + every id is UUIDv7; prints a per-type summary
└── sql/
    ├── 00_reset.sql      # TRUNCATE all Spec03 tables → seed can run again
    ├── 01_dealerships.sql … 13_outbox_events.sql
```

- **Literal SQL, generated.** Each row is one line; edge-case rows end with `-- edge: <reason>`, so a reviewer can find them with `grep edge:`.
- **Deterministic.** Fixed RNG seeds, so running the generator again gives the same files.
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

1. `V2__domain_schema.sql`: tables, `uuidv7()` defaults, FKs, exclusion constraints, indexes, `outbox_events.attempts`.
2. Java enum `AppointmentStatus`.
3. `seeding/generate.py`: build data in memory, check every appointment against §3.3 and §3.4, write SQL.
4. `seed.sh` + `verify.sql`.
5. Run on a scratch database, then on the compose database.
6. Update DLD diagrams (ERD, constraints, availability, booking, Redis keys, API contract, errors) and ROADMAP D2.

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
- **EV bays full, standard bays free:** all 3 usable EV bays at the hub are busy 10:00–12:00.
- **Fully booked day:** the kiosk's only bay is full from 09:00 to 10:00 (the last job takes the last minute).

### 6.4 Vehicle types and models
- **8 types, used in different ways:** 6 are serviceable; **`HYDROGEN_CAR`** has a model (Toyota Mirai) and a vehicle but **no bay and no technician** → never bookable; **`MOTORBIKE`** has **no model at all** (unused type).
- **Hybrid fits two bay kinds:** the same hybrid car is booked once in an EV bay and once in a standard bay.
- **Flex bay:** a heavy bay that takes diesel trucks **and** diesel cars — a diesel car is booked there. Bay selection should use it last (ROADMAP D2).
- **Electric truck** needs a heavy high-voltage bay; **electric pickup** (F-150 Lightning) is an `ELECTRIC_CAR`, not a truck.
- **Same brand + model name, two types:** Toyota Corolla Cross gasoline and hybrid.
- **Active bay that takes no vehicle type yet** — never used.
- **Technician certified for gasoline cars only** (not diesel / hybrid), technician certified for **all 6 serviceable types**.
- Model names: one character (`3`), digits only (`2008`), starts lower-case (`bZ4X`, `mu-X`), with colon (`CR-V e:HEV`), non-ASCII brand/model (`CITROËN`, `ë-C4`), hyphenated brand (`MERCEDES-BENZ`), two-letter brand (`MG`). A **model with no vehicle** yet.

### 6.5 Resources that cannot be used
- Dealership with **technicians but no bays**, one with **bays but no technicians**, one where **everything is inactive**, and many **new branches with nothing yet**.
- **EV-only** and **truck-only** dealerships.
- Technician with **no skills**, technician with skills but **no vehicle-type certification**.
- **Inactive** technician and bay that still have **completed history** (history must stay valid).
- A **service type that no technician can do** (never bookable).
- An **"all-rounder" technician**: 12 skills.

### 6.6 Status and lifecycle
- `CONFIRMED`, `CANCELLED`, `COMPLETED` all present (65 / 12 / 23).
- **Confirmed but already in the past** — the job was never closed.
- **Cancelled in the future.**
- **Later today** (2 hours after "now").
- **Rescheduled:** `updated_at` is later than `created_at`, and the outbox event has old and new dates.
- **Booked 5 minutes before the start**, and **booked 13 months in advance** (this one has the smallest UUIDv7, because ids follow `created_at`, not `start_time`).
- **Idempotency key:** many `NULL`s (allowed), and one at the maximum length (128).

### 6.7 Text and personal data
- Names with **Vietnamese diacritics, apostrophes, ampersand, em dash, CJK characters, an emoji** (4-byte UTF-8), a **single-word name**, and names at the **maximum length** (200 chars).
- The same bay name ("Bay 01") in different dealerships.
- Customer with **email only**, **phone only**; **plus-addressed** email; **upper-case** email (must be unique ignoring case); phone with the **maximum 15 digits**.
- Customer with **no vehicle**, customer with **6 vehicles of 4 types**, a **company** with a truck fleet (diesel + electric).
- Vehicles: **classic car** (1965), **next model year** (2027), VIN made only of digits (valid format).

### 6.8 Outbox events
- **Pending** events (13) and **processed** events (87).
- Pending after **3 failed attempts**, and a **"poison" event with 25 attempts** (Redis down for a long time).
- Processed **after 2 retries**; processed with **zero delay** (`processed_at = created_at`).
- Processed **more than 7 days ago** → the cleanup job must delete it.
- **Two events with the same `created_at`** — their UUIDv7 ids share the same time prefix, so the relay must order by `created_at, id` and must not expect a strict time order from ids alone.
- Event for an **appointment that does not exist** (no FK on the outbox) — the relay must not fail.
- Events about a **dealership, bay or technician**, not only appointments; a **large payload** (60 dates); an **empty `dates` array**; **non-ASCII text** in the payload.

---

## 7. Acceptance Criteria

- [x] `docker compose up -d --build --wait` → Flyway history shows `1 baseline` and `2 domain schema`, both successful; both `server-api` replicas healthy.
- [x] `seeding/seed.sh` → `verify.sql` passes: main tables and `car_info` have exactly 100 rows, join tables at least 100, and **every id is UUIDv7** (`uuid_extract_version(id) = 7`).
- [x] Running `seeding/seed.sh` a second time works (reset + reload).
- [x] Inserting an overlapping job for the same technician fails with `ex_appointments_technician`.
- [x] Booking the hydrogen car into a standard bay fails with `fk_appointments_bay_type`.
- [x] Changing a job to a service its technician cannot do fails with `fk_appointments_tech_skill`.
- [x] `grep -c "edge:" seeding/sql/*.sql` shows tagged edge rows in every main table.
- [x] DLD diagrams (ERD, constraints, availability, booking, Redis keys, API contract, errors) and ROADMAP D2 describe `vehicle_types` / `car_info` / M:N bays.

---

## 8. Trade-offs & Open Questions

- **`status` not guarded by the DB** (§3.5) — chosen for easy change. Accepted: only `server-api` writes this table.
- **Qualification FKs make history strict** (DB-05 … DB-07): a skill, certification or bay-type row used by past appointments cannot be deleted. Accepted; see ROADMAP D2.
- **`vehicles.vehicle_type_id` is a copy** of the model's type. It is kept correct by a FK, but a model's type cannot change once vehicles use it.
- **Business hours** are checked only in the application (§3.4). A DB trigger could do it, but it would duplicate time-zone logic. Accepted.
- **V2 was edited in place** (it had not been pushed). Any local database that ran the old V2 must be reset: `docker compose down -v`, or drop schema `public`.
- [ ] Buffer time between jobs (ROADMAP §1.4) — not modelled yet; back-to-back is allowed.
- [ ] Should `timezone` be validated in the DB (e.g. a lookup table from `pg_timezone_names`)?
- [ ] Should the seed move with the real date (relative timestamps) instead of a fixed anchor? Fixed is simpler and keeps the DST / leap-day cases on real dates.
