# JPA Persistence + Archive-Only Foundation (F-02) — Plan Brief

> Full plan: `context/changes/jpa-persistence-foundation/plan.md`

## What & Why

Swap the scaffolded `spring-boot-starter-jdbc` for `spring-boot-starter-data-jpa` and establish the
persistence conventions every entity-backed slice inherits — a base entity, a Spring Data repository
pattern, and the FR-021 **archive-only** rule — so S-02/S-03/S-04 build on one tested foundation instead
of each reinventing persistence. Doing it once is the whole point (PRD FR-021 + the no-silent-data-loss
guardrail).

## Starting Point

The app boots on a JDBC scaffold: Postgres + Flyway + a tuned HikariCP pool are wired, but there are zero
entities, zero repositories, and no JPA. The only migration (`V1__init.sql`) is a `deploy_smoke_test`
table already applied in the deployed DB. Tests run deliberately DB-free by excluding DataSource/Flyway
autoconfig. Sibling foundation F-01 (Spring Security) is merged and set the house style.

## Desired End State

The app runs on JPA with Flyway still owning the schema (`ddl-auto=validate`). A
`com.example.garageops.persistence` package ships `BaseEntity` (id) and `ArchivableEntity` (`archived_at`
state + audit timestamps), proven by a reference entity/repository mapped onto the existing smoke table.
The automated suite stays DB-free and green; a unit test locks the archive-only behavior. Domain entities
remain unbuilt — they belong to the slices.

## Key Decisions Made

| Decision                       | Choice                                            | Why (1 sentence)                                                                 | Source           |
| ------------------------------ | ------------------------------------------------- | -------------------------------------------------------------------------------- | ---------------- |
| Foundation depth               | Swap + reference entity on smoke table + test     | Proves the full JPA stack and gives slices a concrete pattern without new schema | Plan             |
| Archive-only mechanism         | Explicit `archived_at` state (not `@SoftDelete`)  | Archived rows must stay queryable for FR-008/FR-011 history views                | Plan (PRD-driven)|
| Base type structure            | Two layers: `BaseEntity` + `ArchivableEntity`     | Separates "has an id" from "is archivable"; resolves the smoke-table/validate tension | Plan         |
| ID generation                  | `IDENTITY` (BIGSERIAL)                             | Matches the one existing table; validates cleanly; simplest                      | Plan             |
| `ddl-auto`                     | `validate`                                         | Fail-fast on entity/migration drift; reinforces "Flyway owns schema"             | Plan             |
| Audit timestamps               | `@PrePersist`/`@PreUpdate` callbacks (no auditing) | Full audit with zero config and no EMF-at-startup conflict with the DB-free test | Plan             |
| Test strategy                  | DB-free context-load + POJO unit test             | No Docker/Testcontainers; mapping proven manually against real Postgres          | Plan             |
| V1 smoke migration             | Keep immutable; map reference entity onto it       | `validate-on-migrate=true` — editing an applied migration breaks checksums       | Plan             |

## Scope

**In scope:** starter swap (jdbc→data-jpa); `ddl-auto=validate` + `open-in-view=false`; `BaseEntity` +
`ArchivableEntity`; reference entity/repository on `deploy_smoke_test`; test-profile JPA autoconfig
exclusions; archive-convention unit test.

**Out of scope:** domain entities/tables; new Flyway migrations; `@SoftDelete`; `@EnableJpaAuditing`;
Testcontainers/H2/live-DB automated tests; repository business methods.

## Architecture / Approach

New package `com.example.garageops.persistence`: `BaseEntity` (`@MappedSuperclass`, `Long id` IDENTITY) →
`ArchivableEntity` (`@MappedSuperclass`, adds nullable `archived_at` + `created_at`/`updated_at` via
lifecycle callbacks + `archive()`/`isArchived()`). `DeploySmokeRecord` extends `BaseEntity` and maps the
existing smoke table; `DeploySmokeRecordRepository extends JpaRepository`. `data-jpa` transitively keeps
JDBC + Hikari, so the datasource config is untouched. Flyway stays the schema authority; Hibernate only
validates.

## Phases at a Glance

| Phase                                   | What it delivers                                                       | Key risk                                                                 |
| --------------------------------------- | ---------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| 1. JPA wiring & base conventions        | Starter swap, JPA config, base types, reference entity/repo, test-profile exclusions | EMF tries to autoconfigure in DB-free tests; fixed via the two Boot-4 JPA exclusion FQNs |
| 2. Lock the archive-only convention     | DB-free unit test of archive state transition + audit-timestamp callbacks | Test must exercise real `ArchivableEntity` behavior, not a mock          |

**Prerequisites:** none for code (parallel with F-01, already merged). Manual verification needs a
reachable Postgres with V1 applied.
**Estimated effort:** ~1 session across 2 phases (thin foundation).

## Open Risks & Assumptions

- `archived_at` is compile-checked + unit-tested here but first validated against a real column in S-02.
- Audit via lifecycle callbacks means no `createdBy`/`modifiedBy` (fine for a single-owner tool).
- Assumes the starter is `spring-boot-starter-data-jpa` and the two Boot-4 JPA autoconfig FQNs are correct;
  empirically confirmed by the DB-free tests staying green (per the `2ab8848` precedent).

## Success Criteria (Summary)

- `mvnw.cmd verify` passes; `GarageopsApplicationTests` and `SecurityGatingTests` still run **DB-free**.
- `mvnw.cmd spring-boot:run` boots clean against real Postgres under `ddl-auto=validate` (no schema
  mismatch) — the proof the JPA mapping is correct.
- The archive-only convention (`archive()`/`isArchived()`/`archived_at` + audit timestamps) is locked by a
  passing unit test that slices inherit.
