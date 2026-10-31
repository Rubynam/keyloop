# Edge Cases — Check Availability

Every row must be covered by a test. "Pure" rows belong in `SlotCalculatorTest`; HTTP rows in `AvailabilityControllerTest`; rows marked **DB** in the CAP-6 integration test.

| Scenario | Input / State | Expected | Cap |
|---|---|---|---|
| Happy path | hub dealer, OIL_CHANGE (60m), GASOLINE_CAR, 1 day | slots every 15 min where a fitting bay + qualified tech are free | CAP-1 |
| Back-to-back | existing job 09:00–10:00 on the only pair | 10:00 start offered, 09:15 not | CAP-1 |
| Cancelled overlap | only `CANCELLED` job in window | slot offered | CAP-1 |
| Service longer than hours | 10h service at an 08–17 dealer | every date empty | CAP-1 |
| Before now | slots earlier than the clock today | dropped | CAP-1 |
| Open 24h | close `24:00`, 60m service | last slot 23:00–24:00 | CAP-2 |
| DST day | New York, 2026-11-01 | starts follow wall clock; offsets −04:00 → −05:00 | CAP-2 |
| Unknown id | random UUID `dealershipId` | 404 ProblemDetail | CAP-3 |
| Bad range | `to` before `from`, or 15 days | 400 ProblemDetail | CAP-3 |
| Missing / malformed param | no `vehicleTypeId`, `from=abc` | 400 ProblemDetail | CAP-3 |
| Type not serviceable | HYDROGEN_CAR | 200, every date empty | CAP-4 |
| Book a returned slot (**DB**) | take a returned slot, insert appointment on fitting bay + qualified tech | insert accepted by PostgreSQL | CAP-6 |
| Slot consumed (**DB**) | after the insert above, only pair at the dealer | re-query omits that slot and every overlapping start | CAP-6 |
| Mixed-fit booking (**DB**) | an appointment holds a fitting bay and a tech outside this query | only the bay is marked busy | CAP-6 |
