---
date: 2026-06-15T00:00:00+02:00
researcher: sant11
git_commit: e549ded4feea2853884a572457ef6f92a23049e3
branch: develop
repository: garage
topic: "Record payments & derive overdue (FR-012/013/014) — S-05, with forward hooks for S-06 dashboard & S-07 late-payer flag"
tags: [research, codebase, payments, overdue, contracts, jpa, vaadin]
status: complete
last_updated: 2026-06-15
last_updated_by: sant11
---

# Research: Payments & Overdue (S-05)

**Date**: 2026-06-15T00:00:00+02:00
**Researcher**: sant11
**Git Commit**: e549ded4feea2853884a572457ef6f92a23049e3
**Branch**: develop
**Repository**: garage

## Research Question

For the `payments-overdue` change (roadmap slice **S-05: payments-and-overdue**, PRD FR-012 / FR-013 / FR-014): map the existing codebase patterns needed to build a `Payment` entity, the FR-013 overdue-derivation rule, and the dues/overdue views — and shape the overdue derivation so the dashboard (**S-06**) and the late-payer flag (**S-07**) can consume it. Scope: **S-05 + forward hooks** (note the seams, don't design S-06/S-07). Focus: entity/persistence patterns, service/repository patterns, Vaadin view patterns, and the FR-013 rule design.

## Summary

S-05 builds the **entire payments side from scratch** — there is no `Payment` entity, repository, service, table, or view yet; migrations stop at `V6`. Everything it needs to mirror already exists in the `contracts` slice, which was deliberately shaped to hand off to S-05.

The single most consequential finding is a **forward-hook tension** (detailed in [FR-013 Rule Design](#fr-013-overdue-rule-design) §4): the dashboard (S-06, FR-015) wants a **point-in-time "currently overdue"** status that the codebase's "derived, never stored" grain serves cleanly; but the late-payer flag (S-07, FR-020) needs **≥2 overdue *events* in 6 months**, which a today-only boolean **structurally cannot** reconstruct. **S-05's overdue rule must be as-of-date parameterized** (`overdueStatusAsOf(period, asOfInstant)`) so S-07 can re-derive history per past period — designing it with a `today`-only signature is the trap to avoid.

Two concrete data gaps S-05 must close, both pre-assigned by the contracts slice:
1. **`grace_days` does not exist** on `Contract` or in the schema — deliberately deferred to S-05 (`V6__contracts.sql:8`). Needs a forward `V7` migration.
2. **An injectable `Clock` (fixed zone) was explicitly deferred to S-05** (`rental-contracts/plan.md:51`); contracts use `LocalDate.now()` at the call site. This is test-plan risk **R2** (boundary/timezone) and is a known obligation, not a fresh discovery.

The established patterns are uniform and well-documented: `@Service` + `ObjectProvider<Repo>` constructor injection, `@Transactional` on writes only (never `readOnly`), `@ManyToOne(LAZY)` + explicit `JOIN FETCH` for off-session rendering, batch `IN`-queries to avoid N+1, **no aggregation queries anywhere yet** (overdue summing is greenfield), Vaadin `@Route` + `HasUrlParameter<Long>` detail views rendering entities directly (no DTOs), and entity unit tests + Mockito-mocked service unit tests (`*Tests` naming).

## Detailed Findings

### Entity & Persistence Patterns

The `Payment` entity should **extend `ArchivableEntity`** (which extends `BaseEntity`) and mirror `Contract` exactly.

- **Inheritance chain** — `BaseEntity` (`persistence/BaseEntity.java:15-25`) gives `Long id` via `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`. **No `@Version`/optimistic locking, no `equals`/`hashCode`.** `getId()` is protected; subclasses widen it to public (see `Contract.java:88-91`).
- **`ArchivableEntity`** (`persistence/ArchivableEntity.java:28-77`) adds `archived_at` (TIMESTAMPTZ, nullable), `created_at` / `updated_at` (TIMESTAMPTZ, NOT NULL) stamped by `@PrePersist`/`@PreUpdate` (lines 41-49), plus idempotent `archive()` and `isArchived()`. **No DB DEFAULT on audit columns — JPA callbacks own them.**
- **`Contract` field inventory** (`contracts/Contract.java:37-152`) — the FK target for `Payment`:
  | Field | Type | Annotations | Column |
  |---|---|---|---|
  | `tenant` | `Tenant` | `@ManyToOne(fetch=LAZY, optional=false) @JoinColumn(name="tenant_id", nullable=false)` | `tenant_id` BIGINT FK |
  | `garage` | `Garage` | `@ManyToOne(fetch=LAZY, optional=false) @JoinColumn(name="garage_id", nullable=false)` | `garage_id` BIGINT FK |
  | `startDate` | `LocalDate` | `@NotNull` | `start_date` DATE |
  | `plannedEndDate` | `LocalDate` | `@NotNull` | `planned_end_date` DATE |
  | `monthlyRent` | `BigDecimal` | `@NotNull @Positive` | `monthly_rent` NUMERIC(10,2) |
  | `paymentDayOfMonth` | `int` | `@Min(1) @Max(28)` | `payment_day_of_month` INTEGER |
  | `endedOn` | `LocalDate` | (nullable) | `ended_on` DATE |
- **Type → SQL mapping** (from V3/V5/V6): `Long`→`BIGINT GENERATED ALWAYS AS IDENTITY`, money `BigDecimal`→`NUMERIC(10,2)`, `LocalDate`→`DATE`, `int`→`INTEGER`, `Instant`→`TIMESTAMPTZ` (plain `TIMESTAMP` **fails `ddl-auto=validate`**), required `String`→`TEXT NOT NULL`, nullable `String`→`TEXT`. FK = `BIGINT NOT NULL REFERENCES <table>(id)`. Snake_case columns; FK named `<singular>_id`.
- **No `@OneToMany` back-collections** ("no-parent-collection rule"). `Payment`→`Contract` is a **child-side `@ManyToOne(LAZY)` only**; the overdue sum comes from a repository query, never `contract.getPayments()`.
- **Migration:** next file is **`V7__payments.sql`** under `src/main/resources/db/migration/`. V1–V6 are immutable (forward-only). Follow the V6 header convention (FR refs + type-mapping comment).

### Service & Repository Patterns

- **Service idiom** (`contracts/ContractService.java:35-49`): `@Service`, constructor injection via **`ObjectProvider<Repo>`** (not direct injection) so services unit-test DB-free; repos resolved per-call with `.getObject()` behind private helpers like `contracts()`. `GarageService`/`TenantService` are identical.
- **`@Transactional` convention:** method-level, **write methods only** (`create`, `endEarly`, `archive`). **`readOnly` is never used.** Read finders have **no** `@Transactional` — which is exactly why repositories must `JOIN FETCH` associations they render (open-in-view=false).
- **Repository query types** (`contracts/ContractRepository.java`):
  - Derived names for simple filters: `findByGarageIdAndEndedOnIsNull` (`:32`), `findByArchivedAtIsNull...`.
  - `@Query` + `JOIN FETCH` when rendering an association off-session: `findByGarageIdOrderByStartDateDesc` (`:22-24`, `join fetch c.tenant`), `findByTenantIdOrderByStartDateDesc` (`:27-29`, `join fetch c.garage`).
  - Batch `IN`-queries to avoid N+1: `findNonEndedByGarageIdIn` (`:38-39`).
- **No aggregation queries exist** — `grep` for `SUM`/`COUNT`/`GROUP BY` returns nothing. The codebase precedent is to **fetch rows and sum via Java streams in the service** (see `ContractService.rentedGarageIds()` stream filter, ~`:107-110`). The planner must decide whether overdue summing follows that precedent or introduces the first JPQL `SUM ... GROUP BY` (see Rule Design §1).
- **Archive cascade** (`GarageService.archive()` ~`:83-92`, `TenantService.archive()` ~`:77-86`): no JPA cascade — load children via `findBy...ArchivedAtIsNull`, loop `Contract::archive`, `saveAll`. **No hard deletes anywhere** (FR-021). When a contract is archived, S-05 must cascade-archive its payments the same loop-and-stamp way.
- **Test harness:**
  - Entity tests — `*Tests` (singular, e.g. `ContractTests`): plain JUnit, no Spring/DB, factory method builds entities, AssertJ.
  - Service tests — `*Tests` (e.g. `ContractServiceTests`): plain unit tests with **Mockito-mocked repos wrapped in mocked `ObjectProvider`** via a `providerOf(mock)` helper; BDD `given()`/`verify()`; AssertJ; static test constants (`RENT = new BigDecimal("250.00")`). Method names are verbose action-subject-outcome.
  - `@SpringBootTest` reserved for smoke/security-gating only; **no `@DataJpaTest`** in the repo.

### Vaadin View Patterns

Views render **entities directly — no DTOs** (services hand back JOIN-FETCHed entities; open-in-view=false makes the fetch-join mandatory).

- **Detail view idiom** (`garages/GarageDetailView.java:55`, `tenants/TenantProfileView.java:46`): `@Route(value="garages", layout=MainLayout.class)` + `@PageTitle` + `@PermitAll`, `implements HasUrlParameter<Long>`; `setParameter` fetches via service and throws `NotFoundException` on `EntityNotFoundException` → 404; a single private `refresh()` does `removeAll()` + re-fetch.
- **List view idiom** (`tenants/TenantsView.java`, `locations/LocationsView.java`): `@Route` over `MainLayout`, H2 title + primary `Button` in a `BETWEEN`-justified `HorizontalLayout`, content `VerticalLayout` cleared on `refresh()`, **empty-state `Paragraph`** fallback (matches US-01's friendly empty copy requirement).
- **Grid + drill-through** (`tenants/TenantProfileView.java:113-125`): `new Grid<>(T.class, false)`, `.addColumn(getter)` + `.addComponentColumn(this::cell)` for links/badges/actions, `.setAutoWidth(true)`, `.setAllRowsVisible(true)`. Drill-through cells are `Button(label, e -> UI.getCurrent().navigate("garages/" + id))` with `LUMO_TERTIARY_INLINE`. Status badges: `Span` + `getElement().getThemeList().add("badge success")`.
- **Forms — two patterns:**
  - **`Binder<T>`** (`LocationsView.java:199-227`): bind to a **throwaway bean** (never the live list entity), `.forField(f).asRequired(msg).withValidator(...).bind(getter, setter)`, `binder.validate().isOk()` before save. Combined setters use `(e,v)->e.edit(v, e.getOther())` to preserve the other field.
  - **Manual validation, no Binder** (`GarageDetailView.java:176-237`, the *new-contract* dialog — closest precedent for a record-payment form): `Dialog`, fields validated by hand, errors via field `.setInvalid(true)/.setErrorMessage(...)` or an error `Paragraph` (`--lumo-error-text-color`); **service-layer `IllegalStateException` is caught, shown in the Paragraph, and the dialog stays open**; success → `dialog.close()` + `refresh()`.
- **Field components:** money = `BigDecimalField` (validate `v != null && v.signum() > 0`); dates = `DatePicker` (`LocalDate`); day-of-month = `IntegerField` with `.setMin(1).setMax(28).setStepButtonsVisible(true)`; entity choice = `ComboBox` with `setItems(...)` + `setItemLabelGenerator`.
- **No `Notification` usage in the codebase** — `refresh()` is the success signal.
- **Navigation** (`ui/MainLayout.java:51-54`): `SideNav` with `nav.addItem(new SideNavItem("Tenants", TenantsView.class))`. A new `SideNavItem("Dues", DuesView.class)` auto-routes via `@Route`. Landing view is `ui/HomeView.java` at `@Route("")` (the future S-06 dashboard target).

### FR-013 Overdue Rule Design

**Confirmed gaps vs FR-013** (`Contract.java`, `V6__contracts.sql`): `monthlyRent` ✅, `paymentDayOfMonth` ✅ (capped **1–28**), `startDate`/`plannedEndDate`/`endedOn` ✅; **`grace_days` ❌ missing (deferred to S-05)**; **no `Payment` entity ❌**.

**1. Where the derivation belongs (trade-offs — planner picks):**
- *Pure rule unit* (entity method like `isActiveOn` at `Contract.java:121`, or a small `OverdueRule` class) taking `(monthlyRent, paidInPeriod, paymentDay, graceDays, asOf)` → status + daysOverdue. Cleanest fit with test-plan R2's "extractable, dependency-free unit"; can't reach payments itself (no-parent-collection rule), so it takes the period's paid-sum as an argument.
- *Service method* — matches existing services, natural `@Transactional` boundary, must batch to avoid N+1.
- *JPQL/SQL aggregation* (`SUM(amount) GROUP BY contract` filtered to period) — one round-trip, ideal for the S-06 portfolio scan, but date arithmetic for the due instant is dialect-specific and hard to unit-test with an injected clock. Would be the **first aggregation query in the codebase**.
- *DTO projection* (`OverdueRow(contractId, garageLabel, tenantName, amountDue, daysOverdue)`) — open-in-view-safe by construction; US-01 already names these columns (`prd.md:54`). Pairs with one of the above; doesn't itself decide where the rule runs.
- **Likely-clean composition (confirm, not picked):** pure rule unit fed by a **batch aggregation query** for `paidInPeriod`, surfaced as a **DTO projection** to views. Satisfies testability + off-session rendering together.

**2. "Current period" & due instant:** period = the calendar month; with `paymentDayOfMonth` capped 1–28 the due date `paymentDay + graceDays` always lands in/just-after the same month — **the `payment_day=31`/February edge is structurally impossible today**, an argument to *keep* the cap. **Decision needed:** which month is "current" relative to `today` — a period is only evaluable as overdue once `today > paymentDay + graceDays` for that period (FR-013 is silent on this). **Timezone (R2):** S-05 must introduce an **injectable `Clock` fixed to a zone (Europe/Warsaw)** so classification is CI-zone-independent.

**3. Partial-payment summing (R3):** compare `SUM(payments in period) >= monthlyRent`. **Ambiguity to lock:** "payments *recorded for* the period" vs "payments whose *date* falls in the period" — natural reading is **by the payment's `date` field** (`payment.date BETWEEN periodStart AND periodEnd`). This shapes the `Payment` entity (likely just `amount`, `date`, `note`, `@ManyToOne(LAZY) Contract` — no stored "period" column).

**4. ⚠️ THE forward-hook tension (S-06 vs S-07) — most consequential decision:**
- **S-06 (FR-015)** wants *"garages currently OVERDUE"* → point-in-time, served by a **transient/derived** status (matches "derived, never stored").
- **S-07 (FR-020)** wants *"≥2 overdue **events** in 6 months"* → needs a **history of discrete overdue events**, which a point-in-time boolean **cannot reconstruct** (once paid, the past event vanishes).
- **Fork:** *Option A* — derive history by re-running an **as-of-date** rule per past period (stateless, self-healing; the "as-of" parameter is load-bearing). *Option B* — **persist `overdue_event` rows** (trivial S-07 query, but introduces stored state needing a writer/reconciler; **no scheduler exists in the codebase**, and it cuts against the "derived, never stored" grain + FR-021 retention).
- **Recommendation to surface:** make the rule **as-of-date capable from day one** so S-06 calls it with `today` and S-07 re-derives per past period — keeps Option A viable, defers Option B. **Do not ship a `today`-only signature.**

**5. Carry-over arrears:** FR-013 scopes the check to **one period vs one month's rent** (`prd.md:99`); says nothing about accumulating prior unpaid periods; Non-Goal "no cross-period analytics" (`prd.md:150`) reinforces **per-period only**. Treat `amountDue = monthlyRent − paidThisPeriod`; multi-period arrears out of scope unless owner says otherwise.

**6. `grace_days` representation (Open Q3):** FR-013 says *"each contract carries… a grace period (default 5 days)"* → leans **per-contract column `grace_days INTEGER NOT NULL DEFAULT 5`** (mirrors `payment_day_of_month`, survives as a per-contract historical fact good for S-07 re-derivation). Alternative: **global property** `garageops.overdue.grace-days=5` (simplest to tune per Q3 intent, but changing it retroactively reclassifies historical events — bad for S-07). Real trade-off, left open.

## Code References

- `src/main/java/com/example/garageops/persistence/BaseEntity.java:15-25` — id strategy; no version/equals.
- `src/main/java/com/example/garageops/persistence/ArchivableEntity.java:28-77` — archived/created/updated timestamps, `archive()`/`isArchived()`, `@PrePersist`/`@PreUpdate`.
- `src/main/java/com/example/garageops/contracts/Contract.java:37-152` — FK target field inventory; `isActiveOn(LocalDate)` derived-state precedent (~`:121`).
- `src/main/java/com/example/garageops/contracts/ContractRepository.java:22-39` — `JOIN FETCH` finders + batch `IN`-query patterns.
- `src/main/java/com/example/garageops/contracts/ContractService.java:35-49` — `@Service` + `ObjectProvider` injection; `@Transactional` on writes only.
- `src/main/java/com/example/garageops/contracts/ContractServiceTests.java` — Mockito + `providerOf(mock)` `ObjectProvider` test harness.
- `src/main/java/com/example/garageops/garages/GarageDetailView.java:176-237` — new-contract dialog (manual-validation form, closest precedent for record-payment).
- `src/main/java/com/example/garageops/tenants/TenantProfileView.java:113-125` — Grid + drill-through + status-badge (S-07/FR-014 per-tenant surface).
- `src/main/java/com/example/garageops/ui/MainLayout.java:51-54` — `SideNav` registration; `ui/HomeView.java` `@Route("")` (future S-06 dashboard).
- `src/main/resources/db/migration/V6__contracts.sql:8,17-22` — schema conventions; "grace_days deferred to S-05" note. Next file: `V7__payments.sql`.

## Architecture Insights

- **"Derived, never stored" is the house lifecycle pattern** — active/ended/rented are all computed (`Contract.java:28-31`). Overdue's point-in-time status should follow it; S-07's event-history is the one place that may force a departure.
- **open-in-view=false is the load-bearing constraint** — it dictates JOIN-FETCH-or-project on every off-session read, no `@Transactional` on finders, and pushes toward DTO projections for list/dashboard surfaces (codified in AGENTS.md).
- **No aggregation infrastructure yet** — S-05 introduces the first `SUM`/period logic; the existing precedent is stream-summing in services, but the dashboard's portfolio scan is the natural case for the codebase's first batched aggregation query.
- **No scheduler / background-job infrastructure** — relevant to the Option B (persisted overdue events) trade-off.
- **Single `Clock`/zone obligation pre-assigned to S-05** — the contracts slice intentionally left `LocalDate.now()` at call sites and handed the injectable, zone-fixed clock to this slice (test-plan R2).

## Historical Context (from prior changes)

From `context/archive/2026-06-06-rental-contracts/` (no `research.md`; decisions live in `plan.md` / `plan-brief.md` / `reviews/impl-review.md`):

- **`grace_days` explicitly deferred to S-05** — `plan.md:36`, `plan-brief.md` Key Decisions, `V6__contracts.sql:8`.
- **`payment_day_of_month` capped 1–28 to dodge the month-length trap (R2)** — `plan-brief.md` Open Risks; `Contract.java:62-63`. Feb-31 edge already impossible; keep-vs-relax is S-05's call.
- **Injectable clock (R2) pre-assigned to S-05, not contracts** — `plan.md:51`, `plan-brief.md` Open Risks.
- **No-parent-collection + JOIN FETCH + batch (no N+1) discipline** — `plan.md:41,142`; `ContractRepository.java:22-39`; AGENTS.md.
- **FR-021 retain-on-archive = loop-and-stamp, never delete** — `plan.md:53,166`. If S-05 persists overdue events, they fall under the same retention rule.
- **Recent detail-view refactor → `HasUrlParameter<Long>`** — commit `4d4adc1`; the idiom record-payment/dues detail views should follow.

### Test-plan obligations already pinned for S-05 (`context/foundation/test-plan.md:43-45,67-68,81`)

- **R1** overdue false-negative, **R2** boundary/timezone (**injected clock, fixed zone**), **R3** partial-payment summing — the three decomposed FR-013 risks, all assigned to **§3 Phase 1 "Overdue engine — pure-unit coverage"** with the explicit goal to *"force the calc to be an extractable, dependency-free unit."* Named anti-patterns: single fixed-zone test, system-default zone, testing only the single-full-payment path. This is a strong steer toward the pure-rule-unit + injected-clock placement.

## Related Research

- `context/foundation/prd.md:96-122` (FR-012–FR-015, FR-020), `:54` (US-01 columns), `:150` (no cross-period analytics), `:161` (Q3 grace default).
- `context/foundation/roadmap.md:142-178` (S-05/S-06/S-07), `:198` (Q3).
- `context/archive/2026-06-06-rental-contracts/{plan.md, plan-brief.md, reviews/impl-review.md}`.

## Open Questions

1. **Derived vs persisted overdue history (S-07 fork)** — Option A (as-of-date re-derivation) vs Option B (persisted `overdue_event` rows). Decide in `/10x-plan`; design the rule **as-of-date capable** regardless. *(highest-impact)*
2. **`grace_days` representation** — per-contract column (default 5) vs global property. PRD wording leans per-contract; Q3 tuning-intent leans global.
3. **Period-membership semantics** — a payment belongs to a period by its **`date`** (recommended) vs by recording time. Locks the `Payment` entity shape and the FR-013 sum query.
4. **Which month is "current"** relative to `today`, and the exact "evaluable once `today > paymentDay + graceDays`" boundary (FR-013 is silent).
5. **Keep `payment_day_of_month` 1–28 cap** (Feb-edge stays impossible) vs relax it (then solve short-month clamping). History favors keeping.
6. **Where overdue summing runs** — first JPQL `SUM ... GROUP BY` in the codebase vs service-layer stream-sum precedent.
</content>
</invoke>
