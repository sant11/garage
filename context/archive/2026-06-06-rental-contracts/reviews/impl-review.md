<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Rental Contracts (S-04)

- **Plan**: context/changes/rental-contracts/plan.md
- **Scope**: Phases 1–3 of 3 (full plan)
- **Date**: 2026-06-11
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

**Success criteria evidence**: `mvnw.cmd verify` exit 0; targeted run of ContractTests (11), ContractServiceTests (14), GarageServiceTests (5), LocationServiceTests (4), TenantServiceTests (6), SecurityGatingTests (7) → 47 tests, 0 failures, BUILD SUCCESS.

## Findings

### F1 — Unrelated tooling files bundled into the Phase 1 commit

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: commit 40dcfc3 (Phase 1)
- **Detail**: The Phase 1 commit "Contract data model" also staged 10 unrelated files: `.claude/.10x-cli-manifest.json`, `.claude/hooks/compile-check.ps1`, `.claude/settings.json`, the six `.claude/skills/10x-e2e/*` files, and `CLAUDE.md`. These are e2e-skill / CLI-tooling changes unrelated to the contract data model. Content is benign (no product code) but widens the commit's blast radius and muddies history. Phases 2 and 3 were clean (only planned files).
- **Fix**: Leave as-is — already committed and harmless; going forward stage explicitly by path (Phase 2/3 ritual already did). No code change warranted.
- **Decision**: ACCEPTED — benign tooling only, no rewrite

### F2 — GarageDetailView uses BeforeEnterObserver, not HasUrlParameter

- **Severity**: ✅ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: GarageDetailView.java:58, 76–94
- **Detail**: Plan named `HasUrlParameter<Long>`; implementation reads the `:id` route param in `beforeEnter` and parses it (NumberFormatException → NotFoundException). The justifying comment ("HasUrlParameter would force a second path segment") was **factually wrong** — `@Route("garages")` + `HasUrlParameter<Long>` resolves `garages/123`, an identical single-segment URL. Vaadin 24 docs explicitly prefer `HasUrlParameter<T>` for a single typed param ("If you can get the job done using the HasUrlParameter<T> interface, use that instead").
- **Decision**: FIXED (Fix differently) — refactored BOTH `GarageDetailView` and `TenantProfileView` to `@Route("<base>")` + `implements HasUrlParameter<Long>`, capturing the id in `setParameter(BeforeEvent, Long)`. The router now parses the `Long` and auto-404s a non-numeric segment, so the hand-rolled `NumberFormatException` handling is gone; the `EntityNotFoundException → NotFoundException` 404 path is preserved. Required-param target registers only `<base>/<id>`, so `TenantsView`'s exact `@Route("tenants")` list still owns the bare path. All navigation is string-based (`navigate("garages/" + id)`) so callers are unaffected. Verified: 65 tests pass, full Vaadin route registry boots with no AmbiguousRoute exception.

### F3 — Extra repository finder: findNonArchivedByGarageIdIn

- **Severity**: ✅ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: ContractRepository.java:47–53, used at LocationService.java:81
- **Detail**: A seventh finder beyond the plan's enumerated six — the batch query the location cascade needs to stamp all garages' contracts in one round-trip instead of per-garage. Realizes the plan's "also stamp each garage's contracts" intent as a batch. Justified.
- **Decision**: ACCEPTED — batch query avoids N+1 in the cascade

### F4 — No indexes on contracts FK / filter columns

- **Severity**: ✅ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Performance)
- **Location**: V6__contracts.sql
- **Detail**: Every view finder and the cascade filter on `tenant_id` / `garage_id` / `ended_on` / `archived_at`, but V6 adds no indexes. For a single-owner app row counts are tiny, so not a defect — noted in case the dataset grows.
- **Decision**: ACCEPTED — single-owner scale; indexes premature (revisit via a forward V7 migration if data grows)

### F5 — create() accepts a fully-past planned window

- **Severity**: ✅ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: ContractService.java (create validation)
- **Detail**: `create()` validates `plannedEnd ≥ start` and `paymentDay` 1–28 but does not reject a window entirely in the past. For single-owner software where back-dating a historical contract is plausibly desired, this is likely intentional and the plan never forbade it. Worth a one-line confirm against the PRD, not a defect.
- **Decision**: ACCEPTED — back-dating historical contracts is a valid owner action; intentional
