# Rental Contracts (S-04) Implementation Plan

## Overview

Build the contract layer that links a tenant to a garage. This is the critical-path slice (roadmap S-04): it completes the garage "rented" status deferred from S-02, fills the contract-history seam left in the S-03 tenant profile, and produces exactly the fields the overdue engine (S-05) and the north-star dashboard (S-06) consume — a required end date, a payment day-of-month, and a derivable active/ended state.

Implements FR-009 (create a contract: tenant + garage, start date, required end date, monthly rent, payment day-of-month), FR-010 (end a contract early on the actual move-out date), FR-011 (view a garage's full rental history), and the FR-021 archive-retention guarantee (ending or archiving never drops a contract).

## Current State Analysis

The persistence, service, and view conventions are fully established by the two prior domain slices (S-02 garages/locations, S-03 tenants) and must be replicated exactly:

- **Persistence base** — `persistence/ArchivableEntity.java` (extends `BaseEntity`) owns `archived_at` / `created_at` / `updated_at` via `@PrePersist`/`@PreUpdate`, an idempotent `archive()`, and `isArchived()`. IDs are `@GeneratedValue(strategy = IDENTITY)`. No `equals`/`hashCode`. Protected no-arg ctor for JPA.
- **`@ManyToOne` rule** — every association is `@ManyToOne(fetch = FetchType.LAZY, optional = false)` with an explicit `@JoinColumn` (AGENTS.md hard rule). Because `spring.jpa.open-in-view=false`, any query whose result renders an association outside the session needs an explicit `JOIN FETCH` — see `garages/GarageRepository.java:18-26` (`findByLocationIdInAndArchivedAtIsNull` join-fetches `location` because `GarageService.listActiveByLocations` groups outside the session).
- **Service conventions** — `@Service`, constructor injection, repositories injected as `ObjectProvider<T>` and resolved per call (keeps beans constructible in the DB-free test context). `require(id)` → `EntityNotFoundException`. `@Transactional` on multi-entity mutations. Mutation via entity methods, never setters. Cross-feature cascade is an explicit loop-and-stamp, **not** JPA cascade — see `locations/LocationService.java:64-72` (`archive` stamps the location, then loops its active garages and stamps each; injects `GarageRepository` directly).
- **View conventions** — `@Route(value = "...", layout = MainLayout.class)` + `@PermitAll`; `Binder<Entity>` bound to a *throwaway* bean so keystrokes never mutate the live grid row; a single `refresh()` re-fetch after every mutation; `ConfirmDialog` for archive with "Records are retained, not deleted." copy; parameterized routes via `HasUrlParameter<Long>` → `setParameter` that throws `NotFoundException` on a bad id (see `tenants/TenantProfileView.java`). New views join the drawer via `nav.addItem(new SideNavItem(...))` in `ui/MainLayout.java`.
- **Pre-cut seams** — `tenants/TenantProfileView.java` `contractsSection()` is a placeholder ("No contract history yet. Contracts arrive in a later slice."); `garages/Garage.java:25` notes "rented status is derived from S-04"; `tenants/Tenant.java` javadoc states S-04 owns the `@ManyToOne Tenant` FK on the child side with **no** `@OneToMany` back-collection.
- **Migrations** — `src/main/resources/db/migration/`, last is `V5__tenants.sql`. `ddl-auto=validate`, so V6 columns must match the entity exactly: `Instant`→`TIMESTAMPTZ`, `BigDecimal`→`NUMERIC`, `Long`→`BIGINT`, `LocalDate`→`DATE`, `int`→`INTEGER`. FKs follow `garages.location_id BIGINT NOT NULL REFERENCES locations(id)`.
- **Tests** — DB-free, mocked. Repositories mocked and wrapped in `ObjectProvider` (`providerOf` helper in `tenants/TenantServiceTests.java`). Archive tests assert the R4 oracle: `verify(repo, never()).delete(any())` (and `deleteById`/`deleteAll`). `security/SecurityGatingTests.java` is `@SpringBootTest` + `@AutoConfigureMockMvc`, mocks `UserDetailsService`, and asserts each new route redirects anonymous to `/login` (R5).

### Key Discoveries:

- `locations/LocationService.java:64-72` — the canonical cross-feature retain-on-archive cascade (loop active children, stamp, `saveAll`; inject the child repository directly). Contract retention reuses this shape.
- `garages/GarageService.java:73-95` — `archive(garageId)` stamps and retains; `listActiveByLocations` is the batch grouped-by-id pattern the "rented" status derivation will mirror to avoid N+1.
- `tenants/TenantProfileView.java` `contractsSection()` and `tenants/Tenant.java` javadoc — the exact seam this slice fills, with the no-`@OneToMany` rule called out.
- `security/SecurityGatingTests.java:76-82` — the template for asserting a new parameterized route (`/tenants/1`) is gated; the new `garages/{id}` route gets the same treatment.

## Desired End State

The owner can open a garage (new `garages/:id` view), see its full rental history (every past and current contract with tenant, dates, rent, status), and create a new contract for a vacant garage by picking a tenant — with the monthly rent pre-filled from the garage default and an overlap attempt rejected with a clear message. The owner can end an active contract early by recording the actual move-out date. Ended contracts remain in the history. The portfolio view (`LocationsView`) shows a garage as "rented" whenever it has a current active contract. A tenant's profile lists all their contracts. Archiving a tenant, garage, or location stamps the underlying contracts (`archived_at`) and retains every row — no contract is ever hard-deleted.

**Verification:** `mvnw.cmd verify` passes (compile + schema validate against V6 + all unit tests including the overlap-rejection and R4 archive-no-delete oracles + the new gating assertions). Manually: create a contract from a garage, confirm the garage flips to "rented" and the tenant profile shows it; attempt an overlapping contract and see the rejection; end it early and confirm it stays in history; archive the parent garage and confirm the contract row survives.

## What We're NOT Doing

- **No payments, no overdue derivation, no `grace_days` field** — that is S-05. We add `payment_day_of_month` (1–28) only; `grace_days` is deliberately deferred.
- **No dashboard** (overdue/vacant/ending-soon) — that is S-06. We expose the data it will consume but build no dashboard view.
- **No late-payer flag** — that is S-07. The tenant-profile header slot stays open.
- **No contract term-editing** — create + end-early only. Changing terms means end + recreate.
- **No real-DB integration tests or Testcontainers/H2 harness** — deferred to test-plan §3 Phase 2. Retention is proven here at the mocked-service level (R4 oracle).
- **No `@OneToMany` collections** on `Tenant`/`Garage`/`Location` — the FK lives only on the `Contract` child side (no-parent-collection rule).
- **No auto-renewal, no open-ended contracts** — end date is required (FR-009); the owner creates a new contract per period.
- **No future-dated contract restriction logic beyond what overlap needs** — start date may be future; status derives from dates (see Critical Implementation Details).

## Implementation Approach

A standard vertical slice following the established three-layer rhythm: data model → service → views, each independently verifiable. The `Contract` entity carries two explicit LAZY `@ManyToOne` FKs (Tenant, Garage); every read path that renders those associations join-fetches them. Lifecycle is date-driven — a contract is *active* when `start_date ≤ today` and it has not ended (`ended_on` null) and `planned_end_date ≥ today`; *ended* once `ended_on` is set or the planned end has passed; *upcoming* when `start_date > today`. `archived_at` is orthogonal and set only by the FR-021 parent cascade. The overlap guard and the "rented" derivation both read "active contract on this garage", computed in the service from contract dates — no stored status, no denormalized flag.

## Critical Implementation Details

- **Active/ended/upcoming is derived, never stored.** A single predicate — `ended_on IS NULL AND start_date ≤ :today AND planned_end_date ≥ :today` — defines "currently active". The overlap check, the garage "rented" status, and the history view's status label all read from this same notion. Keep it in one place (an entity method `isActiveOn(LocalDate)` plus the matching repository query) so S-05/S-06 inherit one definition. "Today" is `LocalDate.now()` at the call site for this slice; do **not** thread an injectable clock here — the injectable-clock requirement (risk R2) belongs to the S-05 overdue engine, not to contract listing.
- **Overlap = date-range intersection on non-ended contracts.** Two contracts on a garage overlap when `a.start ≤ b.plannedEnd AND b.start ≤ a.plannedEnd`, considering only contracts with `ended_on IS NULL` (an ended contract frees the garage from its actual end). The create path queries existing non-ended contracts on the target garage and rejects on any intersection with the proposed window. This is what makes "≤ 1 active contract per garage" — and therefore vacant/rented (R6) — trustworthy.
- **Retain-on-archive cascade is loop-and-stamp, not JPA cascade.** Archiving a tenant or garage must stamp that entity's contracts' `archived_at` and retain them; archiving a location (which already cascades to garages) must reach those garages' contracts too. Inject `ContractRepository` directly into the existing services (mirroring `LocationService`'s `GarageRepository` injection). The R4 guarantee is "no `delete*` ever reaches the contract repository" — assert it in tests.

## Phase 1: Data model — Contract entity, migration, repository

### Overview

Introduce the `Contract` entity, its Flyway migration, and the repository with the finders the service needs (history, active-by-garage for overlap + rented status, by-tenant for the profile, by-parent for archive cascade). No behavior beyond entity-level lifecycle methods.

### Changes Required:

#### 1. Contract entity

**File**: `src/main/java/com/example/garageops/contracts/Contract.java`

**Intent**: The rental agreement linking one tenant to one garage. Extends `ArchivableEntity`. Holds the FR-009 fields plus the `ended_on` actual-end date (FR-010). Carries the lifecycle logic as entity methods (`endEarly`, `isActiveOn`, `isEnded`) so the active/ended notion lives in one place. No `@OneToMany` anywhere.

**Contract**:
- Fields: `@ManyToOne(fetch = FetchType.LAZY, optional = false) Tenant tenant` (`@JoinColumn(name = "tenant_id", nullable = false)`); same for `Garage garage` (`garage_id`); `LocalDate startDate` (`start_date`, not null); `LocalDate plannedEndDate` (`planned_end_date`, not null — FR-009 required end); `BigDecimal monthlyRent` (`monthly_rent`, not null, `@Positive`); `int paymentDayOfMonth` (`payment_day_of_month`, not null); `LocalDate endedOn` (`ended_on`, nullable — set by end-early).
- Constructor `Contract(Tenant, Garage, LocalDate start, LocalDate plannedEnd, BigDecimal rent, int paymentDay)`.
- `endEarly(LocalDate actualEnd)` — sets `endedOn`; rejects an `actualEnd` after `plannedEndDate` or before `startDate` (`IllegalArgumentException`); idempotency/already-ended handling: reject if already ended.
- `boolean isEnded()` → `endedOn != null`.
- `boolean isActiveOn(LocalDate today)` → `endedOn == null && !startDate.isAfter(today) && !plannedEndDate.isBefore(today)`.
- Public getters for the fields the views render (including `getTenant()`, `getGarage()`); public `getId()` override (matches `Tenant`/`Garage`).
- Validation annotations mirror `Garage` (`@NotNull`, `@Positive` on rent). Add a bounds check for `paymentDayOfMonth` in 1–28 (bean-validation `@Min(1) @Max(28)` or constructor guard — match whichever the service can surface as a form error).

#### 2. Flyway migration

**File**: `src/main/resources/db/migration/V6__contracts.sql`

**Intent**: Create the `contracts` table matching the entity exactly (`ddl-auto=validate`). Two FKs to `tenants(id)` and `garages(id)`.

**Contract**: columns `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, `tenant_id BIGINT NOT NULL REFERENCES tenants(id)`, `garage_id BIGINT NOT NULL REFERENCES garages(id)`, `start_date DATE NOT NULL`, `planned_end_date DATE NOT NULL`, `monthly_rent NUMERIC(10,2) NOT NULL`, `payment_day_of_month INTEGER NOT NULL`, `ended_on DATE`, `archived_at TIMESTAMPTZ`, `created_at TIMESTAMPTZ NOT NULL`, `updated_at TIMESTAMPTZ NOT NULL`. Follow the V3/V5 header-comment convention (audit columns carry no DB DEFAULT; V1–V5 immutable). Match `monthly_rent` precision to garages (`NUMERIC(10,2)`).

#### 3. Contract repository

**File**: `src/main/java/com/example/garageops/contracts/ContractRepository.java`

**Intent**: Finders for the four read paths. Every finder whose result renders tenant or garage outside the session join-fetches the needed association (open-in-view=false).

**Contract** (`extends JpaRepository<Contract, Long>`):
- `findByGarageIdOrderByStartDateDesc(Long garageId)` with `@Query ... join fetch c.tenant where c.garage.id = :garageId order by c.startDate desc` — the garage rental-history list (renders tenant name).
- `findByTenantIdOrderByStartDateDesc(Long tenantId)` with `join fetch c.garage` — the tenant-profile contract list (renders garage label/location).
- A finder for non-ended contracts on a garage, e.g. `findByGarageIdAndEndedOnIsNull(Long garageId)` — feeds the overlap check (no fetch needed; dates only).
- A batch finder for active-by-garages, e.g. `@Query("select c from Contract c where c.garage.id in :garageIds and c.endedOn is null")` `findNonEndedByGarageIdIn(List<Long> garageIds)` — feeds the "rented" status derivation across the portfolio in one query.
- Cascade finders: `findByGarageIdAndArchivedAtIsNull(Long garageId)` and `findByTenantIdAndArchivedAtIsNull(Long tenantId)` — the archive cascade loops these.

#### 4. Contract entity unit tests

**File**: `src/test/java/com/example/garageops/contracts/ContractTests.java`

**Intent**: Pure object-behavior tests (no DB), mirroring `GarageTests`/`ArchivableEntityTests`.

**Contract**: cover `endEarly` happy path; `endEarly` rejects an actual end after planned end and before start; reject ending an already-ended contract; `isActiveOn` across the boundaries (before start = not active, on start = active, on planned end = active, after planned end = not active, ended = not active); `isEnded` reflects `endedOn`.

### Success Criteria:

#### Automated Verification:

- Compiles and schema validates against V6: `mvnw.cmd verify`
- Entity unit tests pass: `mvnw.cmd test -Dtest=ContractTests`
- App boots (Flyway applies V6, Hibernate validates the mapping): `mvnw.cmd test -Dtest=GarageopsApplicationTests`

#### Manual Verification:

- `V6__contracts.sql` column names/types/nullability match `Contract` field-by-field (the `ddl-auto=validate` check is the real gate, but eyeball the FK names and `NUMERIC(10,2)` precision).

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 2: Service layer — ContractService + FR-021 retention cascade

### Overview

`ContractService` owns contract creation (with overlap rejection), end-early, and the read paths. Extend the three existing archive paths (`TenantService`, `GarageService`, `LocationService`) to cascade-stamp contracts on parent archive while retaining them.

### Changes Required:

#### 1. ContractService

**File**: `src/main/java/com/example/garageops/contracts/ContractService.java`

**Intent**: The contract lifecycle. Constructor-injects `ContractRepository`, `TenantRepository`, `GarageRepository` as `ObjectProvider`s (resolve per call). Create resolves and validates both parents (reject archived tenant/garage, mirroring `GarageService.add`'s archived-location guard), enforces the overlap rule, and saves. End-early loads, calls `contract.endEarly(actualEnd)`, saves.

**Contract**:
- `Contract create(Long tenantId, Long garageId, LocalDate start, LocalDate plannedEnd, BigDecimal rent, int paymentDay)` — resolves tenant + garage via `require`-style helpers (`EntityNotFoundException`); rejects an archived tenant or garage (`IllegalStateException`); validates `plannedEnd ≥ start` and `paymentDay` in 1–28; runs the overlap guard (`findByGarageIdAndEndedOnIsNull`, reject on any date-range intersection with an `IllegalStateException` carrying a clear message); saves a new `Contract`.
- `void endEarly(Long contractId, LocalDate actualEnd)` — `require` the contract, `contract.endEarly(actualEnd)`, save.
- `List<Contract> historyForGarage(Long garageId)` → `findByGarageIdOrderByStartDateDesc`.
- `List<Contract> forTenant(Long tenantId)` → `findByTenantIdOrderByStartDateDesc`.
- `Set<Long> rentedGarageIds(List<Long> garageIds, LocalDate today)` — from `findNonEndedByGarageIdIn`, keep those `isActiveOn(today)`, collect garage ids; the batch path `LocationsView` uses for the "rented" badge.
- `@Transactional` on `create` and `endEarly`.

#### 2. Retain-on-archive cascade into existing services

**File**: `src/main/java/com/example/garageops/tenants/TenantService.java`, `src/main/java/com/example/garageops/garages/GarageService.java`, `src/main/java/com/example/garageops/locations/LocationService.java`

**Intent**: When a parent is archived, its contracts must be stamped and retained (FR-021), never deleted. Inject `ContractRepository` directly (the `LocationService`→`GarageRepository` precedent) and loop-and-stamp.

**Contract**:
- `TenantService.archive` — after stamping the tenant, load `findByTenantIdAndArchivedAtIsNull(id)`, `forEach(Contract::archive)`, `saveAll`. Becomes `@Transactional`.
- `GarageService.archive` — same, via `findByGarageIdAndArchivedAtIsNull(id)`. Becomes `@Transactional`.
- `LocationService.archive` — already loops active garages; for each archived garage also stamp its contracts (reuse `findByGarageIdAndArchivedAtIsNull`, or a batch `findNonArchivedByGarageIdIn`). No `delete` anywhere.

#### 3. Service + cascade tests

**File**: `src/test/java/com/example/garageops/contracts/ContractServiceTests.java` (and additions to `TenantServiceTests`, `GarageServiceTests`, `LocationServiceTests`)

**Intent**: Mocked-repository unit tests in the `providerOf` style.

**Contract**:
- `create` saves a contract with the given fields (ArgumentCaptor); rejects an archived tenant/garage; rejects `plannedEnd < start`; rejects `paymentDay` outside 1–28.
- **Overlap rejection** (R6 root): given an existing non-ended contract whose window overlaps, `create` throws and saves nothing; given a non-overlapping window (or the existing one is ended), `create` succeeds. Cover the boundary (proposed start == existing planned end).
- `endEarly` sets `endedOn` and saves; propagates the entity's rejection of an out-of-range actual end.
- **R4 archive-no-delete oracle**: in `ContractServiceTests` and in the extended `TenantServiceTests`/`GarageServiceTests`/`LocationServiceTests`, archiving a parent stamps the contracts and `verify(contractRepository, never()).delete(any())` / `deleteById` / `deleteAll`.
- `rentedGarageIds` returns only garages with a currently-active contract (exclude ended, exclude future-start).

### Success Criteria:

#### Automated Verification:

- All service tests pass: `mvnw.cmd test -Dtest=ContractServiceTests`
- Cascade retention tests pass: `mvnw.cmd test -Dtest=TenantServiceTests,GarageServiceTests,LocationServiceTests`
- Full suite green: `mvnw.cmd verify`

#### Manual Verification:

- Read the overlap predicate against the FR worked cases (adjacent windows that touch on a single day, fully-contained windows) and confirm the boundary behavior matches intent.

**Implementation Note**: After this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 3: Views & navigation — garage detail, history, create/end, tenant profile, gating

### Overview

Surface the feature: a new `garages/:id` detail view (info + rental-history grid + create/end actions), the portfolio "rented" badge, the filled tenant-profile contract list, and the new-route gating assertion.

### Changes Required:

#### 1. Garage detail / rental-history view

**File**: `src/main/java/com/example/garageops/garages/GarageDetailView.java`

**Intent**: The drill-through target for a garage (also serves S-06 FR-018). Shows garage label/location/rent and current status, a rental-history grid (FR-011), a "New contract" action that opens the create dialog, and an "End early" action on the active contract. Mirrors `TenantProfileView` (`HasUrlParameter<Long>`, `NotFoundException` on bad id) and `TenantsView`'s dialog/`refresh()`/`ConfirmDialog` patterns.

**Contract**:
- `@Route(value = "garages/:id", layout = MainLayout.class)`, `@PageTitle("Garage")`, `@PermitAll`, `implements HasUrlParameter<Long>`. Resolve the garage via `GarageService` (add a `findActive`/`require`-style accessor if not present; throw `NotFoundException` on miss).
- History grid over `contractService.historyForGarage(id)`: columns tenant name, start, planned end, ended-on, rent, derived status (Active/Ended/Upcoming via `isActiveOn(LocalDate.now())`). `setAllRowsVisible(true)`.
- "New contract" dialog: `Binder` on a throwaway bean; tenant picked from a `ComboBox<Tenant>` over `tenantService.listActive()`; `DatePicker` for start + planned end; rent `NumberField`/`BigDecimalField` pre-filled from `garage.getMonthlyRent()`; payment-day field constrained 1–28. On save, call `contractService.create(...)`; **catch the overlap/validation `IllegalStateException` and show its message in the dialog** (do not close); on success close + `refresh()`.
- "End early" action (only on the active contract row): opens a `DatePicker` dialog (default today), calls `contractService.endEarly(...)`, `refresh()`.
- Tenant name cells link to `tenants/{tenantId}` (drill-through both directions).

#### 2. Portfolio "rented" status + garage drill-through

**File**: `src/main/java/com/example/garageops/locations/LocationsView.java`

**Intent**: Activate FR-005 "rented". Make each garage row link to its `garages/:id` view and show status as rented / free / problem, where "rented" is derived from a current active contract.

**Contract**: after batch-loading garages per location (`garageService.listActiveByLocations`), compute `contractService.rentedGarageIds(allGarageIds, LocalDate.now())` once and render each garage's status: problem (if flagged) → rented (if id in the set) → free. Garage label/row navigates to `garages/{id}`. Keep the single-batch-query discipline (no per-row contract query).

#### 3. Tenant profile contract list

**File**: `src/main/java/com/example/garageops/tenants/TenantProfileView.java`

**Intent**: Replace the `contractsSection()` placeholder with the tenant's real contract history (FR-008).

**Contract**: render a grid over `contractService.forTenant(tenant.getId())` — columns garage label (linking to `garages/{garageId}`), start, planned end, ended-on, rent, derived status. Empty-state copy when the tenant has no contracts ("No contracts yet."). Leave the header badge slot untouched (S-07).

#### 4. Navigation

**File**: `src/main/java/com/example/garageops/ui/MainLayout.java`

**Intent**: No new top-level nav item is required (garages are reached via Locations → garage row). Confirm the drawer is unchanged; the `garages/:id` route is reachable by drill-through only.

**Contract**: no change unless review decides a top-level entry is wanted — default: leave `MainLayout` as is.

#### 5. Route-gating assertion

**File**: `src/test/java/com/example/garageops/security/SecurityGatingTests.java`

**Intent**: Lock the new domain route as owner-gated at introduction (R5), mirroring the `/tenants/1` test.

**Contract**: add `unauthenticatedRequestToGarageDetailRedirectsToLogin` asserting `get("/garages/1")` 3xx-redirects to `/login`.

### Success Criteria:

#### Automated Verification:

- Gating test passes: `mvnw.cmd test -Dtest=SecurityGatingTests`
- Full build + suite green: `mvnw.cmd verify`

#### Manual Verification:

- Create a contract from a garage: tenant dropdown lists active tenants, rent pre-fills from the garage default, payment-day rejects 0/29+, save creates and the garage flips to "rented" in `LocationsView`.
- Attempting an overlapping contract on the same garage shows the rejection message and does not close the dialog.
- The contract appears on both the garage history and the tenant profile, with links navigating both ways.
- End the contract early (actual end ≤ planned end): it stays in the history with an ended-on date and status "Ended"; the garage returns to "free".
- Archive the garage (and separately a tenant): the `ConfirmDialog` states records are retained; after archiving, the contract row still exists (verify via the tenant profile or a DB peek) — nothing is hard-deleted.
- `garages/:id` for a non-existent id renders the not-found path, not a stack trace; an anonymous visit redirects to login.

**Implementation Note**: After this phase and all automated verification passes, pause for manual confirmation. This completes S-04.

---

## Testing Strategy

### Unit Tests:

- `ContractTests` — entity lifecycle: `endEarly` validation, `isActiveOn` boundaries, `isEnded`.
- `ContractServiceTests` — create field-mapping, archived-parent rejection, date/payment-day validation, **overlap rejection incl. the single-day-touch boundary**, end-early, `rentedGarageIds`, and the R4 archive-no-delete oracle.
- Extensions to `TenantServiceTests` / `GarageServiceTests` / `LocationServiceTests` — archiving a parent stamps its contracts and deletes nothing (R4).

### Integration Tests:

- **Deferred to test-plan §3 Phase 2** (real-DB harness). This slice does not stand up Testcontainers/H2. Retention (R4) is proven here at the mocked-service boundary; the real-DB "children survive and stay queryable" assertion is Phase 2's job.

### Manual Testing Steps:

1. Create a contract from a garage; confirm rent pre-fill, payment-day bounds, and the "rented" flip.
2. Attempt an overlap; confirm rejection message and dialog stays open.
3. Confirm the contract shows on garage history and tenant profile with two-way links.
4. End early; confirm it persists in history as "Ended" and the garage returns to "free".
5. Archive the parent garage and a tenant; confirm contracts are retained.
6. Visit `garages/<bad-id>` (not-found path) and `garages/1` while logged out (redirect to login).

## Performance Considerations

The portfolio "rented" derivation must stay a single batch query across all displayed garage ids (`rentedGarageIds`), never a per-row query — mirror `GarageService.listActiveByLocations`. History and profile finders are bounded by one garage / one tenant and join-fetch their single rendered association, so no N+1 and no `LazyInitializationException` under `open-in-view=false`.

## Migration Notes

`V6__contracts.sql` is a new forward migration; V1–V5 are immutable. No existing data to backfill (the domain is empty). `ddl-auto=validate` makes the entity↔migration match a boot-time gate.

## References

- Roadmap slice: `context/foundation/roadmap.md` (S-04)
- PRD: `context/foundation/prd.md` (FR-009, FR-010, FR-011, FR-021, FR-005)
- Test plan risks R4/R6, quality gates: `context/foundation/test-plan.md`
- Cascade pattern: `src/main/java/com/example/garageops/locations/LocationService.java:64-72`
- Fetch-join pattern: `src/main/java/com/example/garageops/garages/GarageRepository.java:18-26`
- Profile-view / parameterized-route pattern: `src/main/java/com/example/garageops/tenants/TenantProfileView.java`
- Gating-test pattern: `src/test/java/com/example/garageops/security/SecurityGatingTests.java:76-82`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Data model — Contract entity, migration, repository

#### Automated

- [x] 1.1 Compiles and schema validates against V6: `mvnw.cmd verify` — 40dcfc3
- [x] 1.2 Entity unit tests pass: `mvnw.cmd test -Dtest=ContractTests` — 40dcfc3
- [x] 1.3 App boots (Flyway applies V6, Hibernate validates the mapping): `mvnw.cmd test -Dtest=GarageopsApplicationTests` — 40dcfc3

#### Manual

- [x] 1.4 V6 columns match the `Contract` entity field-by-field (FK names, `NUMERIC(10,2)` precision) — 40dcfc3

### Phase 2: Service layer — ContractService + FR-021 retention cascade

#### Automated

- [x] 2.1 All service tests pass: `mvnw.cmd test -Dtest=ContractServiceTests`
- [x] 2.2 Cascade retention tests pass: `mvnw.cmd test -Dtest=TenantServiceTests,GarageServiceTests,LocationServiceTests`
- [x] 2.3 Full suite green: `mvnw.cmd verify`

#### Manual

- [x] 2.4 Overlap predicate reviewed against worked boundary cases (single-day touch, fully-contained)

### Phase 3: Views & navigation — garage detail, history, create/end, tenant profile, gating

#### Automated

- [ ] 3.1 Gating test passes: `mvnw.cmd test -Dtest=SecurityGatingTests`
- [ ] 3.2 Full build + suite green: `mvnw.cmd verify`

#### Manual

- [ ] 3.3 Create from garage: tenant dropdown, rent pre-fill, payment-day bounds, garage flips to "rented"
- [ ] 3.4 Overlapping contract shows rejection and keeps the dialog open
- [ ] 3.5 Contract appears on garage history and tenant profile with two-way links
- [ ] 3.6 End-early persists as "Ended" and the garage returns to "free"
- [ ] 3.7 Archiving the parent garage and a tenant retains the contract (no hard delete)
- [ ] 3.8 `garages/<bad-id>` renders not-found; anonymous `garages/1` redirects to login
