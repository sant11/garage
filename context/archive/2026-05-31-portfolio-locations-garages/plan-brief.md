# Manage Locations & Garages (S-02) — Plan Brief

> Full plan: `context/changes/portfolio-locations-garages/plan.md`
> Research: `context/changes/portfolio-locations-garages/research.md`

## What & Why

S-02 is GarageOps' first domain slice: the owner can add / rename / archive **locations**,
add / edit / archive **garages** (label + default monthly rent) under them, mark a garage as a
**problem** with a free-text reason, and view the whole portfolio **grouped by location** with each
garage's status. It implements FR-003–006 and is the first slice to exercise the FR-021
archive-only retain-records rule on real domain data.

## Starting Point

The auth + persistence foundations are done (F-01, F-02, S-01). `ArchivableEntity` provides the
archive-only base, Flyway owns schema (`V1`/`V2` immutable, next is `V3`), `ddl-auto=validate`
fail-fasts on schema mismatch at boot, and `MainLayout`/`HomeView`/`SecurityGatingTests` give the
view + gating templates to mirror. No domain entities exist yet; the suite is entirely DB-free.

## Desired End State

After login the owner opens **Locations** from the nav and sees one section per location — each
with rename / add-garage / archive actions and a grid of its garages showing label, default rent,
and a **free / problem** status badge with edit / mark-problem / archive actions. Add and edit use
validated dialogs; archiving a location cascade-stamps its garages (retained, never deleted) behind
a confirm that names the garage count. The obsolete `deploy_smoke_test` table is gone.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Grouped display | Section per location (header + actions + garage grid) | Literal FR-005 "grouped by location" fit; natural home for location-level actions | Plan |
| Archive a location with active garages | Cascade-archive — stamp children, never delete | Matches "close this location" in one action; R4-safe because it stamps, not deletes | Plan |
| Where cascade lives | Service layer, no JPA `CascadeType.REMOVE` | Explicit stamp pass keeps R4 (never delete children) true by construction | Plan |
| Garage edit scope | Full edit of label + default rent (reuse the Binder form) | Rents/typos change; edit is nearly free when the add-form is reused | Plan |
| Problem-flag storage | Single nullable `problem_reason TEXT` (NULL = not flagged) | FR-006 is a simple owner-toggled flag, no state machine | Research/Plan |
| `owner_id` FK | Omitted | Single-owner app; existing entities carry none (PRD Non-Goal) | Research |
| Migrations | `V3` create tables + `V4` drop smoke table | Forward-only; V1/V2 immutable; separates create from cleanup | Plan |
| Smoke-record cleanup | Remove Java + drop table (verified no `src/` usages) | Explicitly dead-flagged; keeps schema honest | Plan |
| This slice's tests | DB-free entity + service units + gating extension | Respects test-plan Phase-2 ownership of the real-DB R4 test; cost×signal | Research/Plan |

## Scope

**In scope:** `Location` + `Garage` entities/repos/services; `V3`/`V4` migrations; section-per-location
portfolio view with Binder dialogs; nav wiring; free/problem status; cascade-stamp archive; smoke
cleanup; DB-free entity + service unit tests; `/locations` gating test.

**Out of scope:** "rented" status (S-04 contracts — left as a seam); `owner_id` FK; the real-DB R4
retention test (test-plan Phase 2); tenants/contracts/payments/dashboard; hard-delete UI; test-plan
§6 cookbook edits.

## Architecture / Approach

Bottom-up: entities extend `ArchivableEntity`; `Garage` holds the `@ManyToOne Location` FK (no
cascade annotations). Services own all business logic — cascade-archive is an explicit
service-level stamp pass (load active children → `archive()` each → save), so R4 holds by
construction. The Vaadin `LocationsView` (`@Route("locations")`, `@PermitAll`, under `MainLayout`)
renders repeated location sections over the services and re-fetches after each mutation.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Persistence & schema | `V3`/`V4` migrations, `Location`/`Garage` entities + repos, smoke cleanup, entity unit tests | `ddl-auto=validate` mismatch breaks boot (`TIMESTAMPTZ`, `NUMERIC`) |
| 2. Services & logic | `LocationService`/`GarageService` + DB-free unit tests; cascade-stamp oracle | Cascade accidentally deletes instead of stamping (R4) |
| 3. Views, nav & gating | `LocationsView` + Binder dialogs, nav link, `/locations` gating test (R5) | New route ships ungated / leaks owner data |

**Prerequisites:** S-01 (done), F-02 (done). No new dependencies (no Testcontainers this slice).
**Estimated effort:** ~3 sessions, one per phase, `/clear` between.

## Open Risks & Assumptions

- Cascade-archive must stamp, never delete — enforced by avoiding `CascadeType.REMOVE` and asserted
  in the service test; full R4 proof (real-DB children survive + queryable) lands in test-plan Phase 2.
- Boot-time `ddl-auto=validate` is the only entity↔schema integration check in this slice (suite is
  DB-free) — it's a manual `spring-boot:run` step, not an automated test.
- "rented" must be designed as a future seam, not stubbed with fake contract data.

## Success Criteria (Summary)

- Owner can manage locations and garages end-to-end and see free/problem status, grouped by location.
- Archiving a location retains it and its garages as archived rows (no data loss; FR-021).
- `/locations` redirects anonymous visitors to login; the app boots clean with the new schema.
