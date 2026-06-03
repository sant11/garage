<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Manage Locations & Garages (S-02)

- **Plan**: context/changes/portfolio-locations-garages/plan.md
- **Scope**: Full plan (Phases 1–3 of 3)
- **Date**: 2026-06-03
- **Verdict**: APPROVED (all findings resolved; F4 re-verified & fixed 2026-06-03)
- **Findings**: 0 critical, 2 warnings, 2 observations (F4 re-verified up to WARNING, now FIXED)

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Success criteria were verified in the implementation session (no source changed since commit `3233abc`): `SecurityGatingTests` green (incl. new R5 `/locations` → `/login`), full suite 31/31, `mvnw.cmd package` built the jar. All manual rows confirmed by the user.

## Findings

### F1 — Rename/edit dialogs mutate the live domain entity before persist

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (reliability)
- **Location**: src/main/java/com/example/garageops/locations/LocationsView.java:169-188 (rename), :216-235 (garage edit)
- **Detail**: The edit/rename paths call `binder.setBean(existing)`, so every keystroke writes through into the live entity that also sits in the rendered list — before any persist and outside a transaction. The persisted result is correct (values are trimmed and `refresh()` re-fetches), so this is masked today, but it's the riskiest pattern in the view: the in-memory list briefly holds a half-edited entity, and the trimmed DB value diverges from the bean's untrimmed value. The add paths already avoid this by binding a throwaway bean.
- **Fix**: For edit/rename, don't `setBean(existing)`. Pre-fill the field with the current value, and on save read the field value directly (`name.getValue().trim()`) before calling the service — leaving the list entity untouched until `refresh()` re-fetches.
- **Decision**: FIXED — bound a throwaway copy (`new Location(existing.getName())` / `new Garage(existing.getLocation(), …)`) for edit/rename and read persisted values from that bean; live list entity no longer mutated.

### F2 — Query-per-location render + redundant count on archive confirm

- **Severity**: ◽ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (performance)
- **Location**: src/main/java/com/example/garageops/locations/LocationsView.java:113, :267
- **Detail**: `refresh()` issues one `findByLocationIdAndArchivedAtIsNull` per location (N+1 at location granularity), and `confirmArchiveLocation` counts garages with a query that the service then re-loads a third time. Explicitly acceptable at the PRD's "small" single-owner scale — the plan's Performance Considerations section anticipated exactly this.
- **Fix**: None for this slice. Batch-load garages grouped by location id if scale ever grows.
- **Decision**: FIXED — added `GarageRepository.findByLocationIdInAndArchivedAtIsNull` + `GarageService.listActiveByLocations` (one grouped query); `refresh()` now batch-loads and threads each list into `locationSection`; `confirmArchiveLocation` takes the already-loaded size (removed the redundant count query). Compiles clean.

### F3 — GarageRepository.findByLocationId is planned but currently unused

- **Severity**: ◽ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/java/com/example/garageops/garages/GarageRepository.java:15
- **Detail**: The plan specified `findByLocationId(Long)` "(all, for cascade)", but the cascade uses `findByLocationIdAndArchivedAtIsNull` instead, so this finder has no caller in the change. Planned (not scope creep), just dead until a future history view uses it.
- **Fix**: Keep as a documented future seam, or drop it until a caller exists.
- **Decision**: FIXED (dropped) — removed unused `findByLocationId(Long)` and corrected the now-stale class Javadoc (the cascade pass actually uses the active-only finder, not the all-by-location one).

### F4 — @ManyToOne relies on the EAGER default (re-verified 2026-06-03)

- **Severity**: ⚠️ WARNING (re-verified up from ◽ OBSERVATION)
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architecture / Pattern Consistency
- **Location**: src/main/java/com/example/garageops/garages/Garage.java:32; src/main/java/com/example/garageops/garages/GarageService.java:94
- **Detail**: Re-verified after the `@ManyToOne` hard rule was added to AGENTS.md ("Always declare `@ManyToOne(fetch = FetchType.LAZY)` explicitly… Never rely on the EAGER default… ensure every repository query includes an explicit fetch-join for any association traversed outside the session"). `Garage.java:32` was `@ManyToOne(optional = false)` — relying on the EAGER default → direct rule violation. The original F4 claim that "the view never traverses `garage.getLocation()`" is now FALSE: the F2 fix added `GarageService#listActiveByLocations`, which groups by `g.getLocation().getId()` (GarageService:94) on **detached** entities (service is not `@Transactional`; `open-in-view=false`). It worked only because EAGER pre-loaded `location` — the exact masking F4 warned about, now load-bearing in shipped code.
- **Fix**: Declare `@ManyToOne(fetch = FetchType.LAZY, optional = false)` and add a `JOIN FETCH g.location` `@Query` to `findByLocationIdInAndArchivedAtIsNull` (the only off-session-traversed finder). The single-location finder needs no change — its only off-session consumer, `LocationService.archive`, is `@Transactional` and never dereferences `getLocation()`.
- **Decision**: FIXED — `Garage.location` now `@ManyToOne(fetch = FetchType.LAZY, optional = false)` (+`FetchType` import); `GarageRepository.findByLocationIdInAndArchivedAtIsNull` rewritten as a `JOIN FETCH g.location` `@Query` with `@Param`, Javadoc documents why. `mvnw.cmd package` green (full suite + jar `garageops-0.0.1-SNAPSHOT.jar`, 13:36:51). Now compliant with both clauses of the AGENTS.md rule.
