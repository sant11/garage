# Overdue Cumulative Balance — Plan Brief

> Full plan: `context/changes/overdue-cumulative-balance/plan.md`

## What & Why

The overdue engine attributes payments to months by **payment date**: a June rent paid on
July 4 lands in July's calendar window, so June reads as unpaid forever. The reported case —
contract from 2026-06-01 at 300/month, 600 paid by 2026-07-05 — shows 300 overdue / 29 days on
the dashboard despite the tenant being fully paid up. The inverse bug also exists: a genuinely
skipped month silently drops off Dues once the next month is paid within its own window. We
replace the per-month windows with a cumulative balance: overdue = periods due since contract
start × rent − everything ever paid.

## Starting Point

Three layers exist and stay: pure `OverdueRule` (calendar math + verdict), `OverdueService`
(portfolio scan feeding Dues/Dashboard), `LatePayerService` (re-derives past periods for the
tenant late-payer flag). All are covered by DB-free mock tests pinned to a fixed clock. `Payment`
has no period column — attribution is derived, and remains so.

## Desired End State

A tenant who paid everything due so far never appears overdue, regardless of *when* they paid.
A skipped month keeps the contract on Dues until the balance is settled — and so does an
**ended contract with an unsettled balance**: it stays listed (accrual frozen at `endedOn`)
until the debt is paid off or the contract is archived (write-off). The late-payer flag still
counts a late-but-eventually-paid month as one late event, so habitual lateness stays visible
even without current arrears. The reproduction scenario is locked as a regression test at rule
and service level.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| -------- | ------ | ---------------- |
| Core semantics | Cumulative balance (periods due × rent − total paid) | Fixes both false-overdue and missed-overdue with no schema change (settled in change.md) |
| `daysOverdue` anchor | Due date of the oldest not-fully-covered period (FIFO) | Shows how long the tenant has actually been behind — the number the owner chases with |
| Late-payer event | Balance negative the day after a period's due date | Measures habitual lateness; paying late-then-catching-up still counts as late |
| Ended contracts with debt | Stay on Dues until fully settled (user decision) | Real debt must not vanish just because the contract ended; accrual freezes at `endedOn`, archive = write-off |
| Payments in the sum | All non-archived payments of the contract, no date filter | Prepayments and post-hoc settlements naturally reduce the balance |

*(The ended-contracts row is the user's explicit decision; the other non-core decisions were
adopted from the recommended options while the user was AFK — revisit before implementation if
any feels wrong.)*

## Scope

**In scope:** `OverdueRule` (cumulative evaluation, FIFO days), `OverdueService` (single
un-windowed total-paid aggregation; scan widened to non-archived so ended-but-unsettled
contracts stay listed), `LatePayerService` (cumulative-through-due-date sums), two new
`PaymentRepository` aggregations (two windowed ones retired), the `ContractRepository` scan
finder, full rewrite of the three test classes, javadoc updates on `OverdueResult`/`OverdueRow`.

**Out of scope:** schema changes / per-payment period allocation, an "ended" badge on Dues rows,
other UI changes, late-payer window/threshold config changes; archived contracts stay excluded
from the live scan (archive = write-off).

## Architecture / Approach

Same three-layer shape; only the meaning of the paid-sum changes. The rule gains the contract's
term bounds and counts owed periods itself (first owed period = earliest month whose due date is
on/after contract start — the predicate both services already use). Live path: one
`SUM ... GROUP BY contract` for the whole portfolio (simpler than today's per-period grouping).
Late-payer path: sums batched per distinct due-date boundary, judged at `due + 1 day` as today.
## Phases at a Glance

| Phase | What it delivers | Key risk |
| ----- | ---------------- | -------- |
| 1. Cumulative rule | Pure rule rewrite + full `OverdueRuleTests` incl. reproduction regression | First-owed-period boundary math (day-28 + grace spills into next month) |
| 2. Live overdue path | Dashboard/Dues on cumulative balance; ended-with-debt contracts now listed | Widened scan surfacing old ended debts the owner forgot about |
| 3. Late-payer flag | Cumulative event semantics + ledger-based tests | Intended cascade (persistent arrears → event per period) surprising the owner |

**Prerequisites:** JDK 21 on `JAVA_HOME`; app runnable for phase 2–3 manual checks.
**Estimated effort:** ~2–3 sessions, one per phase.

## Open Risks & Assumptions

- Three design decisions were auto-adopted from recommendations (marked above) — confirm before
  or during phase 1.
- Late-payer counts will rise after rollout (late-but-paid months and cascades now count), and
  ended contracts with old unsettled balances will newly appear on Dues; worth a heads-up to
  the owner.
- Assumes rent never changes mid-contract — verified: contracts are immutable after creation.

## Success Criteria (Summary)

- The reported scenario (600 paid by 2026-07-05 on a June-start 300/month contract) shows **no**
  overdue on Dashboard/Dues, and 1 (unflagged) late event on the tenant profile.
- A skipped month keeps the contract overdue until settled, even when later months are paid.
- An ended contract with debt stays on Dues/Dashboard until the balance is settled or the
  contract is archived.
- `mvnw.cmd verify` green with the rewritten suites.
