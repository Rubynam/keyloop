---
title: 'Spec05 PR1 — shared layer-root code (constants, NotFoundException, AppointmentStatus, ClockConfig, ping, web)'
type: 'refactor'
created: '2026-11-01'
status: 'done'
route: 'oneshot'
review_loop_iteration: 0
context: ['{project-root}/planing/Spec05.md', '{project-root}/CLAUDE.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `server-api` code shared by more than one feature sits in feature or ad-hoc packages (`availability/NotFoundException`, `domain/AppointmentStatus`, the `Clock` bean inside `AvailabilityConfig`) and uses literals for paths, header names and error text. `PingController` returns a `Map`. This breaks CLAUDE.md §1–§3 and blocks Spec05 PR2/PR3.

**Approach:** Deliver Spec05 §9 PR1 with no behaviour change: add `constant/ApiPaths`, `constant/HeaderNames`, `constant/ErrorMessages`; move `NotFoundException` → `exception/`, `AppointmentStatus` → `model/`; move the clock bean to `config/ClockConfig`; move ping to `controller/ping/PingController` returning `dto/ping/PingResponse`; make `web/RequestIdFilter` and `web/ApiExceptionHandler` use the constants and `HttpStatus.getReasonPhrase()` titles (Spec05 D9). Use `git mv`. Only the import lines and `NotFoundException` entity names change in availability/booking files; the rest of those files is PR2/PR3.

</frozen-after-approval>

## Implementation Notes

- Branch `refactor/spec05-pr1-shared` from `main` @ `8dc1c5a` (PR #5 merged, so Spec05 P1 holds). PR2–PR5 recorded in `deferred-work.md`.
- `git mv`: `availability/NotFoundException` → `exception/`, `domain/AppointmentStatus` → `model/` (`domain/` removed), `web/PingController` → `controller/ping/`, `web/PingControllerTest` → `controller/ping/`.
- New: `constant/ApiPaths`, `constant/HeaderNames`, `constant/ErrorMessages`, `config/ClockConfig`, `dto/ping/PingResponse`. Constants classes use `@NoArgsConstructor(access = PRIVATE)` like `CorrelationId`.
- `ErrorMessages` also holds the 503 / 500 `detail` texts from `ApiExceptionHandler` (not in Spec05 §6, but CLAUDE.md §3 forbids them as literals). Text is byte-equal.
- `ApiExceptionHandler`: one private `problem(status, detail)` helper; title = `HttpStatus.getReasonPhrase()` (D9). The four titles are identical to the old literals.
- `PingController` keeps a private `STATUS_OK` constant (only user). JSON stays `{"status":"ok"}`.
- `ClockConfig.clock()` keeps the bean name `clock`, so test overrides still apply.
- Availability / booking files: only imports and `NotFoundException` entity args (→ `ErrorMessages.ENTITY_*`) changed. Other rule breaks there stay for PR2/PR3.
- `./gradlew :server-api:test`: 63 tests, 0 failures (all Testcontainers ITs ran).

- Review patches: exact title/detail assertions for 404/400/503/500 in `AvailabilityControllerTest`; `AvailabilityController` mappings now use `ApiPaths` (so `ApiPaths.AVAILABILITY` is used); `ErrorMessages` Javadoc wording; `{@link ClockConfig}` in `AvailabilityConfig`. Re-run: 63 tests, 0 failures.

## Review Triage Log

- Error text not pinned by tests — medium — real: 503/500 detail and titles were unasserted. Patched (exact literal assertions).
- Clock bean / nested `ClockConfig` in `AvailabilityCacheIT` — low, rejected — full-context ITs load the bean (63 green); nested test class name only reads confusingly, no bean clash.
- Touched files lack two-part Javadoc — low, rejected for PR1 — CLAUDE.md §4 targets controller/service/repository; `web/`, `config/`, `exception/` are out of that list. Availability/booking Javadoc is PR2/PR3 scope. `{@link}` fix applied.
- `ApiPaths.AVAILABILITY` unused — medium — real. Patched: controller uses `ApiPaths.API_V1` / `ApiPaths.AVAILABILITY`.
- Log message literals in `ApiExceptionHandler` — false — CLAUDE.md §3 targets logic; RF-11 excludes log patterns.
- Test uses `"Dealership"` literal — false — tests pin the contract with literals on purpose.
- `ErrorMessages` Javadoc says "shared by more than one feature" — low — real. Patched wording.
- `STATUS_OK` private constant on `PingController` — low, rejected — named, single-use, not a magic value.
- Docs not staged (CLAUDE.md, Spec05, this spec, deferred-work) — medium — real. Included in the commit.
- `.DS_Store` / `__pycache__` not ignored — low — real but pre-existing; deferred (`.gitignore` also changes in PR #6).
- `HeaderNames.REQUEST_ID` not pinned — false — `RequestIdFilterTest` asserts the `X-Request-Id` literal end-to-end.
