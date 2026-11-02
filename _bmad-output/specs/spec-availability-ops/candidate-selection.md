# Candidate Selection — (bay, technician) pairs for booking

Used by `BookingService` (DLD §5) to pick which resources to try. Reads PostgreSQL only, never the cache. Deterministic: same DB state → same pairs.

## Input

`dealershipId`, `serviceTypeId`, `vehicleTypeId` (from the vehicle, never the client), `serviceBayId` (chosen by the client, mandatory in the booking request), window `[start, end)` where `end = start + serviceType.duration_minutes`.

The client chooses the bay; the server only chooses the technician. Every pair has the same bay.

## Steps

1. **Chosen bay and fitting technicians.**
   - Bay: read `serviceBayId` scoped to the dealership (`CandidateRepository.findBookableBay`). Unknown id or bay of another dealership → **`404 Service bay … not found`**.
   - Bay not `active`, or it does not take `vehicleTypeId` (`service_bay_vehicle_types`) → return **`NOT_SERVICEABLE`** (booking answers `422`).
   - Technicians: `active` technicians of the dealership with skill `serviceTypeId` (`technician_skills`) AND certified for `vehicleTypeId` (`technician_vehicle_types`). Empty → **`NOT_SERVICEABLE`**.
2. **Free resources.** Non-`CANCELLED` appointments overlapping the window (`existing.start < end AND start < existing.end`). Bay busy, or no technician free → return **`NONE_FREE`** (booking answers `409`). The server never swaps to another bay.
3. **Order technicians:** number of non-`CANCELLED` appointments on the window's local date (dealership zone) ASC, then `id` ASC (spread work).
4. **Build at most 3 pairs** (Spec04 §6, `N = 3`, `scheduler.booking.max-pairs`): `(bay, tech[i])` for the first 3 ordered technicians.
5. Return **`PAIRS(list)`** in that order. `BookingService` tries each in its own transaction, no wait between tries; technician exclusion (`23P01`) or FK (`23503`) violation → next pair; bay exclusion → stop (every pair has that bay); all fail → `409 Booking failed`.

## Examples

| Chosen bay | Free techs (ordered) | Pairs |
|---|---|---|
| B1 | T1, T2, T3, T4 | (B1,T1) (B1,T2) (B1,T3) |
| B1 | T1 | (B1,T1) |
| B9 (other dealership) | T1 | `404` |
| B1 (inactive / other vehicle type) | T1 | `NOT_SERVICEABLE` → 422 |
| B1 | — (none qualified) | `NOT_SERVICEABLE` → 422 |
| B1 (busy) | T1 | `NONE_FREE` → 409 |
