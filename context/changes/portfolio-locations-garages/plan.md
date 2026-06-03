# Manage Locations & Garages (S-02) Implementation Plan

## Overview

S-02 is the first domain slice of GarageOps. It lets the owner add / rename / archive
**locations**, add / edit / archive **garages** (label + default monthly rent) under a
location, mark a garage as a **problem** with a free-text reason (and clear it), and view the
whole portfolio **grouped by location** with each garage's status (**free / problem**; "rented"
is derived from S-04 contracts and is explicitly out of scope here). It implements FR-003,
FR-004, FR-005, FR-006 and exercises the FR-021 archive-only retain-records rule.

The slice is built entirely by extending established foundations (F-02 persistence + archive-only,
F-01/S-01 Vaadin views + owner-gating). No new infrastructure is introduced.

## Current State Analysis

- **Persistence (F-02)** — `persistence/ArchivableEntity` (extends `BaseEntity`) provides `id`,
  nullable `archivedAt`, `createdAt`/`updatedAt` (set by `@PrePersist`/`@PreUpdate` lifecycle
  callbacks, **not** `@EnableJpaAuditing`), an idempotent `archive()` and `isArchived()`. Archive
  is a **queryable state, not a row-hiding filter** — there is deliberately no global
  `@SoftDelete`/`@Where`; each query decides whether to include archived rows
  (`ArchivableEntity.java:10-26`). Repositories are plain `JpaRepository<T, Long>` interfaces.
- **Schema** — Flyway owns DDL under `src/main/resources/db/migration/`. `V1__init.sql`
  (the `deploy_smoke_test` table) and `V2__users.sql` are **immutable**; next is `V3`.
  `spring.jpa.hibernate.ddl-auto=validate` (`application.properties:33-45`) fails boot on any
  entity↔column mismatch — so boot is the first integration test of the schema contract.
- **Views & gating (F-01/S-01)** — `ui/HomeView.java` is the `@Route(value="", layout=MainLayout.class)`
  + `@PageTitle` + `@PermitAll` template to mirror. `ui/MainLayout.java` is the `AppLayout` shell
  (`@PermitAll`), with a TODO at `MainLayout.java:14` ("S-02+ add navigation here") naming this
  slice as the owner of nav wiring. `security/SecurityConfig.java` applies
  `VaadinSecurityConfigurer` → views are **denied-by-default**; `SecurityGatingTests` asserts
  unauthenticated → 302 `/login`.
- **Tests** — the entire suite is **DB-free** today (four JPA/datasource/Flyway autoconfigs
  excluded in `src/test/resources/application.properties:4-10`). No Testcontainers, no H2 in
  `pom.xml`. Existing patterns to mirror: `persistence/ArchivableEntityTests` (POJO archive
  behavior), `account/OwnerBootstrapTests` (service logic with mocked repos), `security/SecurityGatingTests`.
- **Dead code** — `persistence/DeploySmokeRecord` + `DeploySmokeRecordRepository` are flagged for
  deletion once the first domain table lands (`DeploySmokeRecord.java:18`). Confirmed during
  planning: in `src/` they are referenced **only** by their own definitions — no runner,
  controller, test, or health check uses them, so removal is safe.

## Desired End State

The owner, after login, opens **Locations** from the app nav and sees their portfolio grouped
into one section per location. Each location section shows its name with rename / archive / add-garage
actions and a grid of its garages; each garage row shows label, default monthly rent, and a
status badge (**free** or **problem**) plus edit / mark-problem (or clear) / archive actions.
Adding and editing use `Binder`-validated dialogs. Archiving a location cascade-stamps its garages
(retain, never delete) behind a confirmation that names how many garages will be archived.
The `deploy_smoke_test` table and its Java are gone. An anonymous request to `/locations`
redirects to `/login`.

Verify: app boots clean (`ddl-auto=validate` passes against `V3`), `mvnw.cmd test` is green
(entity + service unit tests, extended gating test), and the portfolio view round-trips
add/rename/edit/archive/problem operations manually.

### Key Discoveries:

- Archive = state, not deletion (`ArchivableEntity.java:10-26`). Retention is guaranteed by **not
  deleting**; visibility is chosen per query. Cascade must **stamp**, never delete (R4).
- `ddl-auto=validate` is a fail-fast schema gate: `Instant`→`TIMESTAMPTZ` (not `TIMESTAMP`),
  money→`NUMERIC`, `Long`→`BIGINT`, `String`→`TEXT`; audit columns get no DB DEFAULT (JPA owns
  them); snake_case column names (`V2__users.sql:6-11`).
- Views are denied-by-default once `VaadinSecurityConfigurer` applies, but the convention is to
  annotate every view **and** its parent layout explicitly (`@PermitAll`) — `MainLayout` already is.
- Vaadin 25: use `BigDecimalField` for money; `ConfirmDialog` for archive confirmation; a plain
  `Dialog` + `TextArea` for the problem reason (`ConfirmDialog` "isn't meant for collecting input");
  no native row-grouping — section-per-location is built from repeated components.

## What We're NOT Doing

- **"Rented" status.** Derived from active contracts (S-04). This slice ships **free / problem**
  only; rented is left as a future seam (a status enum/derivation point), never stubbed with fake data.
- **`owner_id` FK on `locations`/`garages`.** App is single-owner; existing entities carry no owner
  FK. Omitted by design (PRD Non-Goal: single-tenant; AGENTS hard rule).
- **Real-DB archive-retention test (R4).** Owned by **test-plan §3 Phase 2** (needs Testcontainers,
  not yet in `pom.xml`). This slice's tests stay DB-free; boot-time `ddl-auto=validate` plus
  DB-free unit tests are the coverage here.
- **Tenants / contracts / payments / dashboard.** Later slices (S-03–S-06).
- **Hard-delete UI.** Archive-only (FR-021, AGENTS hard rule).
- **Test-plan §6 cookbook edits.** Those are owned by the test-plan's own rollout-phase changes,
  not by this roadmap domain slice.

## Implementation Approach

Build bottom-up in three phases, each independently verifiable: (1) schema + entities +
repositories + smoke cleanup, gated by boot and POJO unit tests; (2) services holding all business
logic including the explicit cascade-stamp, gated by DB-free service unit tests with mocked repos;
(3) the Vaadin portfolio view + nav + the R5 gating test. Cascade-archive is implemented at the
**service** layer (load active children, stamp each, save) — **no** JPA `CascadeType.REMOVE`
anywhere — so R4 (never delete children) holds by construction. `Garage` holds a `@ManyToOne`
`Location` (the FK side); `Location` does **not** own a cascading collection.

## Critical Implementation Details

- **Schema/entity contract is fail-fast at boot.** A forgotten column or `TIMESTAMP` vs
  `TIMESTAMPTZ` slip breaks `ddl-auto=validate` at startup, not at runtime. Treat a clean boot as
  the first integration test; run the app (or any `@SpringBootTest` that loads JPA) after Phase 1.
  Note the suite is DB-free, so the JPA-validating boot check is a **manual `spring-boot:run`** step,
  not an automated test in this slice.
- **Cascade must stamp, not delete.** Do not add `CascadeType.REMOVE` or `orphanRemoval=true` to
  any relationship. Archiving iterates active garages and calls `archive()` on each — idempotent,
  retains rows.

## Phase 1: Persistence & schema

### Overview

Create the `locations` and `garages` tables, the two archivable entities and their repositories,
remove the dead smoke record, and cover entity behavior with DB-free POJO unit tests.

### Changes Required:

#### 1. Flyway migration — create domain tables

**File**: `src/main/resources/db/migration/V3__locations_and_garages.sql`

**Intent**: Create `locations` and `garages` matching the new entity mappings exactly, so
`ddl-auto=validate` passes at boot.

**Contract**: snake_case columns; IDENTITY PKs; `archived_at TIMESTAMPTZ` nullable;
`created_at`/`updated_at TIMESTAMPTZ NOT NULL` (no DB DEFAULT — JPA callbacks own them);
`garages.location_id BIGINT NOT NULL REFERENCES locations(id)`; `garages.monthly_rent NUMERIC(10,2)
NOT NULL`; `garages.problem_reason TEXT` nullable (NULL = not flagged).

```sql
CREATE TABLE locations (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT NOT NULL,
    archived_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);
CREATE TABLE garages (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    location_id    BIGINT NOT NULL REFERENCES locations(id),
    label          TEXT NOT NULL,
    monthly_rent   NUMERIC(10,2) NOT NULL,
    problem_reason TEXT,
    archived_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);
```

#### 2. Flyway migration — drop the smoke table

**File**: `src/main/resources/db/migration/V4__drop_deploy_smoke_test.sql`

**Intent**: Remove the obsolete `deploy_smoke_test` table now that a real domain table supersedes it.

**Contract**: `DROP TABLE IF EXISTS deploy_smoke_test;` — V1 stays immutable; the drop is a new
forward migration.

#### 3. `Location` entity

**File**: `src/main/java/com/example/garageops/locations/Location.java`

**Intent**: Archivable portfolio record holding a location name. New `locations` feature package.

**Contract**: extends `persistence.ArchivableEntity`; `@Entity @Table(name="locations")`;
`@Column(name="name", nullable=false) String name` with `@NotBlank`; protected no-arg JPA
constructor + public `Location(String name)` value constructor; getter for `name` and a `rename`
setter. Mirror `account/OwnerAccount.java:35-47` for the constructor/column pattern.

#### 4. `Garage` entity

**File**: `src/main/java/com/example/garageops/garages/Garage.java`

**Intent**: Archivable garage record under a location, carrying label, default monthly rent, and an
optional problem reason. New `garages` feature package.

**Contract**: extends `persistence.ArchivableEntity`; `@Entity @Table(name="garages")`;
`@ManyToOne(optional=false) @JoinColumn(name="location_id") Location location` (**no** cascade /
no `orphanRemoval`); `@Column(name="label", nullable=false) String label` (`@NotBlank`);
`@Column(name="monthly_rent", nullable=false) BigDecimal monthlyRent` (`@NotNull`, positive);
`@Column(name="problem_reason") String problemReason` (nullable). Domain helpers:
`markProblem(String reason)` (set reason), `clearProblem()` (null it), `isProblem()` (`problemReason != null`),
and an edit setter for label/rent. Protected no-arg + public value constructor
`Garage(Location location, String label, BigDecimal monthlyRent)`.

#### 5. Repositories

**Files**: `src/main/java/com/example/garageops/locations/LocationRepository.java`,
`src/main/java/com/example/garageops/garages/GarageRepository.java`

**Intent**: Spring Data interfaces with active-only finders for the default list views, leaving
archived rows reachable for future history.

**Contract**: `LocationRepository extends JpaRepository<Location, Long>` with
`List<Location> findByArchivedAtIsNullOrderByNameAsc()`. `GarageRepository extends
JpaRepository<Garage, Long>` with `List<Garage> findByLocationIdAndArchivedAtIsNull(Long locationId)`
and `List<Garage> findByLocationId(Long locationId)` (all, for cascade). Mirror
`account/OwnerAccountRepository.java:11-13`.

#### 6. Remove the smoke record

**Files (delete)**: `src/main/java/com/example/garageops/persistence/DeploySmokeRecord.java`,
`src/main/java/com/example/garageops/persistence/DeploySmokeRecordRepository.java`

**Intent**: Delete the dead reference entity + repo (no `src/` usages outside their own definitions,
confirmed during planning). The table drop is handled by `V4` (change #2).

#### 7. Entity behavior unit tests

**Files**: `src/test/java/com/example/garageops/locations/LocationTests.java`,
`src/test/java/com/example/garageops/garages/GarageTests.java`

**Intent**: Lock entity behavior DB-free, mirroring `persistence/ArchivableEntityTests`.

**Contract**: `LocationTests` — `archive()` idempotency, `rename` updates name. `GarageTests` —
`archive()` idempotency, `markProblem`/`clearProblem` toggle `isProblem()` and the reason,
edit updates label/rent. Pure POJO; class names end in `Tests`; tab-indented.

### Success Criteria:

#### Automated Verification:

- Project compiles: `mvnw.cmd -o compile`
- Entity unit tests pass: `mvnw.cmd test -Dtest=LocationTests,GarageTests`
- Full suite still green: `mvnw.cmd test`

#### Manual Verification:

- App boots with `ddl-auto=validate` against `V3`/`V4` (no schema-mismatch failure): `mvnw.cmd spring-boot:run`
- `deploy_smoke_test` no longer exists after migration; `locations` and `garages` tables present.

**Implementation Note**: After Phase 1 automated verification passes, pause for human confirmation
that the boot/migration manual check succeeded before proceeding to Phase 2.

---

## Phase 2: Services & business logic

### Overview

Add the two services that own all portfolio business logic — CRUD, the problem flag, and the
explicit cascade-stamp archive — with DB-free unit tests using mocked repositories.

### Changes Required:

#### 1. `LocationService`

**File**: `src/main/java/com/example/garageops/locations/LocationService.java`

**Intent**: Owns location lifecycle: add, rename, list active, and **archive with cascade**.

**Contract**: `@Service`, constructor injection of `LocationRepository` + `GarageRepository`
(mirror `account/OwnerDetailsService.java:20-27`). Methods: `Location add(String name)`,
`void rename(Long id, String name)`, `List<Location> listActive()`,
`void archive(Long locationId)`. `archive` loads the location, calls `location.archive()`, loads
its **active** garages (`findByLocationIdAndArchivedAtIsNull`), calls `archive()` on each, and
saves all — a stamp pass, never a delete. `@Transactional` on `archive`.

#### 2. `GarageService`

**File**: `src/main/java/com/example/garageops/garages/GarageService.java`

**Intent**: Owns garage lifecycle: add under a location, edit label/rent, mark/clear problem,
archive, and list by location.

**Contract**: `@Service`, constructor injection of `GarageRepository` + `LocationRepository`.
Methods: `Garage add(Long locationId, String label, BigDecimal monthlyRent)` (resolves the
`Location`, rejects an archived parent), `void edit(Long garageId, String label, BigDecimal rent)`,
`void markProblem(Long garageId, String reason)`, `void clearProblem(Long garageId)`,
`void archive(Long garageId)`, `List<Garage> listActiveByLocation(Long locationId)`.

#### 3. Service unit tests

**Files**: `src/test/java/com/example/garageops/locations/LocationServiceTests.java`,
`src/test/java/com/example/garageops/garages/GarageServiceTests.java`

**Intent**: Cover the business logic DB-free with mocked repos, mirroring
`account/OwnerBootstrapTests` (Mockito).

**Contract**: `LocationServiceTests` — the **cascade-stamp** is the key oracle: archiving a
location with N active garages stamps the location **and** each active garage (verify `archive()`
state / `save` calls), and **no** delete/remove is invoked on either repository. `GarageServiceTests`
— add rejects an archived parent location; mark/clear problem round-trips; edit updates fields.
Mock at the repository boundary only; class names end in `Tests`.

### Success Criteria:

#### Automated Verification:

- Service unit tests pass: `mvnw.cmd test -Dtest=LocationServiceTests,GarageServiceTests`
- Full suite green: `mvnw.cmd test`

#### Manual Verification:

- Cascade-stamp test asserts children are archived (not deleted) and no repository delete is called.

**Implementation Note**: After Phase 2 automated verification passes, pause for human confirmation
before proceeding to Phase 3.

---

## Phase 3: Views, navigation & gating

### Overview

Build the portfolio view (section-per-location with garage grids and `Binder` dialogs), wire the
nav link into `MainLayout`, and extend the gating test to cover the new route (R5).

### Changes Required:

#### 1. `LocationsView`

**File**: `src/main/java/com/example/garageops/locations/LocationsView.java`

**Intent**: The portfolio screen — one section per active location, each with location-level
actions and a grid of its active garages with status + per-garage actions.

**Contract**: `@Route(value="locations", layout=MainLayout.class)` + `@PageTitle("Locations")` +
`@PermitAll`, extends a Vaadin layout (mirror `ui/HomeView.java:17-26`). Constructor-injects
`LocationService` + `GarageService`. Renders a repeated section component per
`locationService.listActive()`: header = location name + buttons **Rename**, **Add garage**,
**Archive**; body = `Grid<Garage>` (`new Grid<>(Garage.class, false)`) over
`garageService.listActiveByLocation(id)` with columns label, monthly rent, a **status badge**
(`addComponentColumn` → "free"/"problem" badge from `isProblem()`), and an action column
(**Edit**, **Mark problem**/**Clear problem**, **Archive**). A top-level **Add location** button.
Re-fetch + refresh after each mutating action (no manual page refresh, per US-01 spirit).

#### 2. Add/edit forms (Binder dialogs)

**File**: `src/main/java/com/example/garageops/locations/LocationsView.java` (same file or small
co-located dialog components under `locations`/`garages`)

**Intent**: Validated input for location name, garage label + default rent, and the problem reason.

**Contract**: `Binder<Location>` / `Binder<Garage>` with `forField(...).asRequired()...bind(...)`;
**`BigDecimalField`** for `monthlyRent` (money — not `NumberField`). Add and edit reuse the same
dialog/binder (edit pre-populates via `readBean`). Archive uses Vaadin **`ConfirmDialog`**
(`error`/primary theme, `setCancelable(true)`); the location archive confirm text **names how many
garages will also be archived**. The problem reason uses a plain **`Dialog`** with a `TextArea`
and footer action buttons (`ConfirmDialog` isn't for input).

#### 3. Navigation link in `MainLayout`

**File**: `src/main/java/com/example/garageops/ui/MainLayout.java`

**Intent**: Surface the Locations route in the app shell (resolves the `MainLayout.java:14` TODO).

**Contract**: add a nav entry linking to `LocationsView` (e.g. `RouterLink`/`SideNav` item) in the
layout's drawer/nav area. No annotation change — `MainLayout` is already `@PermitAll`.

#### 4. Extend gating test (R5)

**File**: `src/test/java/com/example/garageops/security/SecurityGatingTests.java`

**Intent**: Prove the new domain route is owner-gated — an anonymous request to `/locations`
redirects to login.

**Contract**: add a test asserting `GET /locations` while unauthenticated → 302 redirect to
`/login` (mirror the existing unauth assertion in `SecurityGatingTests.java:33-71`). Stays DB-free
(`@AutoConfigureMockMvc` from `org.springframework.boot.webmvc.test.autoconfigure`).

### Success Criteria:

#### Automated Verification:

- Gating test passes: `mvnw.cmd test -Dtest=SecurityGatingTests`
- Full suite green: `mvnw.cmd test`
- Build packages: `mvnw.cmd package`

#### Manual Verification:

- Logged-in owner sees Locations in nav; can add/rename/archive a location and add/edit/archive a
  garage; status badge shows free/problem; mark-problem captures a reason and clear resets it.
- Archiving a location shows a confirm naming the garage count, then both location and its garages
  disappear from the active view (rows retained in DB).
- Anonymous hit on `/locations` redirects to `/login` (no owner data in the response).
- Usable on a phone-sized viewport (NFR mobile).

**Implementation Note**: After Phase 3 automated verification passes, pause for human confirmation
that the manual UI walkthrough succeeded.

---

## Testing Strategy

### Unit Tests:

- `LocationTests` / `GarageTests` — archive idempotency, rename, problem set/clear, edit (DB-free POJO).
- `LocationServiceTests` / `GarageServiceTests` — cascade-stamp (the R4-adjacent oracle: children
  archived, **never** deleted), add-rejects-archived-parent, problem round-trip (DB-free, mocked repos).

### Integration Tests:

- **Deferred to test-plan §3 Phase 2** — the real-DB archive-retention test (R4) needs Testcontainers.
  In this slice, app boot under `ddl-auto=validate` is the entity↔migration integration check.
- `SecurityGatingTests` extension — `/locations` anonymous → `/login` (R5), DB-free MockMvc.

### Manual Testing Steps:

1. `mvnw.cmd spring-boot:run`; confirm clean boot (schema validates) and `deploy_smoke_test` is gone.
2. Log in; open Locations; add a location, add two garages, edit one, mark one as a problem, clear it.
3. Archive a location with active garages; confirm the count in the dialog; verify it and its
   garages leave the active view.
4. Log out; hit `/locations`; confirm redirect to `/login`.
5. Repeat the core path on a phone-sized viewport.

## Performance Considerations

Small scale (PRD `target_scale: small`). Per-location garage queries are fine; no pagination or
caching needed. `open-in-view=false` is set, so services must load what views render (they return
lists, not lazy proxies).

## Migration Notes

`V3` and `V4` are forward-only; V1/V2 immutable. After deploy, `deploy_smoke_test` is dropped — the
production DB retains it until `V4` runs on the next deploy, which is expected and harmless
(`ddl-auto=validate` ignores extra tables, and nothing reads it).

## References

- Research: `context/changes/portfolio-locations-garages/research.md`
- Change identity: `context/changes/portfolio-locations-garages/change.md`
- Test-plan risks: `context/foundation/test-plan.md` §2 R4 (`:46`), R5 (`:47`); §3 Phase 2 (`:82`)
- Archive base: `src/main/java/com/example/garageops/persistence/ArchivableEntity.java:10-77`
- View template: `src/main/java/com/example/garageops/ui/HomeView.java:17-26`
- Nav TODO: `src/main/java/com/example/garageops/ui/MainLayout.java:14`
- Gating test: `src/test/java/com/example/garageops/security/SecurityGatingTests.java:33-71`
- Migration reference: `src/main/resources/db/migration/V2__users.sql:6-11`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Persistence & schema

#### Automated

- [x] 1.1 Project compiles: `mvnw.cmd -o compile` — c6bde6d
- [x] 1.2 Entity unit tests pass: `mvnw.cmd test -Dtest=LocationTests,GarageTests` — c6bde6d
- [x] 1.3 Full suite still green: `mvnw.cmd test` — c6bde6d

#### Manual

- [x] 1.4 App boots with `ddl-auto=validate` against `V3`/`V4` (no schema-mismatch failure) — c6bde6d
- [x] 1.5 `deploy_smoke_test` gone; `locations` and `garages` tables present — c6bde6d

### Phase 2: Services & business logic

#### Automated

- [x] 2.1 Service unit tests pass: `mvnw.cmd test -Dtest=LocationServiceTests,GarageServiceTests` — 136e891
- [x] 2.2 Full suite green: `mvnw.cmd test` — 136e891

#### Manual

- [x] 2.3 Cascade-stamp test asserts children archived (not deleted) and no repository delete is called — 136e891

### Phase 3: Views, navigation & gating

#### Automated

- [x] 3.1 Gating test passes: `mvnw.cmd test -Dtest=SecurityGatingTests`
- [x] 3.2 Full suite green: `mvnw.cmd test`
- [x] 3.3 Build packages: `mvnw.cmd package`

#### Manual

- [x] 3.4 Owner can add/rename/archive a location and add/edit/archive a garage; status badge shows free/problem; problem reason capture + clear works
- [x] 3.5 Archiving a location shows the garage-count confirm; location + garages leave the active view (rows retained)
- [x] 3.6 Anonymous hit on `/locations` redirects to `/login`
- [x] 3.7 Core path usable on a phone-sized viewport
