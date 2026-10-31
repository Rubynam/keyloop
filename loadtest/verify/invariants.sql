-- Spec06 §6.3: run after any load profile that writes (load, stress, spike, race).
-- Expected output: every query returns 0 rows. One row = a double booking got through.
--   docker compose exec -T postgres psql -U scheduler -d scheduler < loadtest/verify/invariants.sql
-- INV-04/05 only look at rows written in the last :since (default 1 hour = the load run).

\if :{?since}
\else
  \set since '1 hour'
\endif

\echo 'INV-01 technician busy twice at the same time (DB-01)'
SELECT a.technician_id, a.id, b.id, a.start_time, b.start_time
FROM appointments a JOIN appointments b
  ON a.technician_id = b.technician_id AND a.id < b.id
 AND a.status <> 'CANCELLED' AND b.status <> 'CANCELLED'
 AND a.start_time < b.end_time AND b.start_time < a.end_time;

\echo 'INV-02 bay busy twice at the same time (DB-02)'
SELECT a.service_bay_id, a.id, b.id, a.start_time, b.start_time
FROM appointments a JOIN appointments b
  ON a.service_bay_id = b.service_bay_id AND a.id < b.id
 AND a.status <> 'CANCELLED' AND b.status <> 'CANCELLED'
 AND a.start_time < b.end_time AND b.start_time < a.end_time;

\echo 'INV-03 vehicle in two places at the same time (DB-03)'
SELECT a.vehicle_id, a.id, b.id, a.start_time, b.start_time
FROM appointments a JOIN appointments b
  ON a.vehicle_id = b.vehicle_id AND a.id < b.id
 AND a.status <> 'CANCELLED' AND b.status <> 'CANCELLED'
 AND a.start_time < b.end_time AND b.start_time < a.end_time;

\echo 'INV-04 appointment without its outbox event (D1: same transaction)'
SELECT a.id FROM appointments a
WHERE a.created_at > now() - :'since'::interval
  AND NOT EXISTS (SELECT 1 FROM outbox_events e WHERE e.aggregate_id = a.id::text);

\echo 'INV-05 outbox backlog older than 1 minute after the run (relay keeps up)'
SELECT count(*) AS stuck FROM outbox_events
WHERE processed_at IS NULL AND created_at < now() - interval '1 minute'
  AND created_at > now() - :'since'::interval
HAVING count(*) > 0;
