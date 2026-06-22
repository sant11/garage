<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Payments & Overdue (S-05)

- **Plan**: context/changes/payments-overdue/plan.md
- **Scope**: Full plan (Phases 1–4 of 4)
- **Date**: 2026-06-16
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 2 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — Upcoming (future-start) contracts are flagged overdue

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Safety & Quality (reliability / correctness)
- **Location**: contracts/ContractRepository.java:40-42; payments/OverdueService.java:67-85; payments/OverdueRule.java:69-75
- **Detail**: `findActiveForOverdue` selects `where c.endedOn is null and c.archivedAt is null` — it does NOT exclude contracts whose `startDate` is in the future. `OverdueRule.latestFullyDuePeriod` walks months backward from `asOf` with no lower bound at the contract's start, so an Upcoming contract resolves a period before it began and is reported overdue with a fabricated `daysOverdue`. The Dues view (US-01) then lists a garage whose tenant owes nothing yet. `ContractService.rentedGarageIds` already guards this exact case with `isActiveOn(today)`; the overdue scan diverged from that precedent. Existing tests don't catch it (fixtures use already-started contracts).
- **Fix A ⭐ Recommended**: Guard at the source — restrict the overdue scan to contracts active as-of the evaluation date (add `and c.startDate <= :asOfDate` to `findActiveForOverdue` passing the clock-zone date, or filter the returned list through `isActiveOn(asOfDate)` in `duesAsOf`). Keeps `OverdueRule` pure.
  - Strength: One change point; mirrors the established `rentedGarageIds`/`isActiveOn` precedent.
  - Tradeoff: `findActiveForOverdue` gains an as-of parameter (or `duesAsOf` post-filters); S-07 path must pass the same date.
  - Confidence: HIGH — bug and precedent-based fix both verified in the diff.
  - Blind spot: Whether expired-but-never-ended contracts (plannedEnd passed, endedOn null) should still surface unpaid past rent — a product call, separate from the clear future-start bug.
- **Fix B**: Clamp the resolved period to the contract term inside the rule.
  - Strength: Fixes both future-start and any past-term drift in one place.
  - Tradeoff: Pushes start/end dates into the pure rule's signature, coupling it to lifecycle facts the plan kept out of `OverdueRule`.
  - Confidence: MED — larger surface than Fix A.
  - Blind spot: Re-derivation semantics for S-07 across term edges.
- **Decision**: FIXED via Fix A (future-start guard only; expired-unended left as the deferred product call)

### F2 — grace_days has no construction/edit path (locked at default 5)

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Adherence
- **Location**: contracts/Contract.java:84-92 (constructor); contracts/ContractService.java:59 (create signature)
- **Detail**: Phase 2 said "Surface it through the contract's existing construction/edit path consistent with how `paymentDayOfMonth` is handled." In practice `graceDays` is only an entity/DB default (5) — not a constructor parameter, not in `ContractService.create`, not in the new-contract dialog. Every contract is locked to 5; the per-contract grace FR-013 calls for can't be set. The entity Javadoc documents this as intentional ("not yet owner-editable"), and Phase 4 added no grace field either, so it's a coherent deferral — but under the plan's literal wording.
- **Fix A ⭐ Recommended**: Accept the default-only behavior as a documented deferral (no code change); note grace-days editing as follow-up.
  - Strength: Matches what Phase 4 shipped and the entity's documented intent; boundary tests assume grace 5; no churn to a green build.
  - Tradeoff: FR-013 "per-contract grace" stays latent until a future change wires the field.
  - Confidence: HIGH — behavior consistent end-to-end at 5.
  - Blind spot: Whether the owner needs non-default grace before S-06/S-07.
- **Fix B**: Thread `graceDays` through `create` + the new-contract dialog now.
  - Strength: Fully delivers the Phase 2 wording; FR-013 per-contract grace becomes real.
  - Tradeoff: Touches constructor, service signature, dialog, and likely `ContractServiceTests` — scope beyond this plan's committed phases.
  - Confidence: MED — straightforward but widens scope post-completion.
  - Blind spot: Validation/UX for the new field (default prefilled to 5).
- **Decision**: ACCEPTED via Fix A (default-only grace accepted as documented deferral; grace-days editing queued as follow-up)

### F3 — amountDue scale not normalized for display

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (reliability)
- **Location**: payments/OverdueRule.java:49; payments/DuesView.java:62
- **Detail**: `amountDue = monthlyRent.subtract(paid).max(ZERO)` with no `setScale(2)`. In practice both operands are NUMERIC(10,2)→scale-2, so the result is scale-2 and `toPlainString()` renders "150.00" correctly — no rounding loss (the `overdue` flag uses `signum()`, scale-independent). Worth a defensive `setScale(2, HALF_UP)` only if aggregate return-scale ever drifts.
- **Fix**: Optionally normalize amountDue to scale 2 in the rule.
- **Decision**: FIXED (added `.setScale(2, RoundingMode.HALF_UP)` to amountDue in OverdueRule.java:50)

### F4 — payments.amount has no DB CHECK(amount > 0) backstop

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (data safety)
- **Location**: src/main/resources/db/migration/V7__payments.sql
- **Detail**: `Payment.amount` carries `@Positive` (enforced on persist by bean validation) and `PaymentService.record` rejects non-positive amounts, but the table has no `CHECK (amount > 0)`. Consistent with the repo's existing tables (none use CHECK constraints), so not a regression.
- **Fix**: Optionally add a CHECK constraint in a future migration.
- **Decision**: SKIPPED (defended at app layer via @Positive + PaymentService.record; consistent with repo convention — no tables use CHECK constraints)

## Confirmed strong (no action)

Batch `SUM…GROUP BY` runs once per distinct period (no N+1); off-session associations are JOIN FETCH'd (per-tenant payments, `findActiveForOverdue`); all "now" resolves through the injected `Clock`; V7 matches entities under `ddl-auto=validate`; ObjectProvider + per-call-helper, `@Transactional`-on-writes-only, explicit `@ManyToOne(LAZY)`, `@PermitAll` routing, and tab indentation all match siblings. Success Criteria: `mvnw.cmd verify` → BUILD SUCCESS, 89 tests, 0 failures (this session, final code); manual walkthrough confirmed.
