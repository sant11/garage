<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Action Dashboard (S-06)

- **Plan**: context/changes/action-dashboard/plan.md
- **Scope**: Phases 1–2 of 2 (full plan)
- **Date**: 2026-06-25
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Verification Run

- ✅ `mvnw.cmd test -Dtest=DashboardServiceTests` — Tests run: 7, Failures: 0, Errors: 0 (BUILD SUCCESS)
- ✅ grep `@ManyToOne` without `fetch = FetchType.LAZY` across `src/main/java` — no matches
- ✅ grep `HomeView` across `src/` — no matches (clean deletion, no dangling references)

All 8 planned items verified MATCH (no DRIFT / MISSING / scope creep). Vacancy-since
is genuinely batched via a single `findLastEndedByGarageIdIn` (no per-garage loop).
All off-session-rendered reads are `JOIN FETCH`-backed — no `LazyInitializationException`
risk under `open-in-view=false`. Empty-list `in :garageIds` is never reached due to
guarded early-returns in `DashboardService.vacantGarages()`.

## Findings

### F1 — Ending-soon boundary exclusions not covered by an automated test

- **Severity**: ◽ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/com/example/garageops/dashboard/DashboardServiceTests.java:131-148
- **Detail**: Testing Strategy (plan:177) lists "ending-soon includes the 30-day boundary and excludes day-31 and already-ended contracts" as a DashboardServiceTests case. The actual test mocks `findEndingBetween` and only asserts the service requests the `[today, today+30]` window. The boundary/exclusion logic lives in JPQL — it cannot be unit-tested in the DB-free (Mockito) context, and the test file documents this, deferring it to manual verification (Progress 1.6, checked manually). Functionally consistent with the repo's DB-free test design; the only mismatch is the plan's wording vs. where the logic is actually exercised.
- **Fix**: None required. Optionally add a thin `@DataJpaTest` slice to assert `findEndingBetween`'s predicate against a real DB if this boundary is considered regression-prone — but that introduces a test style the repo doesn't yet use.
- **Decision**: SKIPPED

### F2 — Two unplanned files touched (comment-only HomeView cleanup)

- **Severity**: ◽ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: src/main/java/com/example/garageops/locations/LocationsView.java, src/main/java/com/example/garageops/tenants/TenantProfileView.java (Javadoc only)
- **Detail**: Neither file is in the plan's Changes Required, but both edits are Javadoc-comment-only, replacing stale references to the deleted `HomeView` ("mirrors HomeView" → "mirrors the sibling views"). These are effectively required to satisfy criterion 2.3 ("no dangling references to HomeView") and carry no behavioral change.
- **Fix**: None — accept as a necessary side-effect of the HomeView deletion.
- **Decision**: SKIPPED
