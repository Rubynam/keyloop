# Candidate Selection — (bay, technician) pairs for booking

Used by `BookingService` (DLD §5) to pick which resources to try. Reads PostgreSQL only, never the cache. Deterministic: same DB state → same pairs.

## Input

`dealershipId`, `serviceTypeId`, `vehicleTypeId` (from the vehicle, never the client), window `[start, end)` where `end = start + serviceType.duration_minutes`.

## Steps

1. **Fitting resources.**
   - Bays: `active` bays of the dealership that take `vehicleTypeId` (`service_bay_vehicle_types`).
   - Technicians: `active` technicians of the dealership with skill `serviceTypeId` (`technician_skills`) AND certified for `vehicleTypeId` (`technician_vehicle_types`).
   - Either list empty → return **`NOT_SERVICEABLE`** (booking answers `422`).
2. **Free resources.** Drop every fitting bay / technician that has a non-`CANCELLED` appointment overlapping the window (`existing.start < end AND start < existing.end`). Either list empty → return **`NONE_FREE`** (booking answers `409`).
3. **Order bays:** number of vehicle types the bay takes ASC, then `id` ASC (D2 — keep flexible bays for vehicles only they can take).
4. **Order technicians:** number of non-`CANCELLED` appointments on the window's local date (dealership zone) ASC, then `id` ASC (spread work).
5. **Build at most 3 pairs** (Spec04 §6, `N = 3`):
   1. Diagonal first: `(bay[i], tech[i])` for `i = 0 … min(#bays, #techs, 3) − 1` — every pair differs in both bay and technician, so one conflicting resource fails one try, not all three.
   2. If fewer than 3 pairs and unused combinations remain, fill from the cross product in order `(bay index, tech index)` ascending, skipping pairs already taken, until 3.
6. Return **`PAIRS(list)`** in that order. `BookingService` tries each in its own transaction, no wait between tries; exclusion (`23P01`) or FK (`23503`) violation → next pair; all fail → `409 Booking failed`.

## Examples

| Free bays (ordered) | Free techs (ordered) | Pairs |
|---|---|---|
| B1, B2, B3 | T1, T2, T3, T4 | (B1,T1) (B2,T2) (B3,T3) |
| B1 | T1, T2, T3 | (B1,T1) (B1,T2) (B1,T3) |
| B1, B2 | T1 | (B1,T1) (B2,T1) |
| B1, B2 | T1, T2 | (B1,T1) (B2,T2) (B1,T2) |
| — (none fit) | T1 | `NOT_SERVICEABLE` → 422 |
| B1 (busy) | T1 | `NONE_FREE` → 409 |
