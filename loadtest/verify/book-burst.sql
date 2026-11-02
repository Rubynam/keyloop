-- Profile book-burst (81-book-burst): bookings per hot slot against its capacity.
-- run-ramp.sh runs it after invariants.sql (which already proves no bay / technician /
-- vehicle is busy twice). :start = when the run began; only rows written since count.
--   docker compose exec -T "${PG_PRIMARY:-postgres-1}" psql -U scheduler -d scheduler -v start="2026-11-01 12:00:00+00" < loadtest/verify/book-burst.sql
--
-- capacity = min(active bays taking the vehicle type, active technicians with the skill and
-- the vehicle type): what one slot can hold on an empty day.

\if :{?start}
\else
  \set start '1 hour ago'
\endif

-- Day ranges of the cases: keep in sync with input/81-book-burst.json (wave.slot.days)
CREATE TEMP TABLE burst_cases (case_id text, first_day int, last_day int, per_slot text);
INSERT INTO burst_cases VALUES ('BB-01', 1100, 1499, 'capacity'), ('BB-02', 1500, 1899, 'capacity'),
  ('BB-03', 1900, 2299, 'capacity'), ('BB-04', 2300, 2699, 'capacity'), ('BB-05', 2700, 3099, 'capacity'),
  ('BB-06', 3100, 3499, '1 (one car)');

CREATE TEMP VIEW burst_slots AS
WITH booked AS (
  SELECT dealership_id, service_type_id, vehicle_type_id, start_time, count(*) AS booked
  FROM appointments
  WHERE created_at >= :'start'::timestamptz AND status <> 'CANCELLED'
  GROUP BY 1, 2, 3, 4
)
SELECT b.*, d.name AS dealership, st.code AS service,
       (SELECT c.case_id FROM burst_cases c
        WHERE ((b.start_time AT TIME ZONE d.timezone)::date - (now() AT TIME ZONE d.timezone)::date)
              BETWEEN c.first_day AND c.last_day) AS case_id,
       least((SELECT count(DISTINCT sb.id) FROM service_bays sb
              JOIN service_bay_vehicle_types bv ON bv.service_bay_id = sb.id
              WHERE sb.dealership_id = b.dealership_id AND sb.active AND bv.vehicle_type_id = b.vehicle_type_id),
             (SELECT count(DISTINCT t.id) FROM technicians t
              JOIN technician_skills s ON s.technician_id = t.id
              JOIN technician_vehicle_types tv ON tv.technician_id = t.id
              WHERE t.dealership_id = b.dealership_id AND t.active
                AND s.service_type_id = b.service_type_id AND tv.vehicle_type_id = b.vehicle_type_id)) AS capacity
FROM booked b
JOIN dealerships d ON d.id = b.dealership_id
JOIN service_types st ON st.id = b.service_type_id;

\echo 'BB-INV-01 slot holds more bookings than its capacity (expected: 0 rows)'
SELECT case_id, left(dealership, 30) AS dealership, service, start_time, booked, capacity
FROM burst_slots WHERE booked > capacity ORDER BY start_time;

\echo 'BB-INFO-02 fill per case: below capacity = a customer got 409 while a pair was free (BB-06: 1 per slot is right)'
SELECT case_id, left(dealership, 30) AS dealership, service, capacity,
       (SELECT per_slot FROM burst_cases c WHERE c.case_id = s.case_id) AS expected,
       count(*) AS slots,
       count(*) FILTER (WHERE booked = capacity) AS full_slots,
       count(*) FILTER (WHERE booked < capacity) AS under_filled,
       round(avg(booked), 2) AS avg_booked,
       string_agg(DISTINCT booked::text, ',') AS booked_values
FROM burst_slots s GROUP BY 1, 2, 3, 4 ORDER BY 1;

\echo 'BB-INFO-03 bookings vs outbox rows of this run (D1: one AppointmentBooked per appointment)'
SELECT (SELECT count(*) FROM appointments WHERE created_at >= :'start'::timestamptz) AS appointments,
       (SELECT count(*) FROM outbox_events
        WHERE created_at >= :'start'::timestamptz AND event_type = 'AppointmentBooked') AS outbox_booked,
       (SELECT count(*) FROM appointments a WHERE a.created_at >= :'start'::timestamptz
          AND NOT EXISTS (SELECT 1 FROM outbox_events e WHERE e.aggregate_id = a.id::text)) AS without_event,
       (SELECT count(*) FROM outbox_events e WHERE e.created_at >= :'start'::timestamptz
          AND e.event_type = 'AppointmentBooked'
          AND NOT EXISTS (SELECT 1 FROM appointments a WHERE a.id::text = e.aggregate_id)) AS event_without_row;
