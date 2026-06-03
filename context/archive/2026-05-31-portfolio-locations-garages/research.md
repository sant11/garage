---
date: 2026-06-03T00:00:00+02:00
researcher: sant11
git_commit: 5087bc5ad60b3711fc23b9440c21adc87ac2acae
branch: develop
repository: garage
topic: "S-02 portfolio-locations-garages — implementation-ready patterns for Location/Garage CRUD, archive-only, and owner-gating"
tags: [research, codebase, locations, garages, jpa, vaadin, security, archive-only, flyway]
status: complete
last_updated: 2026-06-03
last_updated_by: sant11
---

# Research: S-02 Manage Locations & Garages

**Date**: 2026-06-03T00:00:00+02:00
**Researcher**: sant11
**Git Commit**: 5087bc5ad60b3711fc23b9440c21adc87ac2acae
**Branch**: develop
**Repository**: garage

## Research Question

Ground the `/10x-plan` for roadmap slice **S-02 (portfolio-locations-garages)** — the first
domain slice (FR-003, FR-004, FR-005, FR-006, FR-021). Owner can add / rename / archive
locations; add garages (label + default monthly rent) to a location; and view all garages
grouped by location with each garage's status (free / problem; "rented" activates in S-04).
Anchor the patterns S-02 must mirror: F-02 persistence + archive-only, F-01/S-01 Vaadin views
+ owner-gating, repo conventions, the DB-free test base, and current Vaadin 25 CRUD APIs.

Also grounds two test-plan risks this slice is the first to exercise: **R4** (archive-retention
violation, `test-plan.md:46`) and **R5** (domain route not owner-gated, `test-plan.md:47`).

## Summary

S-02 is built entirely by extending existing foundations — **no new infrastructure is needed**:

1. **Entities** extend `persistence/ArchivableEntity` (gives `id`, `archived_at`, `created_at`,
   `updated_at`, idempotent `archive()`, `isArchived()`). `Location` and `Garage` are archivable
   portfolio records per FR-021.
2. **Archive is a queryable state, not a row-hiding filter** — this is the deliberate F-02
   design (`ArchivableEntity.java:10-26`). Archiving stamps `archived_at` and **retains** the row
   plus its children. There is no global `@Where`/`@SoftDelete`; each query decides whether to
   include archived rows. This is exactly what R4 must verify: children survive and stay
   queryable after a parent is archived.
3. **Repositories** are plain `JpaRepository<T, Long>` interfaces; add finders like
   `findByArchivedAtIsNull()` as needed.
4. **Flyway** owns schema. Next migrations are `V3__*.sql`+ (V1, V2 are immutable). Columns must
   match entity mappings exactly because `ddl-auto=validate` (`application.properties:37`) fails
   boot on mismatch — and `Instant` must map to `TIMESTAMPTZ`.
5. **Views** are Vaadin Flow 25 `@Route(... layout = MainLayout.class)` + `@PageTitle` +
   `@PermitAll`, extending a Vaadin layout (mirror `ui/HomeView.java`). Navigation links wire into
   `MainLayout` — the comment at `MainLayout.java:14` ("S-02+ add navigation here") names S-02 as
   the owner of that work.
6. **Owner-gating is denied-by-default and annotation-driven** (Vaadin `VaadinSecurityConfigurer`).
   A new `@Route` view with **no** access annotation is **denied** — but the convention is to
   annotate every view AND its parent layout explicitly. This is the R5 contract.
7. **Services** are `@Service` + constructor injection only (`OwnerDetailsService.java:20-27`).
8. **Tests are DB-free today** — `src/test/resources/application.properties:4-10` excludes four
   datasource/JPA/Flyway autoconfigs. S-02's own gating + entity-behavior tests stay DB-free and
   mirror `SecurityGatingTests` / `ArchivableEntityTests`; a **real-DB** archive-retention test
   (R4) is explicitly the test-plan's **Phase 2** job, needs Testcontainers (not yet in `pom.xml`),
   and must override the suite-wide exclude **per-test**, not remove it globally.

## Detailed Findings

### A. Persistence & archive-only foundation (F-02)

**Base entity** — `persistence/BaseEntity.java:15-26`:
```java
@MappedSuperclass
public abstract class BaseEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	protected Long getId() { return id; }
}
```
ID strategy `IDENTITY` → DB-owned (`BIGINT GENERATED ALWAYS AS IDENTITY` / `BIGSERIAL`).

**Archivable base** — `persistence/ArchivableEntity.java:28-77` (extends `BaseEntity`):
- `@Column(name="archived_at") Instant archivedAt` — `null` = active, non-null = archived-at.
- `@Column(name="created_at", updatable=false) Instant createdAt`, `@Column(name="updated_at") Instant updatedAt`.
- `@PrePersist onCreate()` sets both `createdAt` + `updatedAt`; `@PreUpdate onUpdate()` advances only `updatedAt`. (Audit via lifecycle callbacks, **not** `@EnableJpaAuditing` — the DB-free test profile has no `EntityManagerFactory`; see `ArchivableEntity.java:19-21`.)
- `archive()` is **idempotent** (`if (archivedAt == null)`), `isArchived()` returns `archivedAt != null`.
- **Critical design note** (`ArchivableEntity.java:10-26`): "Archive is a visible, queryable state — not a row-hiding filter… deliberately not Hibernate `@SoftDelete`, which would globally hide archived rows." History views (FR-008, FR-011) depend on archived rows staying readable. **R4 implication:** archiving a parent must NOT cascade-delete or orphan children — and there is no global filter to hide them, so retention is a query-and-cascade concern the plan must design explicitly.

**Reference entity (extends `BaseEntity`, not archivable)** — `persistence/DeploySmokeRecord.java:21-43`. Marked for deletion once S-02's first domain table supersedes the smoke table (`DeploySmokeRecord.java:18`). The smoke table/migration is `deploy_smoke_test` (V1).

**Repositories** — plain Spring Data interfaces:
- `persistence/DeploySmokeRecordRepository.java:9` — `extends JpaRepository<DeploySmokeRecord, Long>`, no custom methods.
- `account/OwnerAccountRepository.java:11-13` — adds `Optional<OwnerAccount> findByUsername(String)`.
- S-02 adds e.g. `LocationRepository extends JpaRepository<Location, Long>` with `List<Location> findByArchivedAtIsNull()`, and `GarageRepository` with `findByLocationIdAndArchivedAtIsNull(Long)`.

**Entity creation pattern** — `account/OwnerAccount.java:35-47`: `protected` no-arg JPA constructor + a public value constructor; column mappings `@Column(name="...", nullable=..., unique=...)`.

### B. Flyway migrations & the `ddl-auto=validate` contract

**Directory**: `src/main/resources/db/migration/`. Files: `V1__init.sql`, `V2__users.sql`.
Naming `V<n>__<desc>.sql`; **next is `V3`**. V1/V2 are **immutable** (`validate-on-migrate=true`).

**V2 reference** (`V2__users.sql:6-11`):
```sql
CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    email         TEXT NOT NULL UNIQUE
);
```

**Config** (`application.properties:33-45`): `spring.jpa.hibernate.ddl-auto=validate`,
`spring.jpa.open-in-view=false`, `spring.flyway.enabled=true`,
`locations=classpath:db/migration`, `validate-on-migrate=true`, `fail-on-missing-locations=true`.

**Mapping rules under `validate`** (boot fails on any mismatch):
- `Long` → `BIGINT`/`BIGSERIAL`; `String` → `TEXT`; **`Instant` → `TIMESTAMPTZ`** (not `TIMESTAMP`);
  `BigDecimal` → `NUMERIC(p,s)`.
- Audit columns get **no DB DEFAULT** — JPA callbacks own them. `archived_at` is nullable;
  `created_at`/`updated_at` are `NOT NULL`.
- Column names are snake_case throughout; FKs `location_id` reference `locations(id)`.

**Proposed S-02 migration shape** (plan to confirm exact columns):
```sql
-- V3__locations_and_garages.sql  (or split V3/V4)
CREATE TABLE locations (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT NOT NULL,
    archived_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);
CREATE TABLE garages (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    location_id     BIGINT NOT NULL REFERENCES locations(id),
    label           TEXT NOT NULL,
    monthly_rent    NUMERIC(10,2) NOT NULL,
    problem_reason  TEXT,            -- FR-006 free-text; NULL = not flagged
    archived_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);
```
Open design choices the plan owns: whether `locations`/`garages` carry an `owner_id` FK to
`users` (the app is single-owner — current entities like `OwnerAccount` carry no owner FK, so the
default is **no `owner_id`**; confirm), and whether `garages` stores `problem_reason` as a column
vs. a separate flag+reason. FR-005's "free/problem" status: **free** = no `problem_reason` and no
active contract; **problem** = `problem_reason` set; **rented** is derived from S-04 contracts and
is out of scope here.

### C. Vaadin views, layout, and owner-gating (F-01, S-01)

**Existing views:**
- `ui/HomeView.java:17-26` — `@Route(value="", layout=MainLayout.class)` + `@PageTitle` +
  `@PermitAll`, `extends VerticalLayout`. The post-login landing (`/`). **Mirror this for S-02 views.**
- `security/LoginView.java:22-49` — `@Route(value="login", autoLayout=false)` + `@AnonymousAllowed`,
  uses Vaadin `LoginForm` posting to Spring `/login`; `BeforeEnterObserver` flips the form to error
  state when `?error` present. **No custom error page → no owner-data leak surface** (R5 sub-check).
- `ui/MainLayout.java:24-43` — the app shell, `extends AppLayout`, `@PermitAll`. Header = `H1
  "GarageOps"` + logout button delegating to constructor-injected `AuthenticationContext.logout()`.
  Has **no nav menu yet**; `MainLayout.java:14` comment: "S-02+ add navigation here." **S-02 wires
  the Locations nav link here.**

**Security config** — `security/SecurityConfig.java:28-44` (`@Configuration @EnableWebSecurity`):
```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
	http.authorizeHttpRequests(auth -> auth
		.requestMatchers("/actuator/health").permitAll());
	http.with(VaadinSecurityConfigurer.vaadin(), cfg -> cfg.loginView(LoginView.class));
	return http.build();
}
```
This is the **ground-truth** V25 form in this repo (the `http.with(VaadinSecurityConfigurer.vaadin(), …)`
style). `/actuator/health` is the only HTTP-level public carve-out.

**The R5 gating contract** (what the plan must guarantee for every new domain route):
- **Denied-by-default.** Once `VaadinSecurityConfigurer` is applied, an unannotated `@Route` view is
  inaccessible (Vaadin treats missing annotation as `@DenyAll`). Confirmed in Vaadin 25 docs
  (protect-views: "all views are inaccessible unless explicitly annotated").
- **Annotate every view explicitly** with `@PermitAll` (flat single-owner role; equivalent to
  `@RolesAllowed("OWNER")` here). Do not rely on the implicit deny — annotate for clarity.
- **The parent layout must also carry an access annotation.** `MainLayout` is already `@PermitAll`
  (`MainLayout.java:24`); a child rendered in a layout with no annotation returns 403
  (`MainLayout.java:16-18` comment, corroborated by Vaadin add-router-layout docs).
- **Unauthenticated → 302 redirect to `/login`** (asserted by `SecurityGatingTests`).
- Vaadin views support **only** JSR-250 annotations (`@AnonymousAllowed`, `@PermitAll`,
  `@RolesAllowed`, `@DenyAll`) — **not** Spring's `@Secured`/`@PreAuthorize`.

**Form/CRUD pattern** — **not yet established**; S-01 only used the pre-built `LoginForm`. S-02 is
the first slice to introduce `Binder`-based forms (see §E). Services follow the
repository-backed `@Service` + constructor-injection pattern of `account/OwnerDetailsService.java:20-27`.

### D. Conventions & test base

- **Package-by-feature** (confirmed; no `controllers/`/`services/` layer packages). Existing
  packages: `account`, `security`, `persistence`, `ui`, root `GarageopsApplication`. **S-02 adds
  `com.example.garageops.locations` and `…garages`**, each co-locating entity + repository +
  service + view(s). `ArchivableEntity.java:13` and `DeploySmokeRecord.java:18` both name S-02 as
  the consumer.
- **Constructor injection only** — confirmed; the only `@Autowired` in the repo is a *test* field
  (`SecurityGatingTests.java:37`). Zero field/setter `@Autowired` in `src/main`.
- **Annotation discipline** — `@Service` (services), `@Configuration` (config), Vaadin `@Route`
  (web entry — there are **no `@RestController`s**; this is a server-rendered Vaadin app). The lone
  `@Component` is `OwnerBootstrap` (an `ApplicationRunner` lifecycle bean — allowed, not a role
  stereotype).
- **Tabs** for indentation in all Java sources (confirmed via byte check).
- **Test classes end in `Tests`.** Current suite (all **DB-free**):

  | Test | Scope | DB? | Mockito |
  |---|---|---|---|
  | `GarageopsApplicationTests` | `@SpringBootTest` context load | DB-free | — |
  | `persistence/ArchivableEntityTests` | FR-021 archive object behavior (idempotent archive, `@PrePersist`/`@PreUpdate`) | DB-free POJO | — |
  | `account/OwnerAccountTests` | entity stores BCrypt hash verbatim | DB-free POJO | — |
  | `account/OwnerBootstrapTests` | bootstrap idempotency (seed/no-op/abort) | DB-free | mocks repo + `ObjectProvider` |
  | `security/SecurityGatingTests` | unauth→`/login`, health public, valid login authenticates | DB-free | `@MockitoBean UserDetailsService` |

- **Boot 4 import note:** `@AutoConfigureMockMvc` comes from
  `org.springframework.boot.webmvc.test.autoconfigure` (the relocated Boot 4 package), per
  `SecurityGatingTests.java:13`.
- **`pom.xml`**: parent `spring-boot-starter-parent` **4.0.6** (`pom.xml:5-10`), `java.version=21`,
  `vaadin.version=25.1.6` via `vaadin-bom`. Starters: `spring-boot-starter-webmvc`,
  `-actuator`, `-security`, `-data-jpa`, `vaadin-spring-boot-starter`, `postgresql`,
  `spring-boot-flyway` + `flyway-core` + `flyway-database-postgresql`, test scope
  `spring-boot-starter-webmvc-test` + `spring-security-test`. **No Testcontainers, no H2.**

### E. Vaadin Flow 25 API grounding (via Vaadin MCP, checked 2026-06-03)

- **Version**: latest stable **25.1.6** (released 2026-05-25); V25 is the current free line and
  requires Java 21+ / Spring Boot 4.0.4+ — this project (4.0.6 / 21) is compatible. Pin the BOM to
  a concrete `25.1.x` (already `25.1.6`).
- **Grid** (`new Grid<>(Garage.class, false)`): value columns via `addColumn(...).setHeader(...)`;
  **status badge / inline action** via `addComponentColumn(g -> …)`; data via `setItems(List)`
  (returns `GridListDataView` for in-memory filter/refresh). **No native row-grouping** — for
  "garages grouped by location" use either (a) one `Grid` per location (simplest, matches a
  location→garages master view), (b) a single sorted Grid with a location column, or (c) `TreeGrid`
  only if collapsible groups are required. For S-02's small scale, (a) or (b) is the cheaper path.
- **Forms / Binder**: `Binder<Location>` with `forField(field).asRequired().withValidator(…).bind(getter, setter)`;
  use **`BigDecimalField`** for monthly rent (money — not `NumberField`/double). `BeanValidationBinder`
  auto-applies Jakarta `@NotBlank`/`@Size`. V25 change to flag: `writeBean()` no longer processes
  hidden fields by default (`setApplyBindingsToHiddenFields(true)` if needed).
- **Archive action → `ConfirmDialog`** (`setHeader/setText/setConfirmText`, `setCancelable(true)`,
  `addConfirmListener`, `error primary` theme). Aligns with FR-021 (no hard delete; confirm before
  archive).
- **"Mark as problem" reason → plain `Dialog`** with a `TextArea`/`TextField` (ConfirmDialog
  explicitly "isn't meant for collecting user input"); action buttons in `dialog.getFooter()`.
- **Routing/layout**: `@Route` + `AppLayout` (implements `RouterLayout`). V25 supports `@Layout`
  for automatic layout application; this repo uses the explicit `layout = MainLayout.class`
  parameter (mirror that). `@PermitAll` required on both the view and the layout.
- **Security primer (denied-by-default confirmed)**: docs state views are inaccessible unless
  annotated; `@DenyAll` is the implicit default. Use `VaadinSecurityConfigurer` (note:
  `VaadinWebSecurity` is **deprecated** in V25 — the repo already uses the configurer).

## Code References

- `src/main/java/com/example/garageops/persistence/BaseEntity.java:15-26` — `@MappedSuperclass`, IDENTITY id.
- `src/main/java/com/example/garageops/persistence/ArchivableEntity.java:10-77` — archive-only contract, audit callbacks, `archive()`/`isArchived()`.
- `src/main/java/com/example/garageops/persistence/DeploySmokeRecord.java:18-43` — reference entity; delete when S-02 lands.
- `src/main/java/com/example/garageops/persistence/DeploySmokeRecordRepository.java:9` — repo convention.
- `src/main/java/com/example/garageops/account/OwnerAccount.java:35-47` — entity constructor/column pattern.
- `src/main/java/com/example/garageops/account/OwnerAccountRepository.java:11-13` — custom finder convention.
- `src/main/java/com/example/garageops/account/OwnerDetailsService.java:20-27` — `@Service` + constructor injection.
- `src/main/resources/application.properties:33-45` — `ddl-auto=validate`, Flyway config.
- `src/main/resources/db/migration/V2__users.sql:6-11` — migration reference (IDENTITY, snake_case).
- `src/main/java/com/example/garageops/ui/HomeView.java:17-26` — view template to mirror.
- `src/main/java/com/example/garageops/ui/MainLayout.java:14,16-18,24-43` — app shell; nav-menu TODO; layout-gating note.
- `src/main/java/com/example/garageops/security/SecurityConfig.java:28-44` — gating filter chain (ground truth).
- `src/main/java/com/example/garageops/security/LoginView.java:22-49` — login view; no custom error page.
- `src/test/java/com/example/garageops/security/SecurityGatingTests.java:33-71` — gating test to extend for the new route.
- `src/test/java/com/example/garageops/persistence/ArchivableEntityTests.java:1-83` — archive-behavior unit test to mirror.
- `src/test/resources/application.properties:4-10` — the four autoconfig exclusions that make the suite DB-free (the blocker for R4 real-DB tests).
- `pom.xml:5-10,30-41,45-104` — Boot 4.0.6 / Vaadin 25.1.6 / starters; no Testcontainers.

## Architecture Insights

- **Archive = state, not deletion, and not a hidden row.** The deliberate choice against
  `@SoftDelete` means retention is *guaranteed by not deleting* and visibility is *chosen per
  query*. The plan must (a) never hard-delete, (b) decide cascade semantics so archiving a
  `Location` does not delete its `Garage` children (and, transitively, future contracts/payments),
  and (c) provide active-only finders (`findByArchivedAtIsNull`) for the default list views while
  keeping archived rows reachable for history. This is the heart of **R4**.
- **Security is denied-by-default + parent-layout-aware.** The cheapest correctness guarantee for
  **R5** is: annotate the new view `@PermitAll`, keep it under `MainLayout` (already `@PermitAll`),
  and add a redirect-to-login assertion for `/locations` to `SecurityGatingTests`.
- **`ddl-auto=validate` is a fail-fast schema gate** — a forgotten column or `TIMESTAMP` vs
  `TIMESTAMPTZ` slip breaks boot, not data. This is a feature; the plan should treat boot as the
  first integration test of the entity↔migration contract.
- **The DB-free test profile is load-bearing and must not be globally disabled.** It is *why*
  `OwnerDetailsService`/`OwnerBootstrap` inject `ObjectProvider<...Repository>`. S-02's unit/gating
  tests stay DB-free; the real-DB archive-retention test (R4) is **test-plan Phase 2** and must
  introduce Testcontainers + a per-test override of the exclude, not edit the shared properties.

## Historical Context (from prior changes)

- `context/archive/2026-05-27-jpa-persistence-foundation/` — established `BaseEntity`/
  `ArchivableEntity`, the archive-only-not-`@SoftDelete` decision, lifecycle-callback audit, and the
  `ddl-auto=validate` + Flyway contract S-02 inherits.
- `context/archive/2026-05-26-access-control-foundation/` — wired `VaadinSecurityConfigurer`,
  denied-by-default gating, `/actuator/health` carve-out, login redirect.
- `context/archive/2026-05-28-owner-auth-signup-login/` — `LoginView`, `MainLayout`, `HomeView`,
  DB-backed `OwnerAccount`/bootstrap, and `SecurityGatingTests`; the view + gating templates S-02 mirrors.

## Related Research

- `context/foundation/test-plan.md` §2 (R4 `:46`, R5 `:47`), §3 Phase 2 (`:82`) — the risk
  scenarios this slice first exercises and where their real-DB tests land.

## Open Questions (for the plan to resolve)

1. **`owner_id` FK on `locations`/`garages`?** App is single-owner and existing entities carry no
   owner FK — default is to omit it. Confirm against the plan's data model.
2. **Migration split** — one `V3__locations_and_garages.sql` vs. `V3__locations.sql` + `V4__garages.sql`.
3. **Problem flag storage** — single nullable `problem_reason TEXT` (NULL = not flagged) vs. a
   boolean + reason. FR-006 is a simple owner-toggled flag with free-text reason, no state machine.
4. **Garage status derivation** — confirm S-02 ships **free / problem** only; "rented" is derived
   from S-04 active contracts and must be designed as a future seam, not stubbed with fake data.
5. **Grouped display** — per-location Grid vs. single sorted Grid vs. TreeGrid (cost × signal favors
   the first two at S-02 scale).
6. **Cascade semantics** — explicit JPA relationship config so archiving a `Location` retains its
   `Garage` children (R4). Decide whether the UI even allows archiving a location with active garages.
