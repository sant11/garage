# S-03 Tenant Management Implementation Plan

## Overview

Ship roadmap slice **S-03 (`tenant-management`)**: the owner can add, edit, and
archive a tenant (a name plus optional free-text contact info), and open a
**tenant profile** that will list the tenant's current and past contracts
(FR-007, FR-008, FR-021).

The slice is a near-clone of the already-shipped, tested S-02
(`portfolio-locations-garages`) vertical: a standalone `ArchivableEntity`, a
`JpaRepository` with an active-only finder, an `@Service` with an idempotent
flag-flip `archive()`, a Flyway migration matching the `V3` DDL style, and
Vaadin views attached to `MainLayout` and gated with `@PermitAll`. The one new
pattern is the **parameterized detail route** (`tenants/:id` +
`HasUrlParameter<Long>`) — the project's first, and the FR-018 drill-through
target / future S-07 late-payer surface.

## Current State Analysis

- **A complete archive-only CRUD vertical already exists for `Location`.**
  `Location` (`locations/Location.java:18-52`) is a standalone
  `ArchivableEntity` with a single `@NotBlank name`, a public-widened `getId()`,
  and a `rename` mutator. `LocationRepository`
  (`locations/LocationRepository.java:11-13`) is a `JpaRepository` with
  `findByArchivedAtIsNullOrderByNameAsc()`. `LocationService`
  (`locations/LocationService.java:28-86`) uses constructor injection with
  `ObjectProvider`-wrapped repositories (so it stays constructible in the
  DB-free unit-test context) and an idempotent, `@Transactional` `archive()`
  that only ever `save()`s — never deletes.
- **`ArchivableEntity`** (`persistence/ArchivableEntity.java:28-77`) owns the
  archive contract: `archived_at`/`created_at`/`updated_at` as `Instant`
  (→ `TIMESTAMPTZ`), `@PrePersist`/`@PreUpdate` audit callbacks (no Spring Data
  `@EnableJpaAuditing`, deliberately, to keep the test context DB-free), an
  idempotent `archive()`, and `isArchived()`.
- **`V3__locations_and_garages.sql`** (`db/migration/V3...sql:7-23`) is the DDL
  template: `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, business
  columns `TEXT NOT NULL` / optional `TEXT`, audit columns `archived_at
  TIMESTAMPTZ` (nullable) + `created_at`/`updated_at TIMESTAMPTZ NOT NULL`, **no
  DB DEFAULT** (JPA callbacks own the values). The highest migration today is
  `V4__drop_deploy_smoke_test.sql`; **the next is `V5`.**
- **`application.properties:37-38`** sets `ddl-auto=validate` (Flyway owns the
  schema; the entity mapping must match the migrated table or boot fails fast)
  and `open-in-view=false`.
- **`LocationsView`** (`locations/LocationsView.java`) is the list/CRUD-UI
  exemplar: `@Route(layout=MainLayout.class)` + `@PageTitle` + `@PermitAll`, a
  `Grid<>(Type.class, false)` with component columns for status/actions, a
  `Dialog` + `Binder` bound to a **throwaway bean** so keystrokes never mutate
  the live list entity, a friendly empty-state `Paragraph`, an archive
  `ConfirmDialog` whose text states "Records are retained, not deleted.", and a
  single private `refresh()` post-mutation hook.
- **`MainLayout`** (`ui/MainLayout.java:50-52`) holds the `SideNav`; today it has
  one item ("Locations"). `@PermitAll` on the parent gates the whole nav chain.
- **Security is fail-closed.** `SecurityConfig`
  (`security/SecurityConfig.java:32-38`) delegates view access to Vaadin's
  checker (default-deny); the only HTTP carve-out is `/actuator/health`.
  `SecurityGatingTests` (`security/SecurityGatingTests.java:54-78`) asserts
  `GET /` and `GET /locations` redirect to `/login`, health returns 200, and a
  valid login authenticates.
- **`LocationServiceTests`**
  (`locations/LocationServiceTests.java:77-99`) is the R4 oracle: mocked repos,
  archive stamps parent + children and invokes `never().delete*()` on either
  repository.
- **No detail/profile route exists yet.** The tenant profile is the project's
  first parameterized route. **No `Contract` entity exists** (it arrives in
  S-04).

## Desired End State

A signed-in owner sees a **Tenants** entry in the side nav. The tenants page
lists active tenants (name + contact) in a grid, with **Add tenant**, and
per-row **View / Edit / Archive** actions. Adding or editing opens a dialog
(name required, contact optional); archiving is gated by a confirm dialog that
states records are retained. **View** navigates to `tenants/:id`, a profile page
showing the tenant's name (with an open header slot for a future late-payer
badge) and a **"current and past contracts"** section that today renders a
friendly empty-state ("Contracts arrive in a later slice") inside an isolated
builder method. An unknown or archived tenant id yields a 404. Archiving a
tenant is a pure flag-flip — no data is deleted.

Verification: the app boots clean (`ddl-auto=validate` passes against the new
`tenants` table); `TenantServiceTests` proves archive deletes nothing;
`SecurityGatingTests` proves `/tenants` and `/tenants/<id>` redirect anonymous
visitors to login; the owner can add, edit, view, and archive a tenant through
the UI with no manual page refresh.

### Key Discoveries:

- **Build the seam, not the dependency** — established team norm. `Tenant` adds
  **zero** contract-facing fields/collections (`research.md` Area 4); the
  contract list is an isolated builder method S-04 fills by swapping one body,
  and the late-payer flag is a header layout slot only — no field, no
  placeholder badge (an always-absent badge would falsely signal "not a late
  payer").
- **No-parent-collection rule** (`garages/Garage.java:33-35`,
  `locations/Location.java`): the child holds the FK, the parent holds no
  `@OneToMany`. `Tenant` therefore has no collection; when S-04 lands, `Contract`
  carries `@ManyToOne Tenant`, created entirely on the S-04 side.
- **R4 safe by construction** — archive is a flag-flip UPDATE; zero
  `CascadeType`/`orphanRemoval`/`@OneToMany` in production code. Copying
  `LocationService.archive()` (minus the cascade, since `Tenant` has no
  children) inherits the guarantee.
- **R5 fail-closed** — a new `@Route` with `@PermitAll` is owner-gated by
  default; danger only arises from an explicit `@AnonymousAllowed` slip.
- **`ddl-auto=validate` is the migration safety net** — the entity column
  mapping must match `V5__tenants.sql` exactly, or boot fails fast.

## What We're NOT Doing

- **No `Contract` entity, no contract-facing fields/collections on `Tenant`,
  no `@OneToMany`.** Contracts are S-04. The profile's contract section is an
  honest empty-state, not a stub.
- **No `latePayer` field/column/enum and no placeholder badge.** S-07 owns the
  late-payer flag; this slice leaves only a header layout slot.
- **No structured contact validation** (no `@Email`, no split phone/email
  columns). FR-007 says "at minimum a name and contact info" — a single optional
  free-text field matches that and avoids over-constraint.
- **No archived-tenant guard on contract creation.** That validation seam
  belongs on `TenantService` when S-04 adds contracts; there is nothing to guard
  against in S-03.
- **No real-DB / `@DataJpaTest` retention test.** That integration harness is
  owned by `test-plan.md` §3 Phase 2. S-03 ships the mock-level service oracle
  only.
- **No test-plan §6 cookbook update.** `tenant-management` is roadmap slice
  S-03, not a test-plan §3 rollout phase; cookbook entries fill in from their
  own dedicated phases.
- **No hard-delete UI** (FR-021; AGENTS hard rule).

## Implementation Approach

Clone the S-02 vertical layer-by-layer, deviating only where the tenant domain
genuinely differs from a location:

1. **Persistence first** (entity + repo + migration), so `ddl-auto=validate`
   has a table to validate against at boot.
2. **Service + its R4 oracle**, proving archive retains.
3. **Views + navigation + gating test** last, including the new parameterized
   profile route.

Two real deltas from the `Location` clone: (a) `Tenant` has a **second editable
field** (`contactInfo`), so it gets an `editProfile(name, contact)` mutator and
a single combined Edit dialog rather than `Location`'s rename-only flow; and
(b) the **profile route** is a new pattern with no in-repo precedent — built per
the Vaadin-idiomatic shape the research specifies.

## Critical Implementation Details

- **`ddl-auto=validate` runs at boot, not in the unit-test suite.** The test
  context is DB-free (JPA autoconfig excluded), so the entity↔migration match is
  *not* caught by `mvnw test` — it is caught when the app boots against Postgres.
  Phase 1's "migration applies / validate passes" is therefore a **manual**
  (run-the-app) verification, not an automated test.
- **Unknown / archived tenant on the profile route must 404.** In
  `setParameter`, throw `com.vaadin.flow.router.NotFoundException` (or
  `RouteNotFoundException`) when the id is unknown or the loaded tenant
  `isArchived()` — do not render a blank or partial profile.
- **Binder binds a throwaway bean.** Mirror `LocationsView` exactly: the
  add/edit dialog binds a fresh `Tenant` copy so keystrokes never mutate the
  live entity sitting in the rendered grid; the persisted values are read from
  that bean on save.

## Phase 1: Persistence & Domain

### Overview

Add the `Tenant` entity, its repository, and the `V5__tenants.sql` migration —
the standalone archivable record with name + optional contact, mirroring
`Location`.

### Changes Required:

#### 1. Tenant entity

**File**: `src/main/java/com/example/garageops/tenants/Tenant.java`

**Intent**: A standalone archivable tenant record — a name and optional
free-text contact info — that S-04 will later reference via a `Contract` FK.
Mirror `Location` one-for-one; add the second field and an `editProfile`
mutator.

**Contract**: `@Entity @Table(name = "tenants") class Tenant extends
ArchivableEntity`. Fields: `@NotBlank @Column(name="name", nullable=false)
String name`; `@Column(name="contact_info") String contactInfo` (nullable, no
`@NotBlank`, no `@Email`). A `protected` no-arg ctor + a `public Tenant(String
name, String contactInfo)` business ctor. Override `getId()` to widen to
`public` (so views can pass the id), matching `Location.java:39-42`. Mutator
`public void editProfile(String name, String contactInfo)`. A class-doc note
stating no contract-facing structure exists yet (the seam S-04 fills). **No
`@OneToMany`, no `Contract` reference, no `latePayer` field.**

#### 2. Tenant repository

**File**: `src/main/java/com/example/garageops/tenants/TenantRepository.java`

**Intent**: Spring Data access with an active-only, name-sorted finder for the
default list view; archived rows stay reachable via inherited `findById`.

**Contract**: `interface TenantRepository extends JpaRepository<Tenant, Long>`
with `List<Tenant> findByArchivedAtIsNullOrderByNameAsc()`. Mirrors
`LocationRepository.java:11-13`.

#### 3. Migration

**File**: `src/main/resources/db/migration/V5__tenants.sql`

**Intent**: Create the `tenants` table matching the `Tenant` entity mapping so
`ddl-auto=validate` passes at boot.

**Contract**: `CREATE TABLE tenants` with `id BIGINT GENERATED ALWAYS AS
IDENTITY PRIMARY KEY`, `name TEXT NOT NULL`, `contact_info TEXT` (nullable),
`archived_at TIMESTAMPTZ` (nullable), `created_at TIMESTAMPTZ NOT NULL`,
`updated_at TIMESTAMPTZ NOT NULL`. No DB DEFAULT on audit columns. Follow the
`V3__locations_and_garages.sql:7-13` style exactly.

### Success Criteria:

#### Automated Verification:

- Code compiles and the existing suite passes: `mvnw.cmd test`

#### Manual Verification:

- App boots clean against the database: `mvnw.cmd spring-boot:run` — Flyway
  applies `V5` and `ddl-auto=validate` reports no mismatch between `Tenant` and
  the `tenants` table.
- The `tenants` table exists with the expected columns/types after boot.

**Implementation Note**: After this phase and all automated verification
passes, pause for manual confirmation that the app boots and the migration
validates before proceeding.

---

## Phase 2: Service Layer + R4 Oracle

### Overview

Add `TenantService` (add / editProfile / listActive / archive) and the
mock-level `TenantServiceTests` proving archive retains rather than deletes.

### Changes Required:

#### 1. Tenant service

**File**: `src/main/java/com/example/garageops/tenants/TenantService.java`

**Intent**: Own the tenant lifecycle. Mirror `LocationService`, but: (a) `Tenant`
has no children, so `archive()` is a single idempotent flag-flip with no cascade
pass; (b) editing updates both name and contact via `editProfile`.

**Contract**: `@Service` class, **constructor injection only**, a single
`ObjectProvider<TenantRepository>` resolved per call via a private
`tenants()` helper (mirror `LocationService.java:28-86`). Methods:
`Tenant add(String name, String contactInfo)`; `void editProfile(Long id, String
name, String contactInfo)` (load → mutate → save; `EntityNotFoundException` for
unknown id); `List<Tenant> listActive()` →
`findByArchivedAtIsNullOrderByNameAsc()`; `Tenant findActive(Long id)` (load,
throw `EntityNotFoundException` if unknown or `isArchived()` — used by the
profile route); `@Transactional void archive(Long id)` (load → `archive()` →
`save()`, never delete). A private `require(Long id)` loader mirroring
`LocationService.require`.

#### 2. Service tests (R4 oracle)

**File**: `src/test/java/com/example/garageops/tenants/TenantServiceTests.java`

**Intent**: Lock the archive-retention guarantee for tenants at the service
layer with mocked repositories — no Spring context, no DB.

**Contract**: Mirror `LocationServiceTests.java`: a `providerOf(...)` helper
wrapping a mocked `TenantRepository` in a mocked `ObjectProvider`. Tests:
`add` captures name + contact; `editProfile` updates both fields and saves;
`listActive` delegates to the active-only finder; **`archive` stamps the tenant
and invokes `never().delete()` / `never().deleteById()` on the repository** (the
R4 oracle, mirroring `LocationServiceTests.java:77-99` minus the child-cascade
assertions).

### Success Criteria:

#### Automated Verification:

- Unit tests pass: `mvnw.cmd test`
- The archive oracle specifically passes: `mvnw.cmd test
  -Dtest=TenantServiceTests`

#### Manual Verification:

- (none beyond automated for this phase)

**Implementation Note**: After this phase and all automated verification
passes, proceed to Phase 3.

---

## Phase 3: Views, Navigation & Gating

### Overview

Add the tenants list/CRUD view, the parameterized profile route with honest
seams, the side-nav entry, and a gating-test extension covering the two new
routes.

### Changes Required:

#### 1. Tenants list + CRUD view

**File**: `src/main/java/com/example/garageops/tenants/TenantsView.java`

**Intent**: List active tenants in a grid with Add and per-row View / Edit /
Archive actions. Clone `LocationsView`'s structure (grid, throwaway-bean Binder
dialog, archive ConfirmDialog, empty-state, single `refresh()` hook); a tenant
has no parent so the layout is a single flat grid (not the per-location
sections).

**Contract**: `@Route(value="tenants", layout=MainLayout.class)
@PageTitle("Tenants") @PermitAll class TenantsView extends VerticalLayout`,
constructor-injecting `TenantService`. A `Grid<Tenant>(Tenant.class, false)`
with columns for name and contact, plus a component column with **View / Edit /
Archive** buttons. `setItems(tenantService.listActive())`,
`setAllRowsVisible(true)`. Add/Edit opens a `Dialog` + `Binder<Tenant>`
(name `asRequired`, contact optional) bound to a throwaway `Tenant` bean; save →
`tenantService.add(...)` or `editProfile(...)` → `dialog.close()` → `refresh()`.
**View** → `UI.getCurrent().navigate("tenants/" + tenant.getId())`. Archive uses
a `ConfirmDialog` with `setConfirmButtonTheme("error primary")` and text stating
"Records are retained, not deleted." Empty-state: `listActive().isEmpty()` →
friendly `Paragraph`. Patterns at `LocationsView.java:84-86,113-119,160-197,277-293`.

#### 2. Tenant profile view (new route pattern)

**File**: `src/main/java/com/example/garageops/tenants/TenantProfileView.java`

**Intent**: The FR-008 profile and FR-018 drill-through target. Render the
tenant's name with an open header slot for a future late-payer badge, and a
"current and past contracts" section that today shows an honest empty-state. The
contract section is an isolated builder method so S-04 fills it by swapping one
body.

**Contract**: `@Route(value="tenants/:id", layout=MainLayout.class)
@PageTitle("Tenant") @PermitAll class TenantProfileView extends VerticalLayout
implements HasUrlParameter<Long>`. `setParameter(BeforeEvent, Long id)` loads
via `tenantService.findActive(id)` and renders; if the id is unknown or the
tenant is archived, throw `com.vaadin.flow.router.NotFoundException` (→ 404).
Header is a `HorizontalLayout(nameComponent, /* slot for future badge */)`
(mirror `LocationsView.java:108-111`) — **leave the slot open; build no badge.**
A private `contractsSection()` returning a `Component` renders a house-style
empty-state `Paragraph` ("No contract history yet. Contracts arrive in a later
slice."). A class-doc note records that S-04 swaps the `Paragraph` for a
`Grid<Contract>` by changing this one method body (mirror the `HomeView`
"comes later" precedent).

#### 3. Navigation entry

**File**: `src/main/java/com/example/garageops/ui/MainLayout.java`

**Intent**: Surface the tenants list in the side nav.

**Contract**: Add `nav.addItem(new SideNavItem("Tenants", TenantsView.class))`
beside the existing "Locations" item (`MainLayout.java:51`).

#### 4. Route-gating test extension (R5)

**File**: `src/test/java/com/example/garageops/security/SecurityGatingTests.java`

**Intent**: Prove the two new routes inherit owner-gating — the slice ships the
first parameterized route, so close that R5 surface at introduction.

**Contract**: Add assertions mirroring the existing `/locations` case
(`SecurityGatingTests.java:54-66`): an anonymous `GET /tenants` and an anonymous
`GET /tenants/1` each return 3xx with `redirectedUrl("/login")`.

### Success Criteria:

#### Automated Verification:

- Full suite passes: `mvnw.cmd test`
- Gating assertions pass: `mvnw.cmd test -Dtest=SecurityGatingTests`

#### Manual Verification:

- A signed-in owner sees **Tenants** in the side nav and can add, edit, and
  archive a tenant; the grid refreshes with no manual page reload.
- Adding requires a name; contact is accepted empty; editing updates both
  fields.
- Archiving shows the "records are retained" confirm, then removes the tenant
  from the active list.
- **View** opens `tenants/:id` showing the name and the friendly empty contract
  section; the header has room for a future badge.
- Navigating to `tenants/<unknown-id>` or an archived tenant's id yields a 404,
  not a blank/partial profile.

**Implementation Note**: After this phase and all automated verification
passes, pause for manual confirmation of the UI flows before closing the slice.

---

## Testing Strategy

### Unit Tests:

- `TenantServiceTests` (mocked repos): `add` persists name + contact;
  `editProfile` updates both; `listActive` delegates to the active-only finder;
  **`archive` stamps the tenant and deletes nothing** (R4 oracle).

### Integration Tests:

- Deferred. The real-DB (`@DataJpaTest` / Testcontainers) retention test that
  proves the *persistence layer* emits no DELETE is owned by `test-plan.md` §3
  Phase 2, not by this slice.

### Manual Testing Steps:

1. Boot the app; confirm Flyway applies `V5` and `validate` passes.
2. Sign in; open **Tenants**; confirm the empty-state copy.
3. Add a tenant with name only; add another with name + contact; both appear.
4. Edit a tenant's name and contact; confirm both persist.
5. Click **View**; confirm the profile renders the name and the empty contract
   section.
6. Visit `tenants/999999` and an archived tenant's id; confirm a 404.
7. Archive a tenant; confirm the confirm-dialog copy and that it leaves the
   active list.
8. While signed out, hit `/tenants` and `/tenants/1`; confirm redirect to login.

## Performance Considerations

Negligible. The list is a single active-only query; the profile is a
single-row load. No associations are traversed off-session, so the
`open-in-view=false` `JOIN FETCH` rule does not apply to this slice (it will
when S-04 adds the contract grid).

## Migration Notes

`V5__tenants.sql` is a new forward migration over an empty domain (no `tenants`
data exists). No backfill. Audit columns carry no DB DEFAULT — the JPA callbacks
populate them on first persist.

## References

- Change identity: `context/changes/tenant-management/change.md`
- Research: `context/changes/tenant-management/research.md`
- Entity exemplar: `locations/Location.java:18-52`
- Repository exemplar: `locations/LocationRepository.java:11-13`
- Service exemplar: `locations/LocationService.java:28-86`
- Migration template: `db/migration/V3__locations_and_garages.sql:7-23`
- List/CRUD-UI exemplar: `locations/LocationsView.java:84-86,108-111,113-119,160-197,277-293`
- Nav: `ui/MainLayout.java:50-52`
- R4 oracle: `locations/LocationServiceTests.java:77-99`
- R5 gating: `security/SecurityGatingTests.java:54-78`
- Archive contract: `persistence/ArchivableEntity.java:28-77`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Persistence & Domain

#### Automated

- [x] 1.1 Code compiles and the existing suite passes: `mvnw.cmd test` — a668c22

#### Manual

- [x] 1.2 App boots clean; Flyway applies `V5` and `ddl-auto=validate` reports no mismatch — a668c22
- [x] 1.3 The `tenants` table exists with the expected columns/types after boot — a668c22

### Phase 2: Service Layer + R4 Oracle

#### Automated

- [x] 2.1 Unit tests pass: `mvnw.cmd test` — 8bd261b
- [x] 2.2 The archive oracle passes: `mvnw.cmd test -Dtest=TenantServiceTests` — 8bd261b

### Phase 3: Views, Navigation & Gating

#### Automated

- [x] 3.1 Full suite passes: `mvnw.cmd test` — 2b8b27b
- [x] 3.2 Gating assertions pass: `mvnw.cmd test -Dtest=SecurityGatingTests` — 2b8b27b

#### Manual

- [x] 3.3 Owner sees Tenants nav and can add / edit / archive with auto-refresh — 2b8b27b
- [x] 3.4 Add requires a name; contact accepted empty; edit updates both fields — 2b8b27b
- [x] 3.5 Archive shows "records are retained" confirm and drops the tenant from the active list — 2b8b27b
- [x] 3.6 View opens `tenants/:id` showing name + friendly empty contract section with an open header slot — 2b8b27b
- [x] 3.7 Unknown / archived tenant id yields a 404, not a blank/partial profile — 2b8b27b
