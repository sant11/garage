<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Frequent-Late-Payer Flag (FR-020 / S-07)

- **Plan**: context/changes/late-payer-flag/plan.md
- **Scope**: Full plan (Phase 1 + 2 of 2)
- **Date**: 2026-06-25
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS (1 observation) |
| Pattern Consistency | PASS (2 observations) |
| Success Criteria | PASS |

All 6 planned items plus the ClockConfig `@EnableConfigurationProperties` registration verified MATCH — no drift, no missing work, no scope creep. Automated criteria all pass (`mvnw test`: 107 tests, 0 failures; `LatePayerServiceTests` 10/10; app boots clean). Manual checks 2.4–2.9 owner-confirmed 2026-06-25.

## Findings

### F1 — Latent lazy-fetch constraint on the contract finder

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architecture
- **Location**: LatePayerService.java:76 (uses ContractRepository.findByTenantIdOrderByStartDateDesc)
- **Detail**: `evaluate()` runs off-session (called from `TenantProfileView.render`, `spring.jpa.open-in-view=false`, not `@Transactional`). It reads only scalar Contract fields, so there is NO present LazyInitializationException bug. But that finder fetch-joins `c.garage` and NOT `c.tenant` — if a future edit adds `contract.getTenant()`/`getGarage()` to the counting path, it will throw at runtime. The AGENTS.md fetch-join rule is satisfied as written; this is future-proofing only.
- **Fix**: Optionally add a one-line comment at the call site noting that tenant/garage traversal here would need a JOIN FETCH. No code change required.
- **Decision**: FIXED — added a 4-line guard comment at LatePayerService.java:76 documenting the off-session scalar-only read and the c.tenant fetch-join trap.

### F2 — Badge built inline rather than via a statusBadge-style helper

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: TenantProfileView.java:100-106
- **Detail**: The flag `Span` (theme `"badge error"` + title tooltip via `setProperty`) correctly follows the `LocationsView` problem-badge precedent, but is inlined in `render()` rather than extracted like the existing `statusBadge(...)` helper. For a single conditional span this is reasonable; noted only for consistency.
- **Fix**: Leave as-is, or extract a small `latePayerBadge()` helper to mirror `statusBadge`.
- **Decision**: FIXED — extracted `latePayerBadge(LatePayerFlag)` next to `statusBadge` in TenantProfileView.java; `render()` now calls the helper. Tests 10/10 green.

### F3 — Tooltip wording slightly loose

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: TenantProfileView.java (tooltip text)
- **Detail**: Tooltip reads `"<n> overdue events in the last <windowMonths> months"`. `windowMonths` is the look-back window size, which is exactly the intended meaning, but "in the last N months" could be read as "N months that had events". Acceptable for a tooltip.
- **Fix**: Optionally reword, e.g. `"<n> overdue events in the last <windowMonths>-month window"`. Cosmetic.
- **Decision**: SKIPPED — wording acceptable for a tooltip; left as-is.
