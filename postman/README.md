# Postman — manual API testing

Collection and environment for hand-testing the Scheduler API against docker compose + seed data.

| File | What |
|---|---|
| `keyloop-scheduler.postman_collection.json` | v2.1 collection: ping, availability cases, booking (Spec07), 404s / 400s — every request has test scripts |
| `local.postman_environment.json` | `baseUrl` (`http://localhost:8080`, nginx) and the seed ids the requests use |

## Run

```bash
docker compose up -d --build --wait
seeding/seed.sh
npx newman run postman/keyloop-scheduler.postman_collection.json -e postman/local.postman_environment.json
```

Or in Postman: **Import** both files → pick the *keyloop local* environment → **Run collection**.
To hit a host-run API (`scripts/run-server-api.sh`), set `baseUrl` to `http://localhost:8090`.

## Requests

| Folder | Request | Checks |
|---|---|---|
| System | Ping | `200 {"status":"ok"}` |
| Availability | Happy path (hub, oil change, gasoline car) | triple echoed, every date listed, 15-min starts, `+07:00`, 60-min windows inside 08:00–17:00, no bay / technician ids |
| | Back-to-back (Riverside, 2026-11-03) | `Asia/Ho_Chi_Minh`, date listed; job 08:00–09:00 on the only pair → 09:00 offered, 08:00–08:45 not |
| | Fully booked kiosk (2026-11-04) | `Asia/Singapore`, date listed with no slots |
| | DST end (New York, 2026-10-31..11-02) | per date: slots present, `-04:00` before / `-05:00` after, starts on the 07:00–18:00 wall clock, last at 18:00 |
| | Open 24h (roadside hub) | last slot `23:00Z`–`00:00Z` next day |
| | Hydrogen car | `200`, every date empty |
| Appointments | Book - happy path (hub, oil change) | `201`, `Location`, bay + technician, UTC times, 60 min; random slot 700–900 days ahead |
| | Book - replay | same key + body → `201`, same id, `Idempotent-Replayed: true` |
| | Key reused with another body / vehicle already booked | `422` |
| | Missing `startTime`, past start, no offset, bad UUID, missing `Idempotency-Key` | `400` with `errors[].field` |
| | Vehicle of another customer | `400` |
| | Unknown dealership | `404` naming the dealership |
| | Off the slot grid (09:07), outside hours (20:00), ends after closing (16:30), hydrogen car | `422` |
| | Fully booked kiosk (2026-11-04 09:00) | `409` "Booking failed"; skipped once the date has passed |
| Errors | Unknown dealership / service type / vehicle type | `404` ProblemDetail |
| | `to` before `from`, 15-day range, missing `vehicleTypeId`, `from=abc`, bad UUID | `400` ProblemDetail |

## Dates

- Run the Appointments folder in order: the replay and the first two `422`s reuse the happy path's key and slot.

- A collection pre-request script sets `futureDate` = today + 3 days (UTC) and two days after it, so the
  happy path, 24h and hydrogen requests keep working on any day.
- Back-to-back, kiosk and DST use **fixed dates** because they rely on specific seed rows
  (seed "now" anchor: 2026-10-31 09:00 UTC). Slots that start before the real "now" are dropped by the API,
  so a check that looks at slots runs only while its date is still ahead and is skipped (`pm.test.skip`)
  afterwards — it never passes on an empty list. Status, time zone and listed dates are always checked.
