# Rental Contracts (S-04) — Plan Brief

> Full plan: `context/changes/rental-contracts/plan.md`

## What & Why

Build the `Contract` layer that links a tenant to a garage (start date, required end date, monthly rent, payment day-of-month), lets the owner end a contract early, and shows a garage's full rental history. This is the critical-path slice: it completes the garage "rented" status deferred from S-02, fills the contract-history seam left in the S-03 tenant profile, and produces the exact fields the overdue engine (S-05) and the north-star dashboard (S-06) consume. Implements FR-009, FR-010, FR-011, and the FR-021 retain-on-archive guarantee.

## Starting Point

S-02 (locations/garages) and S-03 (tenants) are done and archived. The persistence base (`ArchivableEntity`), service conventions (`ObjectProvider` injection, loop-and-stamp cascade), and view conventions (`Binder` + throwaway bean, `HasUrlParameter`, `ConfirmDialog`) are all established. Seams are pre-cut: `TenantProfileView.contractsSection()` is a placeholder, `Garage` notes its rented status comes from S-04, and `Tenant`'s javadoc reserves the child-side FK for this slice. Last migration is `V5__tenants.sql`.

## Desired End State

The owner opens a garage (new `garages/:id` view), sees its rental history, and creates a contract by picking a tenant (rent pre-filled, overlapping contracts rejected). They can end a contract early on the actual move-out date; ended contracts remain in history. The portfolio shows a garage as "rented" when it has a current active contract, and a tenant's profile lists all their contracts. Archiving any parent retains the underlying contracts — nothing is ever hard-deleted.

## Key Decisions Made

| Decision | Choice | Why | Source |
| --- | --- | --- | --- |
| Ended vs archived | Separate: `ended_on` date for end-early; `archived_at` only for parent cascade | Ended contracts must stay queryable in history (FR-011) and feed S-06's ended-early signal | Plan |
| Overlap rule | Service rejects overlapping active contracts on a garage | Guarantees ≤ 1 active contract → trustworthy vacant/rented (R6, FR-005) | Plan |
| Payment day / grace | Day 1–28; defer `grace_days` to S-05 | Avoids month-length boundary trap (R2); schema is exactly what FR-009 names | Plan |
| Contract editing | None — create + end-early only | Matches the FR set; keeps history honest for S-05's overdue math | Plan |
| Create entry point | From the garage (garage fixed, pick tenant, rent pre-filled) | Natural flow; sits next to the history it creates | Plan |
| History surface | New `garages/:id` detail view | Stable drill-through target (also S-06 FR-018); mirrors `TenantProfileView` | Plan |
| "Rented" status | Derived at query time via one batch query, no stored field | Single source of truth (contracts); no denormalized flag to drift | Plan |
| Testing | Mocked unit tests + extend `SecurityGatingTests`; defer real-DB to test-plan Phase 2 | Matches the established DB-free base and the plan's cost×signal layering | Plan |

## Scope

**In scope:** `Contract` entity + `V6` migration + repository; `ContractService` (create-with-overlap-guard, end-early, history/profile/rented reads); FR-021 retain-on-archive cascade into tenant/garage/location services; `garages/:id` detail+history view; portfolio "rented" badge; filled tenant-profile contract list; new-route gating test.

**Out of scope:** payments / overdue / `grace_days` (S-05); dashboard (S-06); late-payer flag (S-07); contract term-editing; real-DB integration tests (test-plan Phase 2); `@OneToMany` parent collections; auto-renewal / open-ended contracts.

## Architecture / Approach

Standard vertical slice: data model → service → views. `Contract` carries two explicit LAZY `@ManyToOne` FKs (Tenant, Garage); every read path join-fetches the association it renders (`open-in-view=false`). Lifecycle is **date-derived, never stored** — one predicate (`ended_on IS NULL AND start ≤ today ≤ planned_end`) defines "currently active", and the overlap guard, the "rented" status, and the history status label all read from it. Retain-on-archive is loop-and-stamp (inject `ContractRepository` into the existing services), never JPA cascade and never a delete.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Data model | `Contract` entity, `V6__contracts.sql`, repository with fetch-join + active finders; entity tests | Migration↔entity mismatch fails `ddl-auto=validate` at boot |
| 2. Service layer | `ContractService` (create/overlap/end-early/reads) + FR-021 cascade into 3 services; service tests incl. R4 oracle | Overlap predicate off-by-one; cascade accidentally deletes (R4) |
| 3. Views & nav | `garages/:id` view (history + create/end), portfolio "rented" badge, tenant-profile list, gating test | Lazy-load outside session; ungated new route (R5); N+1 on rented status |

**Prerequisites:** S-02 and S-03 complete (done). No new dependencies.
**Estimated effort:** ~2–3 sessions across 3 phases.

## Open Risks & Assumptions

- "Today" uses `LocalDate.now()` at the call site here; the injectable-clock requirement (R2) belongs to S-05's overdue engine, not to contract listing — deliberately not introduced now.
- R4 (archive retention) is proven at the mocked-service level this slice; the real-DB "children survive and stay queryable" proof is deferred to test-plan §3 Phase 2.
- Payment day capped at 1–28; an owner wanting "due on the 30th/last day" picks 28 until S-05 revisits.

## Success Criteria (Summary)

- Owner can create a contract from a garage (rent pre-filled), and overlapping contracts are rejected with a clear message.
- Ending a contract early keeps it in the garage history and the tenant profile; the garage flips rented↔free correctly.
- Archiving a tenant, garage, or location retains every underlying contract — no hard delete (`mvnw.cmd verify` green, incl. the R4 oracle and new gating test).
