<!-- PLAN-REVIEW-REPORT -->
# Plan Review: JPA Persistence + Archive-Only Foundation (F-02)

- **Plan**: context/changes/jpa-persistence-foundation/plan.md
- **Mode**: Deep
- **Date**: 2026-05-27
- **Verdict**: SOUND
- **Findings**: 0 critical, 0 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | PASS |
| Plan Completeness | PASS |

## Grounding

5/5 paths ✓ (pom.xml, src/main/resources/application.properties,
src/test/resources/application.properties, V1__init.sql, persistence package location).
Symbols ✓ — `spring-boot-starter-jdbc` at pom.xml:47; V1 columns match the plan exactly
(`id BIGSERIAL PK`, `deployed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `note TEXT NOT NULL`);
test-profile `spring.autoconfigure.exclude` at lines 6-8 (DataSource + Flyway). brief↔plan ✓.

Boot-4 JPA autoconfig FQNs (`org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration`,
`org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration`) are
**unverifiable from local jars** — `.m2` holds `spring-boot-starter-data-jpa` only through 3.5.11
(those legacy versions use the old monolithic `org.springframework.boot.autoconfigure.orm.jpa.*`
package; Boot 4.0.6 not yet resolved). The plan correctly treats them as empirical-check
assumptions backed by the identical per-module pattern proven in commit `2ab8848`
("Fix test autoconfig exclusion FQNs for Spring Boot 4"). Not a finding.

Progress↔Phase mechanical check: well-formed — exactly one `## Progress` heading, both phases
matched (`### Phase 1`/`### Phase 2`), all 11 success-criteria bullets mapped to `- [ ]` items
1.1–2.4, no stray checkboxes in phase bodies.

## Findings

### F1 — Slices get column names but not column TYPE for the audit fields

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 1 §4 (ArchivableEntity contract) + Migration Notes
- **Detail**: ArchivableEntity maps `archivedAt`/`createdAt`/`updatedAt` as `Instant`, which
  Hibernate 6 maps to TIMESTAMP WITH TIME ZONE. The plan tells slices to add
  `archived_at`/`created_at`/`updated_at` columns to their own Flyway migrations but never
  specifies they must be `timestamptz`. A slice author who writes `timestamp` (without tz) passes
  code review and only hits the mismatch when `ddl-auto=validate` fails at S-02 boot. The plan's
  own V1 mapping is correct (`deployed_at TIMESTAMPTZ` ↔ `Instant`) — this is purely about the
  guidance handed downstream.
- **Fix**: Add one clause to the ArchivableEntity Javadoc contract — "slice migrations must declare
  these as `timestamptz` to match the Instant mapping under ddl-auto=validate."
- **Decision**: FIXED — added the `timestamptz` clause to the Phase 1 §4 ArchivableEntity Javadoc contract.

### F2 — "Proves the full JPA stack" overstates what's actually exercised

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: End-State Alignment
- **Location**: Desired End State #4; Open Risks ("used read-only ... no inserts")
- **Detail**: `DeploySmokeRecordRepository` is never called — not in the DB-free automated suite,
  and the manual boot only proves `ddl-auto=validate` accepts the mapping (EMF builds, columns
  match). No query/find/save ever runs. So the proof is "mapping validates + context wires," not
  "the full stack including Spring Data query execution." Acceptable for a thin foundation, but the
  end-state wording promises more than the verification delivers.
- **Fix**: Either soften the claim to "proves the mapping validates against a real table under
  ddl-auto=validate," or accept as-is knowing the repo is a convention exemplar, not an exercised
  component.
- **Decision**: SKIPPED

### F3 — `persistence` package vs the package-by-feature rule

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architectural Fitness
- **Location**: Phase 1 §3–6 (new com.example.garageops.persistence package)
- **Detail**: AGENTS.md mandates package-by-feature, "not by layer." BaseEntity and
  ArchivableEntity are genuinely cross-cutting shared-kernel types with no feature home, so a
  `persistence` package is the right call. The softer spot is `DeploySmokeRecord` — a concrete
  (throwaway) entity in the shared package leans layer-ish. It is explicitly temporary and deleted
  in S-02, so this is a conscious-accept, not a redesign.
- **Fix**: Accept as-is. Optionally note in the package that it holds shared persistence base types
  plus one temporary reference entity, so the intent (shared kernel, not a layer dumping ground)
  stays clear.
- **Decision**: FIXED — added a shared-kernel rationale note to the Implementation Approach (persistence package is a deliberate exception to package-by-feature; DeploySmokeRecord is a temporary tenant retired in S-02).
