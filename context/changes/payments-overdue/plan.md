# Payments & Overdue (S-05) Implementation Plan

## Overview

Build the entire payments side of GarageOps from scratch (PRD **FR-012 / FR-013 / FR-014**, roadmap slice **S-05**): record rent payments against contracts, and derive a per-period "overdue" status from those payments. The slice mirrors the existing `contracts` slice — which was deliberately shaped to hand off here — and lands the two obligations contracts deferred to S-05: a per-contract `grace_days` and an injectable, zone-fixed `Clock`.

The single load-bearing design choice is that overdue is **derived, never stored**, and the derivation is **as-of-date parameterized** from day one. This serves the S-06 dashboard (call the rule with `today`) and keeps the S-07 late-payer flag viable (re-derive per past period) without persisting any overdue state now. S-06 and S-07 are **not built here** — only the seams are left clean.

## Current State Analysis

There is no `Payment` entity, repository, service, table, or view. Migrations stop at `V6__contracts.sql`. Everything S-05 needs already exists in `contracts` to copy:

- **Persistence**: `ArchivableEntity` (`persistence/ArchivableEntity.java:28-77`) gives `archived_at` + `created_at`/`updated_at` via `@PrePersist`/`@PreUpdate`, plus idempotent `archive()`/`isArchived()`; `BaseEntity` (`persistence/BaseEntity.java:15-25`) gives IDENTITY `Long id`, no `@Version`, no `equals`/`hashCode`. Subclasses widen `getId()` to public (`Contract.java:88-91`).
- **The FK target** is `Contract` (`contracts/Contract.java:37-152`): carries `monthlyRent` (`NUMERIC(10,2)`), `paymentDayOfMonth` (`@Min(1)@Max(28)`), `startDate`/`plannedEndDate`/`endedOn`. Derived-state precedent: `isActiveOn(LocalDate)` (~`:121`). **`grace_days` is absent** (deferred to S-05, `V6__contracts.sql:8`). Contracts use bare `LocalDate.now()` at call sites — the **injectable clock was pre-assigned to S-05** (`rental-contracts/plan.md:51`).
- **Service idiom** (`contracts/ContractService.java:35-49`): `@Service`, constructor injection via `ObjectProvider<Repo>` resolved per-call behind private helpers; `@Transactional` on **write methods only**, never `readOnly`; finders carry no `@Transactional`, so repositories must `JOIN FETCH` any association rendered off-session (`spring.jpa.open-in-view=false`).
- **Repository idiom** (`contracts/ContractRepository.java:22-39`): derived-name finders for simple filters; `@Query` + `JOIN FETCH` for off-session association rendering; batch `IN`-queries to dodge N+1. **No aggregation query (`SUM`/`COUNT`/`GROUP BY`) exists anywhere** — overdue summing is greenfield.
- **Archive cascade** (`GarageService.archive()` ~`:83-92`): no JPA cascade — load children via `findBy...ArchivedAtIsNull`, loop `archive()`, `saveAll`. No hard deletes (FR-021).
- **Vaadin** renders entities directly (no DTOs for detail/list). Detail views: `@Route`+`@PageTitle`+`@PermitAll`, `HasUrlParameter<Long>`, `NotFoundException` on miss, single `refresh()` doing `removeAll()`+re-fetch (`garages/GarageDetailView.java:55`). Forms: the **new-contract dialog** (`GarageDetailView.java:176-237`) is the closest precedent for record-payment — manual validation, service `IllegalStateException` caught and shown in an error `Paragraph`, dialog stays open on failure, success → `close()`+`refresh()`. Grid + drill-through + status badges: `tenants/TenantProfileView.java:113-125`. `SideNav` registration: `ui/MainLayout.java:51-54`. No `Notification` usage; `refresh()` is the success signal.
- **Tests**: entity `*Tests` (plain JUnit, AssertJ, factory builder, no Spring); service `*Tests` (Mockito-mocked repos wrapped in mocked `ObjectProvider` via a `providerOf(mock)` helper, BDD `given()`/`verify()`). `@SpringBootTest` reserved for smoke/security; no `@DataJpaTest`.

## Desired End State

The owner can record a payment against a contract from that contract's detail view, see a contract's and a tenant's recorded payments, and open a **Dues** view listing every garage whose current period is overdue (garage, tenant, amount due, days overdue). Overdue status is computed live from payments via a pure, clock-driven rule that can be asked "as of" any instant — so S-06 and S-07 can later consume it unchanged. Verification: the four phases' automated checks pass (`mvnw.cmd verify`), and manual UI walkthrough confirms record → history → dues reflect each other.

### Key Discoveries:

- **As-of-date is load-bearing** — a `today`-only overdue signature structurally cannot reconstruct S-07's "≥2 events in 6 months." The rule takes an `asOf` instant (research §4).
- **`grace_days` belongs on the contract** (`grace_days INTEGER NOT NULL DEFAULT 5`) — a per-contract historical fact so changing one contract never reclassifies another's past events.
- **Period membership is by `payment.date`** — `SUM(amount) WHERE date BETWEEN periodStart AND periodEnd`; no stored "period" column, keeping `Payment` lean.
- **First aggregation query in the codebase** — a batch `SUM ... GROUP BY contract` over `:contractIds`, feeding the pure rule (research §1, §6).
- **Day cap 1–28 stays** — keeps the short-month due-date edge structurally impossible (`Contract.java:62-63`); no clamping logic.
- **`Payment`→`Contract` is child-side `@ManyToOne(LAZY)` only** — no `@OneToMany` back-collection (no-parent-collection rule); the period sum comes from a repository query, never `contract.getPayments()`.

## What We're NOT Doing

- **Not building the S-06 dashboard** (`HomeView` at `@Route("")`) — only leaving the as-of-date rule + portfolio scan it will call.
- **Not building the S-07 late-payer flag** — only keeping history re-derivable (Option A); no `overdue_event` table, no scheduler, no persisted overdue state.
- **No cross-period arrears / accumulation** — `amountDue = monthlyRent − paidThisPeriod` for one period; PRD Non-Goal "no cross-period analytics" (`prd.md:150`).
- **Not relaxing `payment_day_of_month`** beyond the existing 1–28 cap.
- **No hard-delete UI** — archive-only (FR-021); archiving a contract cascade-archives its payments.
- **No global grace-days property** — rejected in favor of the per-contract column.

## Implementation Approach

Engine-first, matching the test-plan's steer to "force the calc to be an extractable, dependency-free unit." Phase 1 builds the pure overdue rule + the `Clock` bean with zero DB dependency (it takes primitives, including the period's paid-sum, as arguments). Phase 2 lands the data model and the batch aggregation query that produces that paid-sum. Phase 3 wires service-layer record/archive writes and an overdue derivation service that joins the aggregation to the rule and projects an `OverdueRow` DTO. Phase 4 builds the three Vaadin surfaces on top.

## Critical Implementation Details

- **Period resolution ("latest fully-due period")** — given an `asOf` instant (via the injected clock), the rule evaluates the most recent calendar month whose due instant (`paymentDayOfMonth + graceDays` days into the month) has passed as-of `asOf`. A still-unpaid earlier month therefore stays overdue until paid, but the rule only ever reasons about **one** period at a time (per-period only; no cross-period accumulation). This is the only non-obvious bit of date logic — keep it in the pure unit, driven by the clock, so R2 (boundary/timezone) is testable without a live clock.
- **Clock zone** — the `Clock` bean is fixed to `Europe/Warsaw`; all overdue date arithmetic resolves "now" through it, never `LocalDate.now()` / `Instant.now()` / system default zone (test-plan named anti-patterns: single fixed-zone test, system-default zone). Route contracts' existing `LocalDate.now()` call sites through the same clock where they intersect overdue logic.

## Phase 1: Overdue engine + Clock

### Overview

A pure, dependency-free overdue rule and a fixed-zone `Clock` bean. No entity, no DB, no Spring context needed to test it.

### Changes Required:

#### 1. Injectable Clock bean

**File**: `src/main/java/com/example/garageops/<config-or-shared>/ClockConfig.java` (new; place under a config package consistent with existing `@Configuration` classes)

**Intent**: Provide a single application `Clock` fixed to `Europe/Warsaw` so all "now"-dependent logic is deterministic and CI-zone-independent (test-plan R2). Tests inject a `Clock.fixed(...)` instead.

**Contract**: `@Configuration` class exposing `@Bean Clock clock()` returning `Clock.system(ZoneId.of("Europe/Warsaw"))`. No `@Component`.

#### 2. Overdue rule (pure unit)

**File**: `src/main/java/com/example/garageops/payments/OverdueRule.java` (new)

**Intent**: Decide, for one contract as of a given instant, which period is currently due and whether it is overdue — independent of persistence (it receives the period's paid-sum as an argument, honoring the no-parent-collection rule).

**Contract**: A method along the lines of `evaluate(BigDecimal monthlyRent, int paymentDayOfMonth, int graceDays, BigDecimal paidInPeriod, Instant asOf)` returning a small result carrying: the resolved period (year-month), `overdue` boolean, `amountDue` (`monthlyRent − paidInPeriod`, floored at 0 / not negative), and `daysOverdue`. The "latest fully-due period" resolution (see Critical Implementation Details) lives here. The method must take `asOf` explicitly — **no `today`-only overload that reads the clock internally** (S-07 re-derives past periods by varying `asOf`). The caller (Phase 3 service) supplies `asOf` from the injected `Clock`.

### Success Criteria:

#### Automated Verification:

- Module compiles: `mvnw.cmd test-compile`
- Pure-unit tests pass: `mvnw.cmd test -Dtest=OverdueRuleTests`
- Tests cover **R1** (overdue false-negative — unpaid period flagged), **R2** (boundary/timezone — due-instant edge evaluated via injected fixed clock across at least two zones-of-evaluation, never system default), **R3** (partial-payment summing — `paidInPeriod < rent` overdue, `== rent` and `> rent` not), plus the not-yet-due case (period whose due instant hasn't passed as-of `asOf`).

#### Manual Verification:

- Spot-check a hand-computed example (e.g. rent 250, day 10, grace 5, asOf mid-month unpaid) returns the expected period, `amountDue`, and `daysOverdue`.

**Implementation Note**: After automated verification passes, pause for human confirmation of the manual spot-check before Phase 2.

---

## Phase 2: Payment data model & migration

### Overview

The `Payment` entity, the `V7` migration (payments table + `grace_days` on contract), the `grace_days` field on `Contract`, and `PaymentRepository` with the batch aggregation query.

### Changes Required:

#### 1. Payment entity

**File**: `src/main/java/com/example/garageops/payments/Payment.java` (new)

**Intent**: Persist one recorded rent payment tied to a contract. Mirrors `Contract`'s structure exactly.

**Contract**: Extends `ArchivableEntity`; widens `getId()` to public. Fields: `amount` (`BigDecimal`, `@NotNull @Positive`, `NUMERIC(10,2)`), `date` (`LocalDate`, `@NotNull`, `DATE`), `note` (nullable `String`, `TEXT`), `contract` (`@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "contract_id", nullable = false)`). **No `@OneToMany` on `Contract`.** Explicit `fetch = LAZY` is mandatory (AGENTS.md).

#### 2. grace_days on Contract

**File**: `src/main/java/com/example/garageops/contracts/Contract.java`

**Intent**: Add the per-contract grace period FR-013 requires, defaulting to 5.

**Contract**: New `int graceDays` field, `@Min(0)`, column `grace_days` `INTEGER NOT NULL`. Surface it through the contract's existing construction/edit path consistent with how `paymentDayOfMonth` is handled. Default 5 applies at the DB and entity level.

#### 3. V7 migration

**File**: `src/main/resources/db/migration/V7__payments.sql` (new)

**Intent**: Create the `payments` table and add `grace_days` to `contracts`. Forward-only; follow the V6 header convention (FR refs + type-mapping comment).

**Contract**: `payments` table — `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, `amount NUMERIC(10,2) NOT NULL`, `date DATE NOT NULL`, `note TEXT`, `contract_id BIGINT NOT NULL REFERENCES contracts(id)`, `archived_at TIMESTAMPTZ`, `created_at TIMESTAMPTZ NOT NULL`, `updated_at TIMESTAMPTZ NOT NULL` (TIMESTAMPTZ, not plain TIMESTAMP — `ddl-auto=validate` fails otherwise). `ALTER TABLE contracts ADD COLUMN grace_days INTEGER NOT NULL DEFAULT 5;`. Consider an index on `contract_id` consistent with existing FK conventions.

#### 4. PaymentRepository

**File**: `src/main/java/com/example/garageops/payments/PaymentRepository.java` (new)

**Intent**: Read payments for history surfaces and provide the batch per-period paid-sum that feeds the overdue rule.

**Contract**: Derived-name finders for history: by contract id and by tenant id (the latter `JOIN FETCH` whatever it renders off-session, ordered by date desc), filtered to non-archived where appropriate. The **batch aggregation** finder: a `@Query` returning per-contract summed `amount` for `contract_id IN (:contractIds)` and `date BETWEEN :periodStart AND :periodEnd` (the codebase's first `SUM ... GROUP BY`). Shape its return so the service can map contractId → paid-sum (projection or `Object[]`/tuple consistent with the repo's style).

### Success Criteria:

#### Automated Verification:

- Compiles: `mvnw.cmd test-compile`
- Schema validates against entities at boot (`ddl-auto=validate`) and migration applies: `mvnw.cmd verify`
- Entity unit tests pass: `mvnw.cmd test -Dtest=PaymentTests`

#### Manual Verification:

- Start the app (`mvnw.cmd spring-boot:run`); confirm `V7` applies cleanly and the app boots with no validation error.
- Existing contracts show `grace_days = 5` after migration.

**Implementation Note**: Pause for human confirmation of the boot/migration check before Phase 3.

---

## Phase 3: Payment & overdue services

### Overview

`PaymentService` for record/archive writes (and the archive cascade), and an overdue derivation service that joins the batch aggregation to the pure rule and projects an `OverdueRow` DTO for views.

### Changes Required:

#### 1. PaymentService

**File**: `src/main/java/com/example/garageops/payments/PaymentService.java` (new)

**Intent**: Record a payment against a contract, and cascade-archive payments when their contract is archived (FR-021 retain-on-archive).

**Contract**: `@Service`, constructor-injected `ObjectProvider<PaymentRepository>` (and `ObjectProvider<ContractRepository>` as needed) resolved per-call behind private helpers, mirroring `ContractService.java:35-49`. `@Transactional` on write methods only. A `record(...)` write that validates and persists a `Payment` for a given contract id; invalid input surfaces as `IllegalStateException` (caught by the view). An `archivePaymentsForContract(...)`-style method that loads non-archived payments via `findBy...ArchivedAtIsNull`, loops `archive()`, `saveAll` — and is invoked from the contract archive path (wire into `ContractService`/`GarageService` archive cascade so payments are stamped, never deleted).

#### 2. Overdue derivation service

**File**: `src/main/java/com/example/garageops/payments/OverdueService.java` (new)

**Intent**: Produce the current overdue picture for one contract and for a batch of contracts (the S-06 portfolio scan seam), by feeding the batch paid-sum aggregation into `OverdueRule` with `asOf = clock.instant()`.

**Contract**: `@Service`, constructor-injected `Clock` + `ObjectProvider<PaymentRepository>` (+ contract/garage/tenant repos as needed for labels). A batch method that: resolves the period(s) to evaluate from the clock, calls the aggregation query once for the contract ids, runs `OverdueRule.evaluate(...)` per contract, and returns only overdue contracts as **`OverdueRow` DTO projections** (`contractId`, garage label, tenant name, `amountDue`, `daysOverdue` — US-01 columns, `prd.md:54`). DTO projection is required because views render off-session (open-in-view=false). Keep the `asOf` threaded from the clock so the same method serves S-07 later by accepting an explicit instant.

#### 3. OverdueRow DTO

**File**: `src/main/java/com/example/garageops/payments/OverdueRow.java` (new)

**Intent**: Off-session-safe row for the Dues view.

**Contract**: A `record` with `contractId`, garage label, tenant name, `amountDue` (`BigDecimal`), `daysOverdue` (`int` or `long`).

### Success Criteria:

#### Automated Verification:

- Compiles: `mvnw.cmd test-compile`
- Service unit tests pass: `mvnw.cmd test -Dtest=PaymentServiceTests,OverdueServiceTests` (Mockito-mocked repos via `providerOf(mock)`; mocked/fixed `Clock`; BDD `given()`/`verify()`).
- Full build green: `mvnw.cmd verify`

#### Manual Verification:

- Trace one contract end-to-end in a unit test or REPL: record a partial payment → `OverdueService` reports it overdue with correct `amountDue`; record the remainder → no longer overdue.

**Implementation Note**: Pause for human confirmation before Phase 4.

---

## Phase 4: Views & navigation

### Overview

The three Vaadin surfaces: record-payment dialog from contract context, per-contract & per-tenant payment history, and the `DuesView` + `SideNav` item.

### Changes Required:

#### 1. Record-payment dialog

**File**: `src/main/java/com/example/garageops/garages/GarageDetailView.java` (edit) — and/or the contract detail surface where a contract is in context

**Intent**: Let the owner record a payment against the in-context contract, reusing the new-contract dialog precedent exactly.

**Contract**: A "Record payment" `Button` opening a `Dialog` with `BigDecimalField` (amount; validate `v != null && v.signum() > 0`), `DatePicker` (date), and an optional note field. Manual validation (no `Binder`); on save, call `PaymentService.record(...)`; catch `IllegalStateException`, show it in an error `Paragraph` (`--lumo-error-text-color`), keep the dialog open; success → `dialog.close()` + `refresh()`. No `Notification`.

#### 2. Payment history surfaces

**Files**: contract detail view (per-contract history) and `tenants/TenantProfileView.java` (per-tenant history)

**Intent**: Show recorded payments (FR-014) where the contract and the tenant are viewed.

**Contract**: A `Grid<Payment>` (or a payment-history DTO if any rendered association would otherwise be lazy off-session) built `new Grid<>(..., false)` with `addColumn(getter)` for amount/date/note, `setAutoWidth(true)`, `setAllRowsVisible(true)`, ordered date-desc, sourced from the repository finders. Friendly empty-state `Paragraph` when there are none.

#### 3. DuesView + navigation

**Files**: `src/main/java/com/example/garageops/payments/DuesView.java` (new); `src/main/java/com/example/garageops/ui/MainLayout.java` (edit)

**Intent**: List every currently-overdue garage with the US-01 columns; reachable from the side nav.

**Contract**: `@Route(value = "dues", layout = MainLayout.class)` + `@PageTitle` + `@PermitAll`. H2 title; content `VerticalLayout` cleared on `refresh()`. A `Grid<OverdueRow>` with columns garage, tenant, `amountDue`, `daysOverdue` (overdue cell may use a `Span` "badge" theme per `TenantProfileView`); drill-through `Button` navigating to the garage detail (`UI.getCurrent().navigate("garages/" + id)`, `LUMO_TERTIARY_INLINE`). Empty-state `Paragraph` ("No overdue dues") when the list is empty. Register `new SideNavItem("Dues", DuesView.class)` in `MainLayout` (`:51-54`). The view calls `OverdueService` with the current clock instant.

### Success Criteria:

#### Automated Verification:

- Compiles and full build green: `mvnw.cmd verify`
- Existing smoke/security tests still pass (route is `@PermitAll` and reachable behind auth).

#### Manual Verification:

- Record a partial payment on a contract → the garage appears in **Dues** with the correct amount due and days overdue.
- Record the remaining amount → the garage drops off **Dues**; the payment shows in both the contract's and the tenant's history.
- Empty states render friendly copy when there are no payments / no dues.
- Archiving a contract retains its payments (still visible per retention) and removes it from active overdue evaluation as expected.

**Implementation Note**: Final phase — confirm the full manual walkthrough with the human.

---

## Testing Strategy

### Unit Tests:

- **`OverdueRuleTests`** (Phase 1, the priority surface) — R1 false-negative, R2 boundary/timezone via injected `Clock.fixed(...)` (multiple evaluation instants, never system default), R3 partial/exact/over payment, and not-yet-due. Pure JUnit + AssertJ, no Spring.
- **`PaymentTests`** (Phase 2) — entity invariants via factory builder.
- **`PaymentServiceTests` / `OverdueServiceTests`** (Phase 3) — Mockito-mocked repos in mocked `ObjectProvider` (`providerOf`), fixed `Clock`; record write, archive cascade (verify stamp-not-delete), and aggregation→rule→DTO mapping including the period-resolution edge.

### Integration Tests:

- `ddl-auto=validate` at boot is the schema/entity contract check (via `mvnw.cmd verify`); no `@DataJpaTest` (none in repo).

### Manual Testing Steps:

1. Boot app; confirm `V7` applies and `grace_days = 5` on existing contracts.
2. Record a partial payment → garage appears in **Dues** with correct amount/days.
3. Record the remainder → garage leaves **Dues**; payment shows in contract + tenant history.
4. Cross a due+grace boundary (set a contract's day/grace, use a payment date) to confirm overdue flips at the expected point.
5. Archive a contract → payments retained, no longer in active dues.

## Performance Considerations

The Dues / portfolio scan must use the **single batch `SUM ... GROUP BY` over `contract_id IN (:ids)`** rather than per-contract queries (avoids N+1; this is the seam S-06 will reuse). Per-contract history finders are small and fine as derived-name queries.

## Migration Notes

`V7` is forward-only and additive: new `payments` table + `grace_days` column with `DEFAULT 5`, so existing rows backfill automatically. V1–V6 remain immutable.

## References

- Research: `context/changes/payments-overdue/research.md`
- Change identity: `context/changes/payments-overdue/change.md`
- Service/repo precedent: `contracts/ContractService.java:35-49`, `contracts/ContractRepository.java:22-39`
- Form precedent: `garages/GarageDetailView.java:176-237`
- Grid/badge/drill-through: `tenants/TenantProfileView.java:113-125`
- Nav: `ui/MainLayout.java:51-54`
- Schema conventions: `src/main/resources/db/migration/V6__contracts.sql:8,17-22`
- Test-plan obligations: `context/foundation/test-plan.md:43-45,67-68,81`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Overdue engine + Clock

#### Automated

- [x] 1.1 Module compiles (`mvnw.cmd test-compile`) — bcbab79
- [x] 1.2 Pure-unit tests pass (`mvnw.cmd test -Dtest=OverdueRuleTests`) — bcbab79
- [x] 1.3 Tests cover R1, R2, R3, and not-yet-due — bcbab79

#### Manual

- [x] 1.4 Hand-computed example spot-check — bcbab79

### Phase 2: Payment data model & migration

#### Automated

- [x] 2.1 Compiles (`mvnw.cmd test-compile`) — 838616e
- [x] 2.2 Schema validates + migration applies (`mvnw.cmd verify`) — 838616e
- [x] 2.3 Entity unit tests pass (`mvnw.cmd test -Dtest=PaymentTests`) — 838616e

#### Manual

- [x] 2.4 App boots, V7 applies cleanly — 838616e
- [x] 2.5 Existing contracts show grace_days = 5 — 838616e

### Phase 3: Payment & overdue services

#### Automated

- [x] 3.1 Compiles (`mvnw.cmd test-compile`) — 6894861
- [x] 3.2 Service unit tests pass (`PaymentServiceTests`, `OverdueServiceTests`) — 6894861
- [x] 3.3 Full build green (`mvnw.cmd verify`) — 6894861

#### Manual

- [x] 3.4 End-to-end partial→full payment flips overdue correctly — 6894861

### Phase 4: Views & navigation

#### Automated

- [x] 4.1 Compiles and full build green (`mvnw.cmd verify`)
- [x] 4.2 Existing smoke/security tests still pass

#### Manual

- [x] 4.3 Partial payment → garage appears in Dues with correct amount/days
- [x] 4.4 Full payment → garage drops off Dues; payment in contract + tenant history
- [x] 4.5 Empty states render friendly copy
- [x] 4.6 Archiving a contract retains payments and removes from active dues
