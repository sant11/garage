<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: S-03 Tenant Management

- **Plan**: context/changes/tenant-management/plan.md
- **Scope**: Full plan (Phases 1–3 of 3)
- **Date**: 2026-06-06
- **Verdict**: APPROVED (with 2 minor warnings, both fixed in triage)
- **Findings**: 0 critical, 2 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Plan adherence: 9/9 planned changes MATCH (no DRIFT/MISSING/EXTRA). All five
"What We're NOT Doing" guardrails held (no Contract entity / @OneToMany, no
latePayer field or placeholder badge, no structured contact validation, no
archived-tenant contract guard, no real-DB retention test). Archive is a
verified flag-flip UPDATE with the R4 no-delete oracle. Automated checks green:
`mvnw.cmd test` (39 after triage), `SecurityGatingTests` 6. Manual checks
3.3–3.7 confirmed by the owner.

## Findings

### F1 — Profile route catch is narrower than the 404 guarantee implies

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality (Reliability)
- **Location**: src/main/java/com/example/garageops/tenants/TenantProfileView.java:55-61
- **Detail**: setParameter caught only EntityNotFoundException. A null/blank :id segment would flow into findActive(null) → findById(null), throwing IllegalArgumentException (→500) instead of the intended 404. Unknown/archived/non-numeric cases were already handled.
- **Fix**: Widen the catch to `catch (EntityNotFoundException | IllegalArgumentException e)` so a null/blank id also yields NotFoundException (404).
- **Decision**: FIXED (Fix now) — catch widened to include IllegalArgumentException, comment updated.

### F2 — findActive() has no unit test despite being load-bearing for 404

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Test coverage)
- **Location**: src/test/java/com/example/garageops/tenants/TenantServiceTests.java
- **Detail**: findActive(Long) — the method the profile route relies on to produce a 404 — was untested on both branches (returns active tenant; throws EntityNotFoundException when archived). The archived-throws branch guarantees an archived tenant's profile 404s.
- **Fix**: Add two mocked-repo tests — findActive returns the tenant when active; findActive throws EntityNotFoundException when isArchived().
- **Decision**: FIXED (Fix now) — added findActiveReturnsTheTenantWhenItIsActive and findActiveThrowsWhenTheTenantIsArchived. Suite now 39 green.

### F3 — Grid rebuilt on every refresh() rather than re-setItems()

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/com/example/garageops/tenants/TenantsView.java:74-95
- **Detail**: refresh() removes content and constructs a new Grid<Tenant> each call to swap between grid and empty-state. Functionally correct, within the single-refresh-hook intent, and LocationsView does the same section-rebuild.
- **Decision**: SKIPPED — accepted as-is; mirrors LocationsView, not a defect.
