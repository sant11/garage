# JPA Persistence + Archive-Only Foundation (F-02) Implementation Plan

## Overview

Swap the scaffolded `spring-boot-starter-jdbc` for `spring-boot-starter-data-jpa` and establish the
persistence conventions every entity-backed slice (S-02, S-03, S-04) will inherit:

- a two-layer base type — `BaseEntity` (surrogate `Long id`, `IDENTITY` generation) and
  `ArchivableEntity` (the FR-021 **archive-only** `archived_at` state + created/updated audit via JPA
  lifecycle callbacks);
- a Spring Data repository convention;
- Flyway-owns-schema JPA configuration (`ddl-auto=validate`, `open-in-view=false`).

It is a *foundation* slice. It proves the JPA stack end-to-end with **one reference entity/repository
mapped onto the existing `deploy_smoke_test` table** — no new domain schema is invented. Domain entities
(locations, garages, tenants, contracts, payments) land in their own slices, extending these base types.

## Current State Analysis

- **Persistence is JDBC-scaffolded.** `pom.xml:47` declares `spring-boot-starter-jdbc`; there is no JPA
  starter, no entity, no repository. Postgres driver (`pom.xml:50-53`), Flyway (`pom.xml:55-66`), and a
  HikariCP pool tuned for the 512MB tier (`application.properties:23-28`) are all wired.
- **Flyway owns schema and validates checksums.** `application.properties:31-35` —
  `spring.flyway.validate-on-migrate=true`. The only migration is `V1__init.sql`, a `deploy_smoke_test`
  table (`id BIGSERIAL PK`, `deployed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `note TEXT NOT NULL`) that
  is **already applied in the deployed DB**. Editing V1 would break checksum validation — it must stay
  immutable.
- **No JPA/Hibernate settings exist.** `application.properties` has datasource + Hikari + Flyway only.
- **Tests are deliberately DB-free.** `src/test/resources/application.properties:6-8` excludes
  DataSource + Flyway autoconfig by their **Boot-4 per-module FQNs**
  (`org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`,
  `org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration`) so `GarageopsApplicationTests`
  and `SecurityGatingTests` run without a database. Adding JPA introduces an `EntityManagerFactory` that
  would try to autoconfigure against an absent DataSource — the exclusion list must grow accordingly.
- **Sibling foundation F-01 is merged** and sets the house style: thin foundation, one feature package
  (`com.example.garageops.security`), constructor injection only, `@Configuration`/`@Service` not
  `@Component`, an automated test that *locks the contract* so slices inherit a guardrail.

### Key Discoveries:

- **Boot 4.0.6 moved autoconfig into per-module packages** (the same change that renamed `-starter-web`
  → `-starter-webmvc`). Verified FQNs for JPA:
  - `org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration` (builds the EMF)
  - `org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration` (Spring Data
    repositories; activates only when a `DataSource` bean exists, so it already backs off under the
    existing DataSource exclusion — listed explicitly for clarity).
  The **starter artifactId is unchanged**: `spring-boot-starter-data-jpa` (parent-managed, no version).
- **`spring-boot-starter-data-jpa` transitively includes `spring-boot-starter-jdbc`** (JDBC + HikariCP),
  so removing the explicit jdbc starter loses nothing — the datasource/Hikari config stays valid.
- **`@SoftDelete` is the wrong archive mechanism here.** Hibernate's `@SoftDelete` globally *hides*
  archived rows from reads, which directly fights FR-008 (view a tenant's past contracts) and FR-011
  (a garage's full rental history). Archive must be an explicit, queryable **state** (`archived_at`
  timestamp), not a row-hiding filter.
- **`@EnableJpaAuditing` needs an `EntityManagerFactory` at context-startup**, which the DB-free test
  profile excludes — so Spring Data auditing would break `contextLoads`. JPA lifecycle callbacks
  (`@PrePersist`/`@PreUpdate`) give created/updated timestamps with zero config and no test-context
  coupling.
- The reference entity must map **only columns that exist in `deploy_smoke_test`** (no `archived_at`
  there) or `ddl-auto=validate` fails at boot — hence it extends `BaseEntity` (id only), not
  `ArchivableEntity`.

## Desired End State

After this plan:

1. `spring-boot-starter-data-jpa` replaces `spring-boot-starter-jdbc`; the app boots a Hibernate
   `EntityManagerFactory` over the existing Hikari datasource.
2. `spring.jpa.hibernate.ddl-auto=validate` and `spring.jpa.open-in-view=false` are set; Flyway remains
   the sole schema authority.
3. `com.example.garageops.persistence` holds the inheritable conventions: `BaseEntity` (id, IDENTITY) and
   `ArchivableEntity` (`archived_at` state + `created_at`/`updated_at` lifecycle-callback audit + an
   `archive()`/`isArchived()` API).
4. A reference entity `DeploySmokeRecord` (mapped to `deploy_smoke_test`) + `DeploySmokeRecordRepository`
   (`extends JpaRepository`) prove the full JPA stack against a real table.
5. The test profile excludes the two JPA autoconfig FQNs so `GarageopsApplicationTests` and
   `SecurityGatingTests` still run **without a database**.
6. A DB-free unit test locks the archive-only convention (`archive()`/`isArchived()`/`archived_at` +
   lifecycle timestamps), so slices inherit a tested base.

Verify: `mvnw.cmd verify` passes; manually, `mvnw.cmd spring-boot:run` against a real Postgres boots clean
under `ddl-auto=validate` (a `DeploySmokeRecord`↔`deploy_smoke_test` mapping mismatch would fail startup —
that boot is the proof the mapping is correct).

## What We're NOT Doing

- **No domain entities or tables** — locations, garages, tenants, contracts, payments are their slices'
  work (S-02/S-03/S-04). This foundation only proves the stack and ships the base conventions.
- **No new Flyway migration.** V1 stays immutable; the reference entity maps onto its existing table. No
  V2, no DROP of the smoke table (that happens when a real domain table supersedes it).
- **No `@SoftDelete`** — archive is an explicit `archived_at` state, not a global read filter.
- **No `@EnableJpaAuditing` / Spring Data auditing** — audit timestamps come from JPA lifecycle callbacks
  to avoid the EMF-at-startup conflict with the DB-free test. No `createdBy`/`modifiedBy` (single owner).
- **No Testcontainers / H2 / live-DB automated test** — the suite stays DB-free; mapping correctness is a
  manual real-Postgres check. (Automated DB testing is a Module-3 / later concern.)
- **No repository business methods** — `JpaRepository` defaults only; slice-specific finders land with the
  slices that need them.
- **No changes to F-01 security wiring** beyond what the shared test profile requires.

## Implementation Approach

Replace the jdbc starter with data-jpa in `pom.xml`, add the two JPA properties to
`application.properties`, and introduce a new `com.example.garageops.persistence` package
holding `BaseEntity`, `ArchivableEntity`, the `DeploySmokeRecord` reference entity, and its repository.
This package is a deliberate **shared-kernel** exception to the package-by-feature rule: it holds the
cross-cutting base types every feature inherits (which have no single feature home), not a by-layer
bucket for controllers/services. `DeploySmokeRecord` is a temporary tenant here — retired in S-02 when
the first domain table supersedes the smoke table.
Grow the test-profile autoconfig exclusion list with the two Boot-4 JPA FQNs so the existing DB-free tests
stay green. Then add a pure-POJO unit test that pins the archive-only state behavior so downstream slices
can't silently break the FR-021 convention.

## Critical Implementation Details

- **`ddl-auto=validate` is the foundation's load-bearing guarantee, and it runs at real-app startup, not
  in tests.** The DB-free test profile excludes JPA autoconfig, so validation only happens when
  `spring-boot:run` (or a deploy) connects to a real Postgres. The reference entity therefore must map
  exactly to `deploy_smoke_test`'s columns; a drift surfaces as a startup failure during manual
  verification.
- **The reference entity extends `BaseEntity`, never `ArchivableEntity`.** `deploy_smoke_test` has no
  `archived_at` column and V1 is immutable, so an `ArchivableEntity`-derived mapping would fail `validate`.
  `archived_at` is thus compile-checked and unit-tested here but first exercised against a real column in
  S-02's migration.
- **Test-profile exclusions must keep the context DB-free.** After adding JPA, confirm
  `GarageopsApplicationTests.contextLoads` and `SecurityGatingTests` still pass with no database — that
  green run is the signal the exclusion FQNs are correct (the same empirical approach commit `2ab8848`
  used for the JDBC/Flyway FQNs).

## Phase 1: JPA wiring & base conventions

### Overview

Swap the starter, add JPA config, create the base types + reference entity/repository, and update the test
profile so the app boots on JPA against real Postgres while the automated suite stays DB-free.

### Changes Required:

#### 1. Swap the persistence starter

**File**: `pom.xml`

**Intent**: Replace the scaffolded JDBC starter with the JPA starter so Hibernate + Spring Data JPA are on
the classpath. data-jpa transitively brings JDBC + HikariCP, so the existing datasource/pool config is
unaffected.

**Contract**: Remove the `spring-boot-starter-jdbc` `<dependency>` (`pom.xml:45-48`) and add
`org.springframework.boot:spring-boot-starter-data-jpa` (no `<version>`, parent-managed) in its place,
alongside the other `spring-boot-starter-*` entries. Preserve tab indentation and the Boot-4 starter
naming convention.

#### 2. JPA configuration

**File**: `src/main/resources/application.properties`

**Intent**: Pin Flyway as the sole schema authority and disable open-session-in-view (avoid the OSIV
anti-pattern and its startup warning; make lazy-loading boundaries explicit).

**Contract**: Add `spring.jpa.hibernate.ddl-auto=validate` and `spring.jpa.open-in-view=false` under a
`# --- JPA ---` heading. Do **not** set a Hibernate dialect — it is auto-detected from the Postgres
connection. No change to the datasource/Hikari/Flyway blocks.

#### 3. Base entity convention

**File**: `src/main/java/com/example/garageops/persistence/BaseEntity.java`

**Intent**: The universal surrogate-key base every entity extends — "has an id" separated from "is
archivable."

**Contract**: A `@MappedSuperclass public abstract class BaseEntity` with a single field
`Long id` annotated `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)` (maps to Postgres
`BIGSERIAL`/`GENERATED AS IDENTITY`), plus a protected `getId()`. Tab-indented; no Lombok (not on the
classpath).

#### 4. Archive-only + audit base

**File**: `src/main/java/com/example/garageops/persistence/ArchivableEntity.java`

**Intent**: Establish the FR-021 archive-only convention as an explicit, queryable state plus created/
updated audit timestamps — the base all archivable domain entities (tenants, garages, contracts) extend.

**Contract**: A `@MappedSuperclass public abstract class ArchivableEntity extends BaseEntity` with:
- `Instant archivedAt` (nullable, column `archived_at`) — null means active.
- `Instant createdAt` (column `created_at`, updatable=false) and `Instant updatedAt` (column
  `updated_at`), populated by `@PrePersist` (set both) and `@PreUpdate` (set updatedAt) lifecycle
  callbacks — **no `@EnableJpaAuditing`**.
- Behavior API: `void archive()` (sets `archivedAt = Instant.now()` if not already archived),
  `boolean isArchived()` (`archivedAt != null`). Archiving is a state transition that **retains** the
  row — never a delete.

A short class Javadoc must state: archive is a visible state, not a row-hiding filter; archived records
stay queryable (FR-008/FR-011); slices add `archived_at`/`created_at`/`updated_at` columns to their own
Flyway migrations, declaring them as `timestamptz` to match the `Instant` mapping (Hibernate maps
`Instant` to TIMESTAMP WITH TIME ZONE — a plain `timestamp` column would fail `ddl-auto=validate`).

#### 5. Reference entity (JPA-stack proof)

**File**: `src/main/java/com/example/garageops/persistence/DeploySmokeRecord.java`

**Intent**: Prove the JPA mapping + `ddl-auto=validate` work against a real table without inventing domain
schema, by mapping the existing smoke table.

**Contract**: `@Entity @Table(name = "deploy_smoke_test") public class DeploySmokeRecord extends
BaseEntity` with fields mapping the table's existing columns: `Instant deployedAt`
(`@Column(name = "deployed_at", insertable = false, updatable = false)` — DB default `NOW()` owns it) and
`String note`. Extends `BaseEntity` (id only), **not** `ArchivableEntity`. Class Javadoc: temporary
JPA-stack proof; delete once the first domain table supersedes the smoke table.

#### 6. Reference repository

**File**: `src/main/java/com/example/garageops/persistence/DeploySmokeRecordRepository.java`

**Intent**: Demonstrate the Spring Data repository convention slices will follow.

**Contract**: `public interface DeploySmokeRecordRepository extends JpaRepository<DeploySmokeRecord, Long>`.
No custom methods.

#### 7. Keep the test suite DB-free

**File**: `src/test/resources/application.properties`

**Intent**: Adding JPA introduces an `EntityManagerFactory` that would autoconfigure against the absent
test DataSource and break `contextLoads`/`SecurityGatingTests`; exclude the JPA autoconfig so the suite
stays DB-free.

**Contract**: Extend the existing `spring.autoconfigure.exclude` list (currently DataSource + Flyway) with:
- `org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration`
- `org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration`

Preserve the line-continuation (`\`) formatting. Verify empirically: the two existing tests must pass
DB-free after the swap (mirrors the `2ab8848` precedent for the JDBC/Flyway FQNs).

### Success Criteria:

#### Automated Verification:

- Project compiles: `mvnw.cmd -q compile`
- Existing context test passes DB-free with JPA on the classpath:
  `mvnw.cmd test -Dtest=GarageopsApplicationTests`
- F-01 gating test still passes DB-free: `mvnw.cmd test -Dtest=SecurityGatingTests`
- Full build passes: `mvnw.cmd verify`

#### Manual Verification:

> **Prerequisite:** a reachable Postgres (local on `:5433` or `PG*` pointed at the Railway DB) with the V1
> migration applied. `spring-boot:run` boots DataSource + Flyway + JPA (main profile, no exclusions).

- `mvnw.cmd spring-boot:run` starts cleanly with `ddl-auto=validate` — **no Hibernate schema-validation
  error**, confirming `DeploySmokeRecord` matches `deploy_smoke_test`.
- The startup log shows Flyway at the existing V1 (no new migration applied) and Hibernate validating, not
  creating/altering, the schema.
- No open-in-view warning in the log (OSIV disabled).

**Implementation Note**: After automated verification passes, pause for manual confirmation that the
real-Postgres boot under `validate` succeeded before proceeding to Phase 2. Phase blocks use plain
bullets — the `- [ ]` checkboxes live in `## Progress`.

---

## Phase 2: Lock the archive-only convention with a test

### Overview

Add a DB-free unit test that pins the FR-021 archive-only state behavior and the audit-timestamp callbacks,
so every downstream slice inherits a *tested* base rather than an unverified one.

### Changes Required:

#### 1. Archive-convention unit test

**File**: `src/test/java/com/example/garageops/persistence/ArchivableEntityTests.java`

**Intent**: Assert the load-bearing archive-only semantics as pure object behavior — no database, no Spring
context needed — so the FR-021 contract can't silently regress.

**Contract**: A plain JUnit 5 test using a minimal concrete `ArchivableEntity` subclass (a tiny test-local
fixture entity) asserting:
- a new instance is **not** archived (`isArchived()` false, `archivedAt` null);
- `archive()` sets `archivedAt` non-null and flips `isArchived()` to true;
- calling `archive()` again does **not** move the timestamp (idempotent — preserves the original archive
  moment);
- the `@PrePersist` callback sets both `createdAt` and `updatedAt`; the `@PreUpdate` callback advances
  `updatedAt` while leaving `createdAt` untouched (invoke the callbacks directly — no persistence).

Class name ends in `Tests` per the project convention.

### Success Criteria:

#### Automated Verification:

- New convention test passes: `mvnw.cmd test -Dtest=ArchivableEntityTests`
- Existing tests still pass: `mvnw.cmd test -Dtest=GarageopsApplicationTests` and
  `mvnw.cmd test -Dtest=SecurityGatingTests`
- Full build + suite passes: `mvnw.cmd verify`

#### Manual Verification:

- Reviewer confirms the test exercises the real `ArchivableEntity` behavior (archive state transition +
  lifecycle-callback timestamps), not a mock, and runs without a database.

**Implementation Note**: After automated verification passes, pause for manual confirmation before
considering the change complete.

---

## Testing Strategy

### Unit Tests:

- `ArchivableEntityTests` — archive state transition (not-archived → archived, idempotent timestamp) and
  `@PrePersist`/`@PreUpdate` audit-timestamp behavior. Pure POJO, DB-free.

### Integration Tests:

- None automated in this slice (suite is deliberately DB-free). The real-Postgres `spring-boot:run` under
  `ddl-auto=validate` is the manual integration check that the JPA stack and the
  `DeploySmokeRecord`↔`deploy_smoke_test` mapping are correct.

### Manual Testing Steps:

1. `mvnw.cmd spring-boot:run` against a reachable Postgres → app starts, no Hibernate validation error.
2. Confirm the log shows Hibernate **validating** (not creating/altering) and Flyway resting at V1.
3. Confirm no open-session-in-view warning appears.

## Performance Considerations

Negligible. One `EntityManagerFactory` over the existing 5-connection Hikari pool; `open-in-view=false`
keeps DB sessions short and request-scoped to the transactional boundary. No queries on any hot path in
this foundation.

## Migration Notes

No schema or data migration. V1 stays immutable and the reference entity maps onto its existing table.
`ddl-auto=validate` means a deployed app fails fast if an entity and its Flyway-defined table diverge —
the intended guardrail. When the first domain table lands (S-02), the `DeploySmokeRecord` reference
entity/repository and, eventually, the `deploy_smoke_test` table can be retired.

## References

- Change identity / seed: `context/changes/jpa-persistence-foundation/change.md`
- Roadmap item: `context/foundation/roadmap.md` → F-02 (lines 79-90)
- PRD: FR-021 archive-only (lines 120-122), no-silent-data-loss guardrail (line 44), FR-008/FR-011
  history-viewing (lines 85-94)
- Sibling foundation + house style: `context/changes/access-control-foundation/plan.md`
- Boot-4 autoconfig-FQN precedent (JDBC/Flyway exclusions): commit `2ab8848`,
  `src/test/resources/application.properties:6-8`
- Immutable smoke migration: `src/main/resources/db/migration/V1__init.sql`
- Boot 4.0 JPA autoconfig FQNs:
  [HibernateJpaAutoConfiguration](https://docs.spring.io/spring-boot/4.0-SNAPSHOT/api/java/org/springframework/boot/hibernate/autoconfigure/HibernateJpaAutoConfiguration.html),
  [DataJpaRepositoriesAutoConfiguration](https://docs.spring.io/spring-boot/4.0-SNAPSHOT/api/java/org/springframework/boot/data/jpa/autoconfigure/DataJpaRepositoriesAutoConfiguration.html)
- Downstream consumers: S-02 `portfolio-locations-garages`, S-03 `tenant-management`,
  S-04 `rental-contracts` (all extend these base types)

## Open Risks & Assumptions

- **`archived_at` is unexercised at runtime in this slice.** It is compile-checked and unit-tested as
  object behavior, but no table carries the column until S-02's migration. Assumption: S-02 adds
  `archived_at`/`created_at`/`updated_at` columns matching `ArchivableEntity` and is the first to validate
  the mapping against a real archivable table. Acceptable per the thin-foundation mandate.
- **Audit via lifecycle callbacks, not Spring Data auditing.** No `createdBy`/`modifiedBy`. If a later
  need for principal-aware auditing emerges, `@EnableJpaAuditing` can be introduced once a real EMF is
  always present (it conflicts with the current DB-free test profile).
- **Assumption: the parent-managed JPA starter is `spring-boot-starter-data-jpa` and the autoconfig FQNs
  are the two Boot-4 packages above.** If `mvnw.cmd compile` can't resolve the starter, or the DB-free
  tests fail to load after exclusion, re-check the artifactId/FQNs (empirical check, per the `2ab8848`
  precedent).
- **`deploy_smoke_test.deployed_at` is read-only in the mapping** (DB default owns it). The reference
  entity is used read-only for the foundation; no inserts are performed.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: JPA wiring & base conventions

#### Automated

- [x] 1.1 Project compiles: `mvnw.cmd -q compile` — 149abbb
- [x] 1.2 Context test passes DB-free with JPA: `mvnw.cmd test -Dtest=GarageopsApplicationTests` — 149abbb
- [x] 1.3 F-01 gating test still passes DB-free: `mvnw.cmd test -Dtest=SecurityGatingTests` — 149abbb
- [x] 1.4 Full build passes: `mvnw.cmd verify` — 149abbb

#### Manual

- [x] 1.5 `spring-boot:run` boots clean against real Postgres under `ddl-auto=validate` (no schema-validation error) — 149abbb
- [x] 1.6 Log shows Hibernate validating (not creating/altering) and Flyway resting at V1 — 149abbb
- [x] 1.7 No open-session-in-view warning in the log — 149abbb

### Phase 2: Lock the archive-only convention with a test

#### Automated

- [x] 2.1 New convention test passes: `mvnw.cmd test -Dtest=ArchivableEntityTests`
- [x] 2.2 Existing tests still pass: `GarageopsApplicationTests` + `SecurityGatingTests`
- [x] 2.3 Full build + suite passes: `mvnw.cmd verify`

#### Manual

- [x] 2.4 Reviewer confirms the test exercises real `ArchivableEntity` behavior (archive transition + lifecycle timestamps), DB-free
