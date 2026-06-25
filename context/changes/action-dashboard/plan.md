# Action Dashboard (S-06) Implementation Plan

## Overview

Replace the placeholder landing screen with the product's north-star surface: a post-login **action dashboard** at route `""` that lists, in three urgency-ordered, drillable sections, the garages that need the owner's attention today — **overdue payments**, **vacant garages**, and **contracts ending in the next 30 days** (US-01, FR-015–FR-018). The overdue section reuses the already-shipped S-05 derivation; the vacant and ending-soon signals are new derivations added in this slice.

## Current State Analysis

The three signals sit on top of data and patterns that already exist:

- **Overdue is done.** `OverdueService.currentDues()` returns `List<OverdueRow>` (`contractId, garageId, garageLabel, tenantName, amountDue, daysOverdue`) — a fully off-session-safe projection that already satisfies US-01's "tenant name, amount, days overdue". `DuesView` (route `"dues"`) is a working render of it and stays as the dedicated FR-014 per-tenant/portfolio dues view.
- **Vacant is derivable but unbuilt.** `GarageService.listActiveByLocations()` lists active garages; `ContractService.rentedGarageIds(garageIds, today)` returns the rented subset. Vacant = active − rented. There is **no stored "vacant since" date** — US-01 wants a vacancy duration, so it must be derived.
- **Ending-soon is unbuilt.** `Contract` carries `plannedEndDate`, `endedOn`, and `isActiveOn(today)`; there is no query for "ending in a window". A new `JOIN FETCH` query + service method is needed.
- **View scaffolding to copy.** `MainLayout` (AppLayout + `SideNav`), `Grid` + `addComponentColumn` link cells, `UI.getCurrent().navigate("garages/" + id)`, per-section empty-state `Paragraph`, Lumo badges. `HomeView` currently owns route `""` and is a placeholder to be replaced. No view tests exist; services/rules are unit-tested (e.g. `OverdueRuleTests`).
- **Hard constraints.** `spring.jpa.open-in-view=false` → every entity-fetching query that renders an association off-session must `JOIN FETCH` it (AGENTS.md). Services inject repositories via `ObjectProvider<T>` to stay constructible in the DB-free test context. Active/ended is derived (`endedOn`), never a stored flag. Constructor injection only; `@Service` / `@RestController` / `@Configuration`, never plain `@Component`. Package by feature.

### Key Discoveries:

- `OverdueService.currentDues()` → `List<OverdueRow>` at `src/main/java/com/example/garageops/payments/OverdueService.java:59` — reuse verbatim for the overdue section.
- `OverdueRow` record at `src/main/java/com/example/garageops/payments/OverdueRow.java:1` — the template for the two new row records.
- `ContractService.rentedGarageIds(List<Long>, LocalDate)` → `Set<Long>` at `src/main/java/com/example/garageops/contracts/ContractService.java:103` — the basis for the vacant derivation; mirrors `LocationsView` usage at `LocationsView.java:107`.
- `ContractRepository.findActiveForOverdue()` at `src/main/java/com/example/garageops/contracts/ContractRepository.java:40` — the `JOIN FETCH c.garage join fetch c.tenant` pattern the ending-soon query copies.
- `ArchivableEntity.getCreatedAt()` → `Instant` at `src/main/java/com/example/garageops/persistence/ArchivableEntity.java:70` — the vacancy-since fallback for a never-rented garage.
- `ClockConfig` (Europe/Warsaw `Clock`) injected into `OverdueService` — the same injected `Clock` derives "today" for all three signals deterministically.
- `MainLayout.java:52` registers nav via `new SideNavItem("…", SomeView.class)`; `SecurityConfig.java:36` wires `loginView(LoginView.class)`; logged-in users land on route `""`.

## Desired End State

After login the owner lands on `DashboardView` (route `""`). It shows three sections in this order — Overdue, Vacant, Ending soon — each with a count in its header (e.g. "Overdue (3)"), rows sorted most-urgent-first, and a friendly empty line when the section has nothing. Every row drills to the garage detail (`garages/:id`). The data is recomputed server-side each time the owner navigates to the dashboard — no manual browser refresh needed. The standalone Dues page (`"dues"`) is unchanged. New derivation logic (vacant + ending-soon, including the vacancy-since fallback and the 30-day boundary) is covered by service-layer unit tests; `mvnw.cmd verify` is green.

Verification: log in → land on the dashboard → see the three sections with correct counts and ordering → click a row → arrive at the right garage detail → with a fresh portfolio every section shows its friendly empty copy.

## What We're NOT Doing

- **No problem-flagged section** — the dashboard ships the three FR-mandated signals only; the problem flag stays on the portfolio/garage views where it already lives.
- **No live `@Push` updates** — freshness means recompute-on-navigation, not real-time streaming to an open tab.
- **No removal or rework of `DuesView`** — it remains the dedicated FR-014 dues view at `"dues"`.
- **No new contract-detail route** — all drill-throughs target the existing garage detail.
- **No Vaadin/view test harness** — view rendering is verified manually, consistent with the current repo (zero view tests).
- **No long-vacant flag (FR-019)** — parked; this slice only groundworks vacancy-since.
- **No monthly-revenue total or any non-action signal** — out of scope per PRD Non-Goals.

## Implementation Approach

Build the two missing derivations behind feature services first (Phase 1), each returning an off-session-safe row record so the view never touches a lazy association, then assemble the landing view that composes all three signals and swaps the landing route (Phase 2). Overdue is consumed as-is. The vacant derivation lives in a new `dashboard`-feature service (it spans garages + contracts and belongs to neither cleanly); the ending-soon query is a natural addition to `ContractRepository` / `ContractService`. All "today"/"now" derivations flow from the injected `Clock` so tests are deterministic.

## Critical Implementation Details

- **Vacancy-since semantics.** A garage is vacant if it is active (not archived) and has no contract active on `today`. Its vacancy-since date is `max(endedOn)` across that garage's contracts; if the garage has never had an ended contract (never rented, or only future/active ones), fall back to `getCreatedAt()` converted to a `LocalDate` in the clock's zone. Vacancy duration = whole days between vacancy-since and today.
- **Ending-soon boundary.** Include active, non-archived contracts whose `plannedEndDate` is within `[today, today + 30 days]` inclusive on both ends; exclude already-ended (`endedOn != null`) contracts. Days-remaining = whole days from today to `plannedEndDate`.
- **Fetch-joins.** The ending-soon query must `JOIN FETCH c.garage join fetch c.tenant` (rendered off-session). The vacancy-since aggregation returns a projection (ids + dates only), so it needs no fetch.

## Phase 1: Signal derivation backend

### Overview

Add the vacant-garage and ending-soon derivations as services returning off-session-safe row records, with the repository queries they need, and unit-test the date logic. No UI in this phase.

### Changes Required:

#### 1. Ending-soon repository query

**File**: `src/main/java/com/example/garageops/contracts/ContractRepository.java`

**Intent**: Add a query returning active, non-archived contracts whose planned end falls within a date window, with the associations the dashboard renders fetched eagerly.

**Contract**: New method `List<Contract> findEndingBetween(LocalDate from, LocalDate to)` using JPQL `select c from Contract c join fetch c.garage join fetch c.tenant where c.endedOn is null and c.archivedAt is null and c.plannedEndDate between :from and :to order by c.plannedEndDate asc`. Mirrors `findActiveForOverdue()` at line 40.

#### 2. Vacancy-since aggregation query

**File**: `src/main/java/com/example/garageops/contracts/ContractRepository.java`

**Intent**: For a set of garage ids, return the most recent contract end date per garage, so the dashboard can compute how long each vacant garage has been empty without an N+1 scan.

**Contract**: New method `List<GarageLastEnded> findLastEndedByGarageIdIn(List<Long> garageIds)` returning an interface projection `interface GarageLastEnded { Long getGarageId(); LocalDate getLastEnded(); }`, backed by `select c.garage.id as garageId, max(c.endedOn) as lastEnded from Contract c where c.garage.id in :garageIds and c.endedOn is not null group by c.garage.id`. Projection-only (no fetch needed). Follows the `ContractPaidSum` projection pattern in `PaymentRepository`.

#### 3. Vacant-garage row record

**File**: `src/main/java/com/example/garageops/dashboard/VacantGarageRow.java`

**Intent**: Off-session-safe value type for a vacant-garage dashboard row.

**Contract**: `record VacantGarageRow(Long garageId, String garageLabel, String locationName, long daysVacant)`. All strings resolved at construction (no lazy entity refs), per the `OverdueRow` pattern.

#### 4. Ending-soon row record

**File**: `src/main/java/com/example/garageops/dashboard/EndingSoonRow.java`

**Intent**: Off-session-safe value type for an ending-soon dashboard row.

**Contract**: `record EndingSoonRow(Long contractId, Long garageId, String garageLabel, String tenantName, LocalDate plannedEndDate, long daysRemaining)`.

#### 5. Dashboard signal service

**File**: `src/main/java/com/example/garageops/dashboard/DashboardService.java`

**Intent**: Compute the two new signals. `vacantGarages()` lists active garages, subtracts `rentedGarageIds(..., today)`, and for each remaining garage derives vacancy-since from the last-ended aggregation (fallback to `getCreatedAt()` in the clock zone), returning rows sorted by `daysVacant` desc. `endingSoon()` calls the new window query for `[today, today+30]` and maps to rows sorted by `daysRemaining` asc.

**Contract**: `@Service` class, constructor injection with `ObjectProvider<…>` for repositories (per the existing service pattern), injected `Clock`. Public methods `List<VacantGarageRow> vacantGarages()` and `List<EndingSoonRow> endingSoon()`. Reuses `GarageService`/`ContractService` where natural (e.g. `rentedGarageIds`); reads are not `@Transactional` but must resolve all fetched data before returning.

### Success Criteria:

#### Automated Verification:

- Build + full test suite pass: `mvnw.cmd verify`
- New service tests pass: `mvnw.cmd test -Dtest=DashboardServiceTests`
- No `@ManyToOne` added without explicit `fetch = FetchType.LAZY` (grep check per AGENTS.md)

#### Manual Verification:

- `vacantGarages()` returns a never-rented garage with `daysVacant` measured from its creation date (fallback path).
- `vacantGarages()` returns a garage whose last tenant moved out, with `daysVacant` from the most recent `endedOn`.
- `endingSoon()` includes a contract ending exactly 30 days out and excludes one ending in 31 days and one already ended.

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding to Phase 2.

---

## Phase 2: Dashboard view & landing wiring

### Overview

Build `DashboardView` at route `""`, composing the three signals into urgency-ordered sections with counts, empty states, and garage drill-through; remove the placeholder `HomeView`; add the nav entry. Keep `DuesView` untouched.

### Changes Required:

#### 1. Dashboard view

**File**: `src/main/java/com/example/garageops/dashboard/DashboardView.java`

**Intent**: The post-login landing screen. Renders three sections — Overdue (from `OverdueService.currentDues()`), Vacant (`DashboardService.vacantGarages()`), Ending soon (`DashboardService.endingSoon()`) — each a header with a count, a `Grid` of rows, and a friendly empty `Paragraph` when the list is empty. Each row drills to `garages/:id`. Recomputes on attach/navigation.

**Contract**: `@Route(value = "", layout = MainLayout.class)` `@PermitAll` class extending `VerticalLayout`. Constructor-injects `OverdueService` and `DashboardService`. Columns per US-01: Overdue → garage label (link), tenant name, amount due, days overdue (badge), sorted by days overdue desc; Vacant → garage label (link), location, days vacant, sorted desc; Ending soon → garage label (link), tenant, planned end date, days remaining, sorted asc. Garage link via `UI.getCurrent().navigate("garages/" + id)` (LocationsView pattern). Empty copy: "No overdue payments.", "No vacant garages.", "No contracts ending soon." Follows the `DuesView` / `LocationsView` Grid + empty-state idiom; recompute via a `refresh()` called from the constructor (matching existing views' load-on-construct).

#### 2. Remove placeholder landing view

**File**: `src/main/java/com/example/garageops/ui/HomeView.java`

**Intent**: Delete the placeholder so `DashboardView` owns route `""`. Confirm no other code references `HomeView`.

**Contract**: File deleted; no remaining references (grep `HomeView`).

#### 3. Navigation entry

**File**: `src/main/java/com/example/garageops/ui/MainLayout.java`

**Intent**: Add a "Dashboard" nav item pointing at `DashboardView`, listed first.

**Contract**: `nav.addItem(new SideNavItem("Dashboard", DashboardView.class))` added as the first item at `MainLayout.java:52`-area, ahead of Locations/Tenants/Dues.

### Success Criteria:

#### Automated Verification:

- Build + full test suite pass: `mvnw.cmd verify`
- App boots: `mvnw.cmd spring-boot:run` starts without error and `/actuator/health` is UP
- No dangling references to the removed `HomeView` (grep returns nothing)

#### Manual Verification:

- After login the owner lands on the dashboard (route `""`), not a placeholder.
- The three sections appear with correct counts and most-urgent-first ordering.
- Clicking an overdue / vacant / ending-soon row navigates to the correct `garages/:id` detail.
- With a fresh (empty) portfolio, each section shows its friendly empty line.
- Recording a payment / ending a contract elsewhere, then returning to the dashboard, reflects the change (no manual browser refresh).
- The standalone Dues page (`"dues"`) still works unchanged.
- Dashboard is usable on a phone-sized viewport (NFR mobile).

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation that manual testing succeeded.

---

## Testing Strategy

### Unit Tests:

- `DashboardServiceTests` — vacancy-since from most-recent `endedOn`; vacancy-since fallback to creation date for a never-rented garage; vacant excludes garages with a contract active today; ending-soon includes the 30-day boundary and excludes day-31 and already-ended contracts; ordering of both row lists. Use a fixed `Clock` like `OverdueRuleTests`.

### Integration Tests:

- None new (consistent with repo). Overdue continues to be covered by existing S-05 tests.

### Manual Testing Steps:

1. Log in with an empty portfolio → land on dashboard → confirm three friendly empty lines and "(0)" counts.
2. Add a location, garage, tenant, contract; record no payment past the due date → overdue section shows the row with correct days overdue.
3. End a contract → its garage appears under Vacant with days-vacant from the end date.
4. Create a contract ending within 30 days → it appears under Ending soon with correct days remaining.
5. Click each section's row → confirm it lands on the right garage detail.
6. Repeat the dashboard visit after each mutation → confirm data is current without a browser refresh.
7. Shrink to phone width → confirm all three sections are readable and rows drillable.

## Performance Considerations

Target scale is small (single owner, low QPS, small data) per the PRD, so straightforward per-load recomputation is fine. The only N+1 risk — vacancy-since per garage — is avoided by the batched `findLastEndedByGarageIdIn` aggregation. Overdue already batches its paid-sum aggregation.

## Migration Notes

No schema changes — all three signals derive from existing tables (contracts, garages, payments). No data migration.

## References

- Roadmap slice S-06: `context/foundation/roadmap.md:155`
- PRD US-01 + FR-015–FR-018: `context/foundation/prd.md:49`, `:104`
- Overdue reuse: `src/main/java/com/example/garageops/payments/OverdueService.java:59`, `OverdueRow.java:1`
- Vacant basis: `src/main/java/com/example/garageops/contracts/ContractService.java:103`, `LocationsView.java:107`
- Fetch-join pattern: `src/main/java/com/example/garageops/contracts/ContractRepository.java:40`
- View/nav patterns: `src/main/java/com/example/garageops/payments/DuesView.java`, `ui/MainLayout.java:52`, `ui/HomeView.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Signal derivation backend

#### Automated

- [x] 1.1 Build + full test suite pass: `mvnw.cmd verify` — e1a9a87
- [x] 1.2 New service tests pass: `mvnw.cmd test -Dtest=DashboardServiceTests` — e1a9a87
- [x] 1.3 No `@ManyToOne` added without explicit `fetch = FetchType.LAZY` (grep check) — e1a9a87

#### Manual

- [ ] 1.4 `vacantGarages()` never-rented garage uses creation-date fallback for `daysVacant`
- [ ] 1.5 `vacantGarages()` rented-then-vacated garage uses most-recent `endedOn`
- [ ] 1.6 `endingSoon()` includes the 30-day boundary, excludes day-31 and already-ended contracts

### Phase 2: Dashboard view & landing wiring

#### Automated

- [x] 2.1 Build + full test suite pass: `mvnw.cmd verify`
- [x] 2.2 App boots and `/actuator/health` is UP: `mvnw.cmd spring-boot:run`
- [x] 2.3 No dangling references to the removed `HomeView` (grep returns nothing)

#### Manual

- [ ] 2.4 After login the owner lands on the dashboard at route `""`
- [ ] 2.5 Three sections show correct counts and most-urgent-first ordering
- [ ] 2.6 Each row drills to the correct `garages/:id` detail
- [ ] 2.7 Empty portfolio shows each section's friendly empty line
- [ ] 2.8 Mutating data elsewhere then returning reflects the change without a browser refresh
- [ ] 2.9 Standalone Dues page (`"dues"`) still works unchanged
- [ ] 2.10 Dashboard usable on a phone-sized viewport
