# Outbox payload contract

One sample per `event_type` in `outbox_events.payload` (Spec04 §4.4).
`server-api` (producer) must write payloads of this shape; `scheduler-batch` (relay) reads them.

| Field | Required | Meaning |
|---|---|---|
| `dealershipId` | yes | UUID of the dealership whose availability changed |
| `dates` | yes (may be empty) | Dealership-**local** dates (`yyyy-MM-dd`), never UTC |
| `oldDates`, `newDates` | only `AppointmentRescheduled` | Dates before and after the move |
| anything else | no | Ignored by the relay |

The relay bumps `avail:ver:{dealershipId}:{date}` for the union of `dates`, `oldDates` and `newDates`.
A payload without `dealershipId` or with an invalid date is FAILED without retry.
