"""Booking write simulator for the ramp load test.

The API has no booking endpoint yet, so this process plays its part: it writes
appointments and their outbox events in ONE transaction (ROADMAP D1), as the booking
path will. scheduler-batch's relay then bumps the Redis version keys, so the hot
availability keys that Locust reads are invalidated while the load runs.

    SIM_RATE=2 SIM_DURATION=480 SIM_CANCEL_SHARE=0.15 python simulate.py

    tick ─┬─ 85 %  book    INSERT appointments + outbox AppointmentBooked
          │                 exclusion violation (23P01) = slot already taken → "conflict"
          └─ 15 %  cancel  UPDATE status = CANCELLED + outbox AppointmentCancelled

One logfmt line per write on stdout; a JSON summary in reports/ramp_simulator.json.
"""
import json
import os
import random
import sys
import time
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import psycopg
from psycopg import errors

REFS = json.loads(Path(os.getenv("SIM_REFS", "/loadtest/data/seed-refs.json")).read_text())
REPORT = Path(os.getenv("SIM_REPORT", "/loadtest/reports/ramp_simulator.json"))
RATE = float(os.getenv("SIM_RATE", "2"))
DURATION = float(os.getenv("SIM_DURATION", "480"))
CANCEL_SHARE = float(os.getenv("SIM_CANCEL_SHARE", "0.15"))
DAYS = int(os.getenv("SIM_DAYS", "7"))

DEALER = REFS["dealerships"]["Q7"]
TZ = "Asia/Ho_Chi_Minh"
VEHICLE_TYPE = REFS["vehicleTypes"]["GASOLINE_CAR"]
# The services Locust reads as hot keys: every booking here invalidates one of them
SERVICES = [REFS["serviceTypes"]["OIL_CHANGE"], REFS["serviceTypes"]["TYRE_ROTATION"]]

BOOK = """
WITH d AS (SELECT open_time, close_time, timezone FROM dealerships WHERE id = %(dealer)s),
     st AS (SELECT duration_minutes FROM service_types WHERE id = %(service)s),
     v AS (SELECT id, customer_id FROM vehicles WHERE vehicle_type_id = %(vt)s ORDER BY random() LIMIT 1),
     bay AS (SELECT b.id FROM service_bays b
             JOIN service_bay_vehicle_types x ON x.service_bay_id = b.id AND x.vehicle_type_id = %(vt)s
             WHERE b.dealership_id = %(dealer)s AND b.active ORDER BY random() LIMIT 1),
     tech AS (SELECT t.id FROM technicians t
              JOIN technician_skills s ON s.technician_id = t.id AND s.service_type_id = %(service)s
              JOIN technician_vehicle_types tv ON tv.technician_id = t.id AND tv.vehicle_type_id = %(vt)s
              WHERE t.dealership_id = %(dealer)s AND t.active ORDER BY random() LIMIT 1),
     slot AS (SELECT ((%(day)s::date + d.open_time
                       + floor(random() * ((extract(epoch FROM d.close_time - d.open_time) / 60
                                            - st.duration_minutes) / 15 + 1)) * interval '15 minutes')
                      AT TIME ZONE d.timezone) AS start_time,
                     st.duration_minutes
              FROM d, st)
INSERT INTO appointments (customer_id, vehicle_id, dealership_id, service_type_id, technician_id,
                          service_bay_id, vehicle_type_id, start_time, end_time)
SELECT v.customer_id, v.id, %(dealer)s, %(service)s, tech.id, bay.id, %(vt)s, slot.start_time,
       slot.start_time + slot.duration_minutes * interval '1 minute'
FROM v, bay, tech, slot
RETURNING id, (start_time AT TIME ZONE %(tz)s)::date
"""

CANCEL = """
UPDATE appointments SET status = 'CANCELLED', updated_at = now()
WHERE id = (SELECT id FROM appointments
            WHERE dealership_id = %(dealer)s AND status = 'CONFIRMED' AND start_time > now()
            ORDER BY random() LIMIT 1 FOR UPDATE SKIP LOCKED)
RETURNING id, (start_time AT TIME ZONE %(tz)s)::date
"""

OUTBOX = """
INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
VALUES ('Appointment', %(id)s::text, %(type)s,
        jsonb_build_object('appointmentId', %(id)s::text, 'dealershipId', %(dealer)s::text,
                           'dates', jsonb_build_array(%(day)s::text)))
"""


def log(**fields):
    fields = {"ts": datetime.now(timezone.utc).isoformat(timespec="milliseconds"), **fields}
    print(" ".join(f"{k}={v}" for k, v in fields.items()), flush=True)


def write(conn, op, latencies):
    """One booking or cancellation and its outbox event in one transaction."""
    began = time.perf_counter()
    try:
        with conn.transaction():
            if op == "book":
                # The same local days Locust reads as hot keys (dealership time zone)
                day = datetime.now(ZoneInfo(TZ)).date() + timedelta(days=random.randint(1, DAYS))
                row = conn.execute(BOOK, {"dealer": DEALER, "service": random.choice(SERVICES), "vt": VEHICLE_TYPE,
                                          "day": day, "tz": TZ}).fetchone()
                event = "AppointmentBooked"
            else:
                row = conn.execute(CANCEL, {"dealer": DEALER, "tz": TZ}).fetchone()
                event = "AppointmentCancelled"
            if row is None:
                return "no_candidate"
            conn.execute(OUTBOX, {"id": row[0], "type": event, "dealer": DEALER, "day": row[1]})
        result = "booked" if op == "book" else "cancelled"
        log(op=op, result=result, appointment=row[0], day=row[1])
        return result
    except errors.ExclusionViolation:
        # Expected under load: another booking took the bay, technician or vehicle
        return "conflict"
    except psycopg.Error as e:
        log(op=op, result="error", sqlstate=e.sqlstate, error=repr(str(e).splitlines()[0]))
        return "error"
    finally:
        latencies.append((time.perf_counter() - began) * 1000)


def main():
    dsn = (f"host={os.getenv('DB_HOST', 'postgres')} port={os.getenv('DB_PORT', '5432')} "
           f"dbname={os.getenv('POSTGRES_DB', 'scheduler')} user={os.getenv('POSTGRES_USER', 'scheduler')} "
           f"password={os.getenv('POSTGRES_PASSWORD', 'scheduler')}")
    counts, latencies = Counter(), []
    started = time.monotonic()
    log(op="start", rate=RATE, duration=DURATION, cancel_share=CANCEL_SHARE, dealer=DEALER)
    with psycopg.connect(dsn, autocommit=True) as conn:
        n = 0
        while time.monotonic() - started < DURATION:
            op = "cancel" if random.random() < CANCEL_SHARE else "book"
            counts[f"{op}:{write(conn, op, latencies)}"] += 1
            n += 1
            # Fixed schedule, not "sleep after each write": a slow write does not lower the rate
            time.sleep(max(0.0, started + n / RATE - time.monotonic()))
    latencies.sort()
    pct = lambda p: round(latencies[min(len(latencies) - 1, int(p * len(latencies)))], 1) if latencies else None
    summary = {"rate": RATE, "durationS": round(time.monotonic() - started), "writes": sum(counts.values()),
               "counts": dict(counts), "latencyMs": {"p50": pct(0.5), "p95": pct(0.95), "p99": pct(0.99),
                                                    "max": round(latencies[-1], 1) if latencies else None}}
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(summary, indent=2) + "\n")
    log(op="summary", **{k.replace(":", "_"): v for k, v in counts.items()})
    return 1 if counts.get("book:error", 0) + counts.get("cancel:error", 0) else 0


if __name__ == "__main__":
    sys.exit(main())
