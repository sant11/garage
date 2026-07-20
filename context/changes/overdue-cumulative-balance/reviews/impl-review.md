<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Overdue Cumulative Balance

- **Plan**: context/changes/overdue-cumulative-balance/plan.md
- **Scope**: Full plan (Phases 1–3 of 3)
- **Date**: 2026-07-05
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS — 12/12 planned items MATCH (commits e73ce4d, 752518e, dcf0608); zero drift, zero missing |
| Scope Discipline | PASS — all 6 "What We're NOT Doing" guardrails held; no unplanned changes |
| Safety & Quality | PASS — no criticals; FIFO math, date-boundary consistency, nullability, JPQL binding all verified clean |
| Architecture | WARNING — 1 finding (F1) |
| Pattern Consistency | PASS — matches sibling services/repositories/tests on every checked axis |
| Success Criteria | PASS — fresh `mvnw.cmd verify`: 122/122 tests, BUILD SUCCESS (OverdueRuleTests 14, OverdueServiceTests 13, LatePayerServiceTests 12); all manual steps owner-confirmed |

## Findings

### F1 — Undocumented effectiveEnd divergence between live and late-payer paths

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architecture
- **Location**: src/main/java/com/example/garageops/payments/OverdueService.java:90 / src/main/java/com/example/garageops/payments/LatePayerService.java:98
- **Detail**: OverdueService passes `effectiveEnd = endedOn` (null = unbounded), so a holdover contract past `plannedEndDate` keeps accruing on Dues. LatePayerService caps at `endedOn ?? plannedEndDate`, so those holdover months never generate new late events. Both behaviors are plan-sanctioned (live path explicitly not bounded by plannedEndDate; the late-payer in-term guard explicitly kept), but neither javadoc acknowledges the other's choice — a future reader will read the asymmetry as a bug.
- **Fix**: One javadoc sentence in each service naming the intended divergence (Dues = real occupancy debt accrues until actually ended; flag = late events only within the agreed term).
- **Decision**: PENDING

### F2 — graceDays has no upper bound; rule loops scale with it

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/garageops/contracts/Contract.java:74 (surfaced by OverdueRule.java:94-120)
- **Detail**: The month-stepping loops in OverdueRule iterate ~graceDays/30 extra times per call; `graceDays` has `@Min(0)` but no `@Max`. Today it is a fixed default (5) with no edit path, so there is no live risk — this is a latent guard for when the field becomes editable.
- **Fix**: Add `@Max` (e.g. 90) to `Contract.graceDays`.
- **Decision**: PENDING

### F3 — Rule-level cap-exit path has no direct test

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/garageops/payments/OverdueRule.java:62-64
- **Detail**: The effectiveEnd-before-first-owed-period case (capping loop exits via `!last.isBefore(first)` → periodsDue = 0) is verified terminating by analysis, and both services filter it out before the rule (pre-filter / in-term guard), but no OverdueRuleTests case pins it directly at rule level.
- **Fix**: Add one rule test: effectiveEnd before the first owed period's due date → not overdue, amountDue 0.
- **Decision**: PENDING

### F4 — Manual step 3.3 verified in adapted form

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: context/changes/overdue-cumulative-balance/plan.md (Progress 3.3) / src/main/java/com/example/garageops/tenants/TenantProfileView.java:97
- **Detail**: The step as worded ("profile shows counter at 1 event") is unverifiable in the GUI — the counter renders only as the badge tooltip once flagged (≥2 events); below the threshold the profile deliberately renders nothing. Verified in adapted form: the owner observed no badge (= not flagged) for the reproduction tenant, and the count of 1 is locked by the unit test `aLateButEventuallyPaidMonthCountsAsOneEvent`. Accepted during the phase gate; recorded for the audit trail.
- **Fix**: None required. Optionally open a follow-up change (/10x-new) if an always-visible late-event counter in the tenant profile is wanted.
- **Decision**: PENDING

## Evidence notes

- Drift agent: full MATCH across all 12 planned items; extra retained tests (`treatsTheDueDateItselfAsNotYetOverdue`, `duesAsOfHonorsAnExplicitInstantForTheReDerivationSeam`, `aTenantWithNoContractsIsNotFlaggedAndQueriesNoPayments`) are pre-existing scenarios kept within "keep spirit of existing scenarios". One plan-wording inaccuracy (plan said "both overloads public" for `latestFullyDuePeriod`; the LocalDate overload was private before and after — code faithful to actual baseline, no drift).
- Safety agent verified clean: FIFO anchor math for partial payments (anchor never overruns `last`, daysOverdue ≥ 1 when overdue), division-by-zero structurally unreachable, due-date/day-after boundary consistent across OverdueRule and LatePayerService, nullability sound (`plannedEndDate` @NotNull, null paid sums handled), zone-explicit date math, parameterized JPQL only, no schema impact under `ddl-auto=validate`.
