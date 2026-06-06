# S-03 Tenant Management — Plan Brief

> Full plan: `context/changes/tenant-management/plan.md`
> Research: `context/changes/tenant-management/research.md`

## What & Why

Ship roadmap slice S-03: the owner can add, edit, and archive a tenant (name +
optional contact info) and open a **tenant profile** that will list their
current and past contracts (FR-007, FR-008, FR-021). The profile is built now
because it is the FR-018 drill-through target and the future S-07 late-payer
surface — even though its contract list is empty until S-04.

## Starting Point

A complete, tested archive-only CRUD vertical already exists for
`Location`/`Garage` (entity → repository → service → Vaadin view → Flyway
migration → service tests). Tenants do not exist yet, and no parameterized
detail/profile route exists anywhere in the app.

## Desired End State

A **Tenants** side-nav entry lists active tenants with Add and per-row
View/Edit/Archive actions. **View** opens `tenants/:id`, a profile showing the
name (with an open header slot for a future late-payer badge) and an honest
empty "current and past contracts" section. Archiving is a flag-flip that
retains data; an unknown or archived id 404s.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Contact-info shape | Single optional free-text `contact_info` | Matches FR-007 "minimum name + contact"; avoids `@Email` over-constraint | Plan (research recommended) |
| Edit scope | Edit name + contact in one dialog (`editProfile`) | Tenant has two editable fields, unlike Location's rename-only | Plan |
| Profile route | Build list **and** `tenants/:id` profile now | change.md + FR-008/FR-018 require the drill-through target | Plan (research recommended) |
| Profile navigation | Per-row **View** button | Matches the existing per-row action pattern in `LocationsView` | Plan |
| Contract list / late-payer | Honest empty-state + open header slot; zero structure | "Build the seam, not the dependency" — S-02 precedent | Research |
| Test coverage | R4 service oracle + R5 gating assertion on new routes | Cheap coverage at the first parameterized route's introduction | Plan |
| Real-DB retention test | Deferred | Owned by `test-plan.md` §3 Phase 2, not this feature slice | Research |

## Scope

**In scope:** `Tenant` entity, `TenantRepository`, `V5__tenants.sql`,
`TenantService` (+ R4 oracle test), `TenantsView` (list/CRUD), `TenantProfileView`
(`tenants/:id`), `MainLayout` nav entry, `SecurityGatingTests` extension.

**Out of scope:** any `Contract` entity or contract-facing structure on
`Tenant`; `latePayer` field/badge; structured contact validation; archived-tenant
contract guard; real-DB retention test; test-plan §6 cookbook update;
hard-delete UI.

## Architecture / Approach

Clone the S-02 vertical layer-by-layer — `Tenant` ≈ `Location` (standalone
`ArchivableEntity`, no parent, no collections; archive = idempotent flag-flip,
no cascade). Two deltas: a second editable field (`contactInfo` →
`editProfile`), and the project's first parameterized route
(`@Route("tenants/:id")` + `HasUrlParameter<Long>`). The contract list and
late-payer flag are rendered as a method-boundary seam and a layout slot — no
fake data — so S-04/S-07 graft by changing one method body.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Persistence & domain | `Tenant`, `TenantRepository`, `V5__tenants.sql` | Entity↔migration mismatch (caught at boot by `ddl-auto=validate`, not by tests) |
| 2. Service + R4 oracle | `TenantService` + `TenantServiceTests` (`never().delete*()`) | Accidentally introducing a delete/cascade path |
| 3. Views, nav & gating | `TenantsView`, `TenantProfileView`, nav, gating test | New profile route must 404 on unknown/archived; route must stay owner-gated |

**Prerequisites:** S-01 (auth) and F-02 (JPA persistence) done — both archived.
No `Contract` entity required (seams only).
**Estimated effort:** ~1 session across 3 phases (mechanical clone + one new
route pattern).

## Open Risks & Assumptions

- **R4 (archive-retention)** is safe by construction (flag-flip, no cascade);
  this slice proves it only at the mock level — the persistence-layer proof is
  deferred to test-plan §3 Phase 2.
- **R5 (route-gating)** is fail-closed by default; the new route's gating is
  asserted here, but the error-page-body leak check remains a Phase 2 item.
- **Assumption:** `ddl-auto=validate` confirms the entity↔table match at boot;
  the DB-free unit suite does not exercise it.

## Success Criteria (Summary)

- Owner can add / edit / view / archive tenants through the UI with no manual
  refresh; archiving retains data.
- The profile route renders an honest empty contract section and 404s on
  unknown/archived ids.
- `TenantServiceTests` proves archive deletes nothing; `SecurityGatingTests`
  proves `/tenants` and `/tenants/<id>` redirect anonymous visitors to login.
