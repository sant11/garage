---
date: 2026-06-03T00:00:00+02:00
researcher: sant11
git_commit: df92a543ea4eb301cf3afe8f20100e21c6fd9ee7
branch: develop
repository: sant11/garage
topic: "S-03 tenant-management: tenant CRUD + profile, mirroring S-02; archive-retention & route-gating grounded"
tags: [research, codebase, tenants, jpa, vaadin, archive-only, security, FR-007, FR-008, FR-021]
status: complete
last_updated: 2026-06-03
last_updated_by: sant11
---

# Research: S-03 Tenant Management (FR-007, FR-008, FR-021)

**Date**: 2026-06-03T00:00:00+02:00
**Researcher**: sant11
**Git Commit**: df92a54 (`df92a543ea4eb301cf3afe8f20100e21c6fd9ee7`)
**Branch**: develop
**Repository**: sant11/garage

## Research Question

How should slice S-03 (`tenant-management`) be built so that it (a) mirrors the
proven S-02 (`portfolio-locations-garages`) patterns for entity / repository /
service / view / migration; (b) lands a tenant CRUD + profile page that is the
drill-through target for FR-008 contract history and the future S-07 late-payer
flag, **without over-reaching into S-04/S-07 scope**; and (c) satisfies the two
domain-slice risks flagged by `test-plan.md` — **R4 archive-retention (FR-021)**
and **R5 route-gating** — by copying patterns already verified in code?

Scope decisions (confirmed with user before research):
- **Forward scope: buildable-now + design seams.** Build what S-03 ships; document
  the Tenant↔Contract seam and profile extension points so S-04/S-07 graft cleanly.
- **Risk grounding: verify both R4 and R5** against the current S-02 codebase.

## Summary

**S-03 is a near-clone of S-02.** The codebase already has a complete, tested
CRUD-with-archive vertical for `Location`/`Garage`. `Tenant` should mirror
`Location` one-for-one: a standalone `ArchivableEntity` with `name` + contact
fields, a `JpaRepository` with an active-only finder, an `@Service` using
`ObjectProvider`-wrapped repositories, a `V5__tenants.sql` migration matching the
`V3` DDL style, and Vaadin views attached to `MainLayout` and gated with
`@PermitAll`.

**Three load-bearing conclusions:**

1. **Build the seam, not the dependency.** The house relationship pattern is
   *child-holds-the-FK, parent holds no collection* (`Garage` owns
   `@ManyToOne Location`; `Location` has zero `@OneToMany`). When S-04 adds
   `Contract`, the FK lives on `Contract` — so **`Tenant` adds zero
   contract-facing fields/collections today.** The profile's "current and past
   contracts" section is rendered as a friendly empty-state `Paragraph` inside an
   *isolated builder method* that S-04 fills by changing one method body. This
   exactly follows the precedent by which S-02 deferred "rented" status (`plan.md`
   "never stubbed with fake data").

2. **R4 (archive-retention) is SAFE by construction.** Archiving is a pure
   `archivedAt` flag-flip (UPDATE), never a delete. There is **zero**
   `CascadeType` / `orphanRemoval` / `@OneToMany` anywhere in production code;
   cascade-archive is an explicit service-layer stamp pass. Copying
   `LocationService.archive()` for `TenantService.archive()` inherits this
   guarantee. The one coverage gap: no integration test proves the *persistence
   layer* emits no DELETE (current proof is mock-level `never().delete(...)`).

3. **R5 (route-gating) is SAFE by default.** The `VaadinSecurityConfigurer` chain
   is **default-deny**: a new `@Route` view that *forgets* its access annotation
   is redirected to login, not exposed. Owner-gating a view is the single step of
   adding `@PermitAll` (the convention for the flat single-owner role). Danger
   only arises from an *explicit* `@AnonymousAllowed` mistake. Gap: no test
   asserts the redirect/error body is data-free, and the default-deny net itself
   is untested.

## Detailed Findings

### Area 1 — Persistence & domain layer (the Tenant entity/repo/service template)

**Base classes.**
- `persistence/BaseEntity.java:18-20` — `@MappedSuperclass`, `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`, `Long` id, **no** equals/hashCode (JPA identity by surrogate key). `getId()` is `protected`.
- `persistence/ArchivableEntity.java:31-38` — adds `archived_at`, `created_at`, `updated_at` as `Instant` (→ `TIMESTAMPTZ`); `@PrePersist`/`@PreUpdate` callbacks (lines 40-50) stamp audit times (no Spring Data `@EnableJpaAuditing`, deliberately, to keep the test context DB-free). `archive()` (52-59) is **idempotent**; `isArchived()` (62) returns `archivedAt != null`. Docstring (14-18): deliberately **not** Hibernate `@SoftDelete` — archived rows stay queryable for history.

**Entity exemplar (mirror `Location`).**
- `locations/Location.java:18-20` — `@Entity @Table(name="locations") extends ArchivableEntity`. Field `name` with `@NotBlank @Column(nullable=false)` (22-24). `protected` no-arg ctor + `public Location(String name)` business ctor (26-32). `getId()` overridden to **widen to public** (39-42) so views can pass IDs. Mutator `rename(String)`.
- `garages/Garage.java:33-35` — the LAZY-FK pattern (only needed by S-03 if a parent FK is added; tenants have none): `@ManyToOne(fetch = FetchType.LAZY, optional=false) @JoinColumn(name="location_id", nullable=false)`, **no cascade/orphanRemoval**.

**Repository (mirror `LocationRepository`).**
- `locations/LocationRepository.java:11-13` — `extends JpaRepository<Location, Long>` + `findByArchivedAtIsNullOrderByNameAsc()` (active-only, name-sorted, for the default view). Archived rows stay reachable via inherited `findById`/`findAll`.
- `garages/GarageRepository.java:18-26` — when a query must traverse a LAZY association off-session, use explicit `JOIN FETCH` (the AGENTS.md rule under `open-in-view=false`). Not needed by the tenant *list* (no associations) but the pattern to copy if the profile ever batch-loads associated rows.

**Service (mirror `LocationService`).**
- `locations/LocationService.java:28-39` — `@Service`, **constructor injection only**, repositories wrapped in `ObjectProvider<...>` so the service instantiates in DB-free test contexts (repos resolved lazily via private `locations()`/`garages()` helpers, 79-85).
- Methods: `add(name)` (42-44), `rename(id,name)` (load→mutate→save), `listActive()` (returns `findByArchivedAtIsNull...`). `archive(id)` is the only `@Transactional` method (63-72): stamp parent, save, then **service-layer cascade stamp** of active children — never a delete.
- `garages/GarageService.java:43-50` — child `add` validates the parent exists and is **not archived** (`IllegalStateException`); `EntityNotFoundException` for unknown ids.

**Migration (write `V5__tenants.sql`).**
- Highest migration today is `V4__drop_deploy_smoke_test.sql`; **next is `V5`.**
- `db/migration/V3__locations_and_garages.sql:7-23` — DDL style to copy: `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, business columns as `TEXT NOT NULL` / optional `TEXT`, audit columns `archived_at TIMESTAMPTZ` (nullable) + `created_at`/`updated_at TIMESTAMPTZ NOT NULL` (JPA callbacks own the values — **no DB DEFAULT**). FKs as `BIGINT NOT NULL REFERENCES parent(id)`.
- `application.properties:37-38` — `ddl-auto=validate` (Flyway owns schema; entity must match migrated table) and `open-in-view=false` (mandates `JOIN FETCH` for off-session LAZY traversal).

### Area 2 — Vaadin views, navigation & gating (the list/profile/form template)

Stack: **Vaadin Flow 25.1.6**, Spring Boot **4.0.6**, server-side Java UI. All
views bind to a single `MainLayout` (`AppLayout`).

**Routes & landing.**
- `locations/LocationsView.java:47-49` — `@Route(value="locations", layout=MainLayout.class)`, `@PageTitle("Locations")`, `@PermitAll`, `extends VerticalLayout`, constructor-injects services.
- `ui/HomeView.java:17-19` — `@Route(value="", layout=MainLayout.class)` `@PermitAll` — the post-login landing.
- `security/LoginView.java:22-24` — `@Route(value="login", autoLayout=false)` `@AnonymousAllowed`, `implements BeforeEnterObserver` to read login-error query params.

**Parameterized detail route (NEW pattern — none exists yet).** The tenant profile
(FR-008) will be the **first** detail view. Vaadin-idiomatic shape:
`@Route(value="tenants/:id", layout=MainLayout.class)` + `implements
HasUrlParameter<Long>` + `setParameter(BeforeEvent, Long id)` to load and render;
throw `RouteNotFoundException` (from `com.vaadin.flow.router`) if the id is
unknown or the tenant is archived (→ 404). Navigate from the list via
`UI.getCurrent().navigate("tenants/" + id)`.

**Navigation.**
- `ui/MainLayout.java` — `AppLayout` with `DrawerToggle` + header (app name + logout via `AuthenticationContext.logout()`) and a `SideNav`. **Add the tenants entry** at the `SideNav` block (around `MainLayout.java:51`): `nav.addItem(new SideNavItem("Tenants", TenantsView.class))`.

**List + CRUD UI (copy from `LocationsView`).**
- Grid: `new Grid<>(Tenant.class, false)` + `addColumn(...)` / `addComponentColumn(...)` for actions, `setItems(service.listActive())`, `setAllRowsVisible(true)` (no pagination) — pattern at `LocationsView.java:113-119`.
- Add/edit dialog: `Dialog` + `Binder<Tenant>` with `asRequired(...)`/`withValidator(...)`, bound to a **throwaway bean** so keystrokes don't mutate the live list entity; on save validate → call service → `dialog.close()` → `refresh()` — pattern at `LocationsView.java:160-197`.
- Archive: `ConfirmDialog` with consequence-explaining text ("Records are retained, not deleted."), `setConfirmButtonTheme("error primary")`, confirm listener → `service.archive(id)` → `refresh()` — `LocationsView.java:277-293`.
- Empty-state: check `listActive().isEmpty()` → friendly `Paragraph` (not "0 results") → return early — `LocationsView.java:84-86`. (Matches US-01 empty-state spirit.)
- The single post-mutation hook is a private `refresh()` that re-fetches and rebuilds.

**Owner-gating (R5 — see Area 3 for the verdict).**
- `security/SecurityConfig.java:32-38` — `VaadinSecurityConfigurer.vaadin()` with `loginView(LoginView.class)`; only HTTP carve-out is `requestMatchers("/actuator/health").permitAll()`. `MainLayout` itself is `@PermitAll` (gates the whole nav chain).
- **A new owner-gated view needs exactly one thing: `@PermitAll`.** (Single-owner ⇒ any authenticated principal is the owner; `@RolesAllowed` is not the house convention.)

### Area 3 — Risk grounding (R4 archive-retention, R5 route-gating)

**R4 — Archive-retention (FR-021). VERDICT: SAFE by construction.**
- Archive is a flag-flip UPDATE: `ArchivableEntity.archive()` stamps `archivedAt` only if null (idempotent); `LocationService.archive()` (`:63-72`) and `GarageService.archive()` (`:74-78`) only ever `save()`/`saveAll()`, never delete.
- **Zero** `CascadeType` / `orphanRemoval` / `@OneToMany` / `@OneToOne` in production code (repo-wide grep — every textual hit is Javadoc documenting their deliberate absence: `Garage.java:23-24`, `Location.java:12-13`, `LocationService.java:20`). `Location` holds no collection of `Garage`, so no inverse side can cascade a remove. Cascade-archive is achieved entirely in the service layer.
- Tests: `LocationServiceTests.archiveCascadeStampsActiveGaragesAndDeletesNothing` (`:77-99`) asserts parent + children `isArchived()` and `verify(..., never()).delete/deleteById/deleteAll` on **both** repos; `GarageServiceTests.archiveStampsTheGarageWithoutDeleting` (`:98-108`) the same; `ArchivableEntityTests` locks the idempotency/audit contract (pure POJO).
- **Gap:** all service tests mock repositories — **no integration test (`@DataJpaTest`/real EntityManager) proves JPA itself issues no DELETE / cascades nothing at the SQL layer.** The guarantee rests on annotation-absence + mock-level `never().delete()`. (This is precisely what `test-plan.md` §3 Phase 2 is scoped to add.)

**R5 — Domain route not owner-gated. VERDICT: SAFE by default (fail-closed).**
- `SecurityConfig.java:32-38` delegates view access to Vaadin's checker; the chain **denies any request that isn't a framework request, an `@AnonymousAllowed` view, or an explicitly permitted matcher.** A new `@Route` with **no** annotation → redirect to login, not exposure. Leakage requires an *active* `@AnonymousAllowed` mistake.
- `SecurityGatingTests.java` asserts the denied path: `GET /` and `GET /locations` both 3xx → `redirectedUrl("/login")` (`:54-66`); `/actuator/health` → 200; valid login authenticates.
- **No custom error view exists** (no `HasErrorParameter`); Spring's default `/error` + Vaadin default internal-error handling apply. Gated data-views + generic error metadata ⇒ no obvious owner-data leak, but **unverified by test**.
- **Gaps:** no test proves an *un-annotated* route is denied (the safety net itself), and no test asserts the redirect/error **body** carries no owner data (only the redirect URL is checked).

### Area 4 — Tenant↔Contract seam & profile extension points

**House relationship rule (verified): child-holds-the-FK, parent holds NO collection.**
- `Garage.java:33-35` is the only side of the Location↔Garage association; `Location.java:20-52` has no `@OneToMany`, no `List<Garage>`. Cross-entity reads live in a **service method**, not a parent-side collection: `GarageService.listActiveByLocations()` (`:89-95`) batch-loads children via a repo finder and groups with `Collectors.groupingBy(...)`.

**S-02 deferral precedent (the model to follow):** S-02 faced the identical
forward-dependency — garage "rented" status depends on S-04 contracts — and
deferred it by **naming the gap in prose and shipping zero contract-facing
structure** (no stub field, no fake collection, no placeholder enum):
- `plan.md:71-73` (archived S-02): *"'Rented' status … This slice ships free / problem only; rented is left as a future seam (a status enum/derivation point), **never stubbed with fake data**."*
- `research.md:316` (archived S-02): *"'rented' … **must be designed as a future seam, not stubbed with fake data**."*
- Mechanism: the status badge (`LocationsView.java:131-139`) computes from `garage.isProblem()` — a method that exists today. **The seam is a method boundary, not a data structure.**

**Recommendations:**

1. **`Tenant` adds zero contract-facing fields/collections.** No `@OneToMany List<Contract>` (would violate the no-parent-collection rule, is un-mappable today since `Contract` doesn't exist, and `ddl-auto=validate` would fail at boot). When S-04 lands, `Contract` carries `@ManyToOne Tenant` (FK on `Contract`, mirroring `garages.location_id`) — created entirely on the S-04 side. `Tenant` = plain `ArchivableEntity` with name + contact.

2. **Render "current and past contracts" honestly now.** Put the contract-history section in its **own private builder method** (e.g. `contractsSection()` returning a `Component`, mirroring `LocationsView.locationSection(...)` at `:97`), rendering a house-style empty-state `Paragraph` ("No contract history yet. Contracts arrive in a later slice."). A class-doc note (mirroring `HomeView.java:14-16`, `Garage.java:23-27`) records that S-04 fills it. S-04 then swaps the `Paragraph` for a `Grid<Contract>` fed by a new `contractService` finder **by changing one method body — no view restructure, no route change.**

3. **S-07 late-payer flag gets a layout anchor only — build nothing.** Construct the profile header as a `HorizontalLayout(name, actions)` (mirroring `LocationsView.java:108-111`) so S-07 can insert a derived badge `Span` beside the name later (the `statusBadge` precedent). **No `latePayer` field/column/enum** (it is derived, not stored), and **no placeholder badge** (an always-absent badge would falsely signal "not a late payer"). Seam = a future derivation method + a header slot S-03 leaves open.

## Code References

- `persistence/BaseEntity.java:18-20` — IDENTITY surrogate key, no equals/hashCode.
- `persistence/ArchivableEntity.java:31-64` — archive-only soft-delete: audit fields, callbacks, idempotent `archive()`, `isArchived()`.
- `locations/Location.java:18-52` — entity exemplar (extends ArchivableEntity, `@NotBlank` field, ctors, public `getId()`).
- `garages/Garage.java:23-35` — LAZY `@ManyToOne` FK pattern, doc on deliberate no-cascade.
- `locations/LocationRepository.java:11-13` — active-only finder `findByArchivedAtIsNullOrderByNameAsc()`.
- `garages/GarageRepository.java:18-26` — `JOIN FETCH` for off-session LAZY (open-in-view=false).
- `locations/LocationService.java:28-85` — `@Service`, ctor injection, `ObjectProvider`, `@Transactional archive()` cascade-stamp.
- `garages/GarageService.java:43-95` — parent-active validation; `listActiveByLocations()` service-layer grouping.
- `db/migration/V3__locations_and_garages.sql:7-23` — DDL template for `V5__tenants.sql`.
- `application.properties:37-38` — `ddl-auto=validate`, `open-in-view=false`.
- `ui/MainLayout.java` (~`:51`) — `SideNav` where the "Tenants" item is added.
- `locations/LocationsView.java:47-49,84-86,113-119,160-197,277-293` — route/gating, empty-state, grid, add/rename dialog+Binder, archive ConfirmDialog.
- `ui/HomeView.java:14-25` — "comes later" placeholder precedent for the profile.
- `security/SecurityConfig.java:32-38` — `VaadinSecurityConfigurer`, default-deny, health carve-out.
- `security/LoginView.java:22-24` — `@AnonymousAllowed` login route.
- `security/SecurityGatingTests.java:54-78` — asserted denied/anonymous + login paths.
- `locations/LocationServiceTests.java:77-99` — R4 oracle (`never().delete*()`).
- `garages/GarageServiceTests.java:98-108` — R4 child stamp-without-delete.

## Architecture Insights

- **Vertical-slice clone is the cheapest correct path.** `Location` ≈ `Tenant`
  (standalone archivable entity, no parent). The whole stack (entity→repo→
  service→view→migration→tests) has a working, tested exemplar to copy.
- **Constructor injection + `ObjectProvider<Repository>`** is the house DI idiom —
  it keeps services instantiable in the DB-free unit-test context (JPA autoconfig
  excluded in the test profile per `test-plan.md` §4).
- **Archive-only is enforced in the service layer, by construction, not by JPA
  cascade.** No annotation in the entity graph can drop a child; the only way to
  remove data is an explicit `delete*` call that the codebase never makes and the
  tests forbid.
- **Security fails closed.** Default-deny means the dangerous direction (forgetting
  to gate) is safe; the only live risk is an explicit `@AnonymousAllowed` slip.
- **"Build the seam, not the dependency"** is an established team norm (S-02
  rented-status), not a one-off — apply it to the contract list and late-payer
  flag.
- **One stack correction:** the project is Spring Boot **4.0.6** / Vaadin **25.1.6**
  (one sub-agent's prose said "Boot 3.x" — incorrect; verified against `pom.xml`).

## Historical Context (from prior changes)

- `context/archive/2026-05-31-portfolio-locations-garages/plan.md:71-73` — S-02
  deferred "rented" status as "a future seam … never stubbed with fake data." The
  direct precedent for deferring the contract list and late-payer flag here.
- `context/archive/2026-05-31-portfolio-locations-garages/research.md:316` — same
  norm phrased as an open question resolution.
- Roadmap "Done" note (`roadmap.md:222`): S-02's lesson — *@ManyToOne LAZY needs
  explicit fetch-joins on off-session view/grouping paths (open-in-view=false)* —
  codified in AGENTS.md. Relevant only if the profile later batch-loads
  associations; the tenant list/profile-shell has none.
- `context/archive/2026-05-28-owner-auth-signup-login/plan.md` — origin of the
  "view access is annotation-driven and easy to forget" observation that seeded
  test-plan R5; this research confirms the default is **fail-closed**.

## Related Research

- `context/foundation/test-plan.md` §2 (R4, R5), §3 Phase 2 (real-DB harness +
  retention & gating) — this research grounds those risks against current code and
  flags the integration-test gap Phase 2 will close.
- `context/archive/2026-05-31-portfolio-locations-garages/research.md` — the S-02
  research this slice's structure parallels.

## Open Questions

1. **Contact-info shape (FR-007).** PRD says "at minimum a name and contact info";
   FR-007 resolution notes the owner may put an ID in the contact field. Is
   `contactInfo` a single free-text `TEXT` field, or split (phone/email)? PRD
   leans single free-text. → resolve in `/10x-plan` (recommend single `TEXT`,
   optional, to match the "minimum name + contact" wording and avoid `@Email`
   over-constraint).
2. **Should the profile be reachable now, or only the list?** FR-008 requires the
   profile; the contract section is empty until S-04. Recommend building the
   profile route + shell now (it is the FR-018 drill-through target and S-07
   surface) — consistent with the change.md note.
3. **R4 integration-test gap** — owned by `test-plan.md` §3 Phase 2, not by S-03's
   feature plan. S-03 should at minimum copy the `never().delete*()` service-test
   assertion for `TenantService`.
4. **Archived-tenant guard on add (forward).** When S-04 adds contracts, creating a
   contract against an archived tenant should be blocked (mirror
   `GarageService.add` archived-parent guard). Not S-03 scope, but the validation
   seam belongs on `TenantService`.
</content>
</invoke>
