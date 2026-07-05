# Overdue Cumulative Balance Implementation Plan

## Overview

Replace the overdue engine's "paid within the period's calendar month" semantics with a
cumulative balance: a contract is overdue when `(periods due since contract start) × monthlyRent`
exceeds the total it has ever paid. This fixes both directions of the payment-date-window bug —
a rent paid after its month ends currently never clears that month (false overdue), and a
genuinely skipped month silently drops off the dashboard once the next month is paid within its
own window (missed overdue). The same semantics are applied to the late-payer flag so a period
counts as a late event when the cumulative balance was negative the day after that period's due
date.

## Current State Analysis

- `OverdueRule` (`src/main/java/com/example/garageops/payments/OverdueRule.java`) is a pure unit
  that resolves the single "latest fully-due period" and compares `monthlyRent` against a
  paid-sum the caller aggregated for that period's calendar month.
- `OverdueService` (`OverdueService.java:68`) scans `findActiveForOverdue()` (non-ended,
  non-archived), pre-filters with `inEffectOnDueDate` (contract owes the resolved period only if
  `startDate ≤ dueDate` — commit b7ce5f5), groups contracts by resolved period, and runs one
  `sumPaidByContractIdInPeriod` per distinct period (`PaymentRepository.java:50` — bounded to
  `[period.atDay(1), period.atEndOfMonth()]`, excludes archived).
- `LatePayerService` (`LatePayerService.java:70`) re-runs the rule per (contract, period) over
  the last `windowMonths` periods, with the in-term guard `startDate ≤ due ≤ effectiveEnd`
  (`effectiveEnd = endedOn ?? plannedEndDate`), using the include-archived per-month aggregation
  (`sumPaidByContractIdInPeriodIncludingArchived`, `PaymentRepository.java:66`).
- `Payment` (`Payment.java:28-30`) has **no period attribution** — "period membership is by
  `date` alone". This is the root cause: a June rent paid on July 4 lands in July's window and
  June reads as unpaid.
- Reproduction (from `change.md`): contract 2026-06-01 → 2027-06-01, 300/month, payment day 1,
  grace 5 (entity default, `Contract.java:75`); payments 300 on 2026-07-04 and 2026-07-05. As of
  2026-07-05 the resolved period is June (July's due date 2026-07-06 hasn't passed), June's
  window sums to 0 → dashboard shows 300 overdue / 29 days despite 600 paid against 300 due.
- Consumers `DuesView` and `DashboardView` render only `amountDue` and `daysOverdue` off
  `OverdueRow` — no view change needed.
- Contracts are immutable after creation (no rent-edit path in `ContractService`), so
  `periods × monthlyRent` is safe; rent never changes mid-contract.
- All three test classes (`OverdueRuleTests`, `OverdueServiceTests`, `LatePayerServiceTests`)
  are DB-free mock tests pinned to a fixed Warsaw clock; their fixtures and query-shape
  assertions encode the calendar-window semantics and must be rewritten alongside.

## Desired End State

The dashboard/Dues list shows a contract as overdue **iff** its cumulative balance is positive:
`max(0, periodsDueSoFar × monthlyRent − totalPaid)`. For the reproduction scenario above, the
contract shows **no** overdue on 2026-07-05 (600 paid ≥ 300 due; the second 300 is credit toward
July). A skipped month keeps the contract overdue indefinitely until the balance is settled,
regardless of later months' payments. An **ended contract with an unsettled balance stays on
Dues/Dashboard** until its debt is fully paid off — periods stop accruing at `endedOn`, but the
outstanding balance keeps the row visible (it disappears only when settled, or when the owner
archives the contract, which is the FR-021 write-off gesture). The late-payer flag counts June
in that scenario as one late event (the balance *was* negative on 2026-06-07), so habitual late
payment is still surfaced even when arrears are eventually cleared.

Verify with: `mvnw.cmd test` green (rewritten suites include the reproduction scenario as a
regression test at both rule and service level), plus the manual dashboard walkthrough below.

### Key Discoveries:

- The in-term predicate both services already share — a contract owes period P iff
  `startDate ≤ dueDate(P) ≤ effectiveEnd` — generalizes directly to "first owed period" /
  "last owed period" bounds for the cumulative count (`OverdueService.java:94`,
  `LatePayerService.java:95`).
- `latestFullyDuePeriod` and `dueDate` in `OverdueRule` are semantics-neutral date helpers and
  survive unchanged.
- The live path's aggregation actually gets *simpler*: one un-windowed
  `SUM ... GROUP BY contract` for the whole portfolio replaces the per-distinct-period grouping.
- `LatePayerService` already evaluates each period at `asOf = due + 1 day`
  (`LatePayerService.java:116`) — the cumulative rule slots into the same call shape, only the
  paid-sum argument changes from "paid within P's month" to "paid through due(P)".

## What We're NOT Doing

- **No schema change.** No "period covered" column on `payments`; attribution stays derived.
  Explicit per-payment period allocation remains a possible future change if the owner needs
  "which month did this payment cover" reporting.
- **No "ended" badge on Dues rows.** An ended-but-unsettled contract appears as a normal
  overdue row (`OverdueRow` shape unchanged); visually distinguishing ended contracts is a
  possible follow-up, not part of this change.
- **Archived contracts stay excluded from the live scan.** Archiving remains the owner's way to
  write off a debt — an archived contract's arrears leave Dues (unchanged behavior; the
  late-payer history still sees it via the include-archived path).
- **No UI changes.** `OverdueRow` keeps its shape; `DuesView`/`DashboardView` untouched.
- **No change to late-payer window/threshold config** (`LatePayerProperties` stays 2 events /
  6 months).
- **Not bounding non-ended contracts by `plannedEndDate`.** A contract past its planned end but
  not actually ended keeps accruing periods (the tenant still occupies); only `endedOn` stops
  accrual. Parity with today for active contracts.

## Implementation Approach

Keep the existing three-layer shape — pure rule, live service, late-payer service — and change
the *meaning* of the paid-sum each layer passes around:

1. `OverdueRule.evaluate` becomes cumulative: it receives the contract's term bounds and a
   **total** paid sum, counts the owed periods itself (first owed period → latest fully-due
   period, capped by `effectiveEnd`), and returns the balance-based verdict. `daysOverdue`
   anchors to the **oldest not-fully-covered period** (FIFO: `totalPaid / rent` whole periods
   are covered oldest-first).
2. `OverdueService` swaps the per-period grouping + windowed aggregation for a single
   un-windowed total-paid aggregation (excluding archived) and passes term bounds to the rule.
3. `LatePayerService` swaps the per-month aggregation for cumulative-through-due-date sums
   (including archived) and judges each candidate period by the balance the day after its due
   date.

Decisions locked during planning (#3 decided by the user; #1, #2 and #4 adopted from the
recommended options while the user was AFK — revisit before implementation if either feels
wrong):

| # | Decision | Choice |
|---|----------|--------|
| 1 | `daysOverdue` anchor | Due date of the oldest not-fully-covered period (FIFO) |
| 2 | Late-payer event | Cumulative balance negative the day after the period's due date — late-but-eventually-paid still counts |
| 3 | Ended contracts with debt | **Stay on Dues until fully settled** (user decision) — scan widens to non-archived contracts; periods stop accruing at `endedOn` |
| 4 | Which payments sum | All non-archived payments of the contract, no date filter (live path); through-due-date, including archived (late-payer path) |

## Critical Implementation Details

**First-owed-period predicate.** The first owed period is the earliest month P with
`dueDate(P) ≥ startDate` — *not* `YearMonth.from(startDate)`. With payment day 28 + grace 5 a
period's due date spills into the next month, so a contract starting 2026-06-01 can owe **May**
(due 2026-06-02). This is exactly the predicate the services already apply
(`!startDate.isAfter(due)`); the cumulative count must use the same one or the two would
disagree at the boundary. Conversely a contract starting mid-month after its own month's due
date (existing test `aContractStartedAfterThePeriodsDueDateOwesNothingForThatPeriod`) first owes
the *next* month.

**Zero-owed-periods case replaces the service guard.** When the latest fully-due period
precedes the first owed period, `periodsDue = 0` → `amountDue = max(0, 0 − paid) = 0` → not
overdue. The rule now subsumes `OverdueService.inEffectOnDueDate` correctness-wise; keep the
service pre-filter anyway as a cheap skip (it spares the aggregation for future-start contracts
and preserves the existing query-shape test assertions).

**Ended contracts join the live scan.** The scan widens from "non-ended and non-archived" to
"non-archived": an ended contract with a positive balance must keep surfacing (user decision
#3). Its accrual is capped — `effectiveEnd = endedOn` — so the balance freezes at the debt as
of ending and the row drops off the moment payments cover it. At single-owner scale scanning
all non-archived contracts is fine; the settled majority cost one aggregation row each and are
filtered out by the rule.

**Late-payer cascade is intended.** Under cumulative semantics a contract that misses one month
and never catches up produces a late event **every** subsequent period (each new rent is
effectively unpaid at its due date, because payments cover oldest debt first). This is a
semantics change vs. today (one missed month = one event) and is deliberate — persistent
arrears should trip the flag. Lock it with a test, and note it in the LatePayerService javadoc.

## Phase 1: Cumulative rule (pure unit)

### Overview

Rewrite `OverdueRule.evaluate` to the cumulative-balance contract and lock it with a fully
rewritten `OverdueRuleTests`, including the reproduction scenario as a named regression.

### Changes Required:

#### 1. OverdueRule

**File**: `src/main/java/com/example/garageops/payments/OverdueRule.java`

**Intent**: Replace the single-period evaluation with a cumulative-balance one. The rule now
owns the owed-period counting (it already owns the due-date calendar math), so both services
stop reasoning about "which period" and just supply term bounds + a paid total.

**Contract**:

```java
public OverdueResult evaluate(BigDecimal monthlyRent, int paymentDayOfMonth, int graceDays,
        LocalDate contractStart, LocalDate effectiveEnd, // effectiveEnd nullable = unbounded
        BigDecimal totalPaid, Instant asOf, ZoneId zone)
```

- `firstOwedPeriod` = earliest month P with `dueDate(P) ≥ contractStart` (see Critical
  Implementation Details; a short walk from `YearMonth.from(contractStart).minusMonths(1)`).
- `lastOwedPeriod` = `latestFullyDuePeriod(asOf)`, additionally capped so
  `dueDate(P) ≤ effectiveEnd` when `effectiveEnd` is non-null.
- `periodsDue` = months between them inclusive (0 when last < first).
- `amountDue = max(0, periodsDue × monthlyRent − totalPaid)`, scale 2 HALF_UP; `overdue`
  iff positive.
- FIFO anchor: `covered = min(periodsDue, totalPaid / monthlyRent  // integer floor)`;
  `anchorPeriod = firstOwedPeriod + covered months`;
  `daysOverdue = DAYS.between(dueDate(anchorPeriod), asOfDate)` when overdue, else 0.
- `latestFullyDuePeriod` (both overloads) and `dueDate` stay public and unchanged.

Rewrite the class javadoc: the "latest fully-due period" paragraph becomes the cumulative
balance story; keep the explicit-zone paragraph as-is.

#### 2. OverdueResult

**File**: `src/main/java/com/example/garageops/payments/OverdueResult.java`

**Intent**: Re-document the record for the new semantics; shape is unchanged.

**Contract**: `period` = the FIFO anchor (oldest not-fully-covered owed period) when overdue;
when not overdue, the latest owed period (or `null` when `periodsDue == 0`). `amountDue` = the
cumulative balance floored at zero. `daysOverdue` = days past the anchor period's due date.

#### 3. OverdueRuleTests

**File**: `src/test/java/com/example/garageops/payments/OverdueRuleTests.java`

**Intent**: Rewrite to the new signature, preserving the R1/R2/R3 risk coverage and adding the
new-semantics cases.

**Contract**: keep the rent-250/day-10/grace-5 fixture and the Warsaw/New-York zone-boundary
test (R2 — the period resolution is untouched, the test only gains term-bound arguments). Cases
to cover:
- R1: unpaid single due period → overdue, full rent, days from that period's due date.
- R3: partial / exact / over payment on one period (over → not overdue, credit).
- **Reproduction regression**: start 2026-06-01, rent 300, day 1, grace 5, `totalPaid` 600,
  asOf 2026-07-05 → not overdue, amountDue 0.
- Multi-month arrears FIFO: 3 owed periods, 1× rent paid → amountDue = 2× rent, `daysOverdue`
  anchored to the *second* owed period's due date; a further partial payment does not move the
  anchor until a full period is covered.
- Prepayment: `totalPaid` ≥ dues including a payment credited before any period was due → not
  overdue.
- `periodsDue == 0` (contract starts after the only elapsed due date) → not overdue even with
  zero paid.
- `effectiveEnd` cap: periods whose due date falls after `effectiveEnd` don't accrue.
- Day-28+grace spill: contract starting the 1st owes the *previous* month when that month's
  due date lands on/after the start date (locks the first-owed-period predicate).

### Success Criteria:

#### Automated Verification:

- Rule tests pass: `mvnw.cmd test -Dtest=OverdueRuleTests`
- Full suite still compiles (services updated in Phase 2/3 — if the signature change breaks
  compilation of `OverdueService`/`LatePayerService`, adapt their call sites mechanically in
  this phase and finish their semantics in Phases 2–3): `mvnw.cmd test`

#### Manual Verification:

- (none — pure unit; end-to-end behavior is verified in Phases 2–3)

**Implementation Note**: After completing this phase and all automated verification passes,
pause for confirmation before proceeding.

---

## Phase 2: Live overdue path (Dues + Dashboard)

### Overview

Switch `OverdueService` to the total-paid aggregation and the cumulative rule; rewrite
`OverdueServiceTests` including the service-level reproduction regression.

### Changes Required:

#### 0. ContractRepository — widen the scan to non-archived

**File**: `src/main/java/com/example/garageops/contracts/ContractRepository.java`

**Intent**: The live scan must include ended contracts so unsettled debt survives contract end
(decision #3). Only `OverdueService` calls `findActiveForOverdue()`, so the finder can change
in place.

**Contract**: replace `findActiveForOverdue()` with `findNonArchivedForOverdue()` — same
`join fetch c.garage join fetch c.tenant`, `where c.archivedAt is null` only (the
`c.endedOn is null` clause is dropped). Update the javadoc: ended contracts are included so
unsettled balances keep surfacing; archived contracts remain excluded (archive = write-off).
`LatePayerService.java:28`'s javadoc references the old finder semantics by name — fix the
mention in Phase 3.

#### 1. PaymentRepository — total-paid aggregation

**File**: `src/main/java/com/example/garageops/payments/PaymentRepository.java`

**Intent**: Add the un-windowed batch aggregation the live path needs; retire the windowed one
it replaces.

**Contract**: `List<ContractPaidSum> sumPaidTotalByContractIdIn(List<Long> contractIds)` —
`SUM(amount) GROUP BY contract.id`, `archivedAt is null`, **no date bounds** (decision #4).
Reuses the `ContractPaidSum` projection. Delete `sumPaidByContractIdInPeriod` once
`OverdueService` no longer calls it (it has no other caller).

#### 2. OverdueService

**File**: `src/main/java/com/example/garageops/payments/OverdueService.java`

**Intent**: Replace the group-by-period + per-period aggregation plumbing with one
total-paid aggregation over the active contracts, and pass term bounds to the rule.

**Contract**: `duesAsOf` keeps its signature and `OverdueRow` output. Internally: scan
`findNonArchivedForOverdue()`; keep the `inEffectOnDueDate` pre-filter as a cheap skip,
extended with the end cap (a contract whose `endedOn` precedes its first owed period's due date
owes nothing — skip before aggregating); replace `sumPaidPerPeriod` with one
`sumPaidTotalByContractIdIn(ids)` call; call the rule with `contract.getStartDate()`,
`effectiveEnd = contract.getEndedOn()` (null for active contracts = unbounded accrual), and the
total. Update the class javadoc ("one aggregation, not N" now means literally one query;
ended-but-unsettled contracts stay listed).

#### 3. OverdueRow

**File**: `src/main/java/com/example/garageops/payments/OverdueRow.java`

**Intent**: Javadoc only — `amountDue` is now the cumulative balance (may exceed one month's
rent), `daysOverdue` is anchored to the oldest uncovered period.

#### 4. OverdueServiceTests

**File**: `src/test/java/com/example/garageops/payments/OverdueServiceTests.java`

**Intent**: Re-target mocks to the new aggregation and lock the new semantics at service level.

**Contract**: keep every existing scenario's spirit (unpaid → overdue; partial → remainder;
full → drops off; mixed portfolio; empty portfolio queries nothing; future-start excluded
pre-aggregation; started-after-due-date excluded) — the query-shape assertions now verify
`sumPaidTotalByContractIdIn(ids)` with no date bounds and the scan mock re-targets
`findNonArchivedForOverdue()`. Add:
- **Reproduction regression**: clock 2026-07-05 (Warsaw), contract start 2026-06-01, rent 300,
  day 1, grace 5, total paid 600 → `currentDues()` empty.
- Multi-month arrears: total paid one rent short across two owed periods → single row with
  `amountDue` = one rent, `daysOverdue` anchored per FIFO.
- **Ended with debt stays listed**: contract with `endedOn` set and totalPaid short of its
  accrued periods → row present; same ledger with the balance fully paid → absent. Accrual
  cap asserted: a period whose due date falls after `endedOn` does not increase `amountDue`.
- Ended before first due date: `endedOn` precedes the first owed period's due date → excluded
  by the pre-filter, never aggregated.

### Success Criteria:

#### Automated Verification:

- Service tests pass: `mvnw.cmd test -Dtest=OverdueServiceTests`
- Full suite green: `mvnw.cmd test`

#### Manual Verification:

- Reproduce the original report against the running app (`mvnw.cmd spring-boot:run`): contract
  2026-06-01→2027-06-01 at 300/month, two 300 payments dated 2026-07-04 and 2026-07-05, clock
  date 2026-07-05 → Dashboard and Dues show **no** overdue row for the contract.
- Skipped-month check: contract with only the *second* month paid within its own month →
  contract stays on Dues with one rent outstanding (the old code would drop it).
- Ended-with-debt check: end a contract that still owes a month → it remains on Dashboard/Dues
  with the frozen balance; record the missing payment → it disappears.

**Implementation Note**: After completing this phase and all automated verification passes,
pause for manual confirmation before proceeding.

---

## Phase 3: Late-payer flag

### Overview

Move `LatePayerService` onto cumulative-through-due-date sums and rewrite
`LatePayerServiceTests` for the new event semantics (including the intended cascade).

### Changes Required:

#### 1. PaymentRepository — cumulative-through-date aggregation

**File**: `src/main/java/com/example/garageops/payments/PaymentRepository.java`

**Intent**: Add the include-archived cumulative aggregation the late-payer re-derivation
needs; retire the windowed include-archived one.

**Contract**: `List<ContractPaidSum> sumPaidThroughDateByContractIdIn(List<Long> contractIds,
LocalDate through)` — `SUM(amount) GROUP BY contract.id` where `date <= :through`, **including
archived rows** (same FR-021 rationale as the query it replaces — keep that javadoc argument).
Delete `sumPaidByContractIdInPeriodIncludingArchived` once unused.

#### 2. LatePayerService

**File**: `src/main/java/com/example/garageops/payments/LatePayerService.java`

**Intent**: Judge each in-term candidate period by the cumulative balance the day after its due
date (decision #2) instead of by its calendar month's paid sum.

**Contract**: candidate generation and the in-term guard are unchanged. Replace the
per-period batching (`idsByPeriod`) with batching by **distinct due date**
(`Map<LocalDate, List<Long>> idsByDueDate`) feeding `sumPaidThroughDateByContractIdIn` — same
bounded query-count profile (≤ windowMonths per distinct payment-terms combination). Each
candidate is judged via the cumulative `rule.evaluate(..., contractStart, effectiveEnd,
cumulativePaidThroughDue, asOf = due+1, zone)` — the existing `asOf = due.plusDays(1)` trick is
retained. Javadoc: document the cascade semantics (persistent arrears → an event per period),
that a late-but-eventually-paid month still counts, and fix the "why this can't reuse
`OverdueService`" paragraph, which names the retired `findActiveForOverdue()` finder
(`LatePayerService.java:28`) — the reasoning still holds (late history must include archived
contracts and archived payments), only the finder name and the non-ended clause changed.

#### 3. LatePayerServiceTests

**File**: `src/test/java/com/example/garageops/payments/LatePayerServiceTests.java`

**Intent**: Rework the `givenFullyPaid` month-set stub into a cumulative stub and re-express
the scenarios under the new semantics.

**Contract**: replace the stub with one derived from a per-contract payment ledger
(date → amount); the mock answers `sumPaidThroughDateByContractIdIn` by summing ledger entries
`≤ through`. Scenario coverage:
- Threshold behavior (below / at / configured) re-expressed as ledgers.
- Two contracts overdue in the same month → two events (unchanged spirit).
- Ended + archived contracts still contribute; include-archived path asserted
  (`verify(..., never())` against the live aggregation).
- Fully-paid-on-time ledger → zero events.
- Out-of-term periods not counted; thin history; window boundary (oldest period in, one
  beyond out — now asserted via the `through` due-date argument).
- **New — late-but-paid event**: rent for month M paid after `due(M)` but before `due(M+1)`,
  everything else on time → exactly 1 event, flag stays below threshold.
- **New — cascade lock**: one missed month never caught up across the window → an event per
  subsequent in-window period.

### Success Criteria:

#### Automated Verification:

- Late-payer tests pass: `mvnw.cmd test -Dtest=LatePayerServiceTests`
- Full build green: `mvnw.cmd verify`

#### Manual Verification:

- Tenant profile for the reproduction scenario's tenant shows the late-payer counter at 1 event
  (June paid late) but **not** flagged (threshold 2), while Dues shows no current arrears —
  the two surfaces now tell consistent, complementary stories.

**Implementation Note**: After completing this phase and all automated verification passes,
pause for manual confirmation.

---

## Testing Strategy

### Unit Tests:

- `OverdueRuleTests` — cumulative math: period counting bounds (first-owed predicate, spill
  month, effectiveEnd cap, zero-periods), balance arithmetic (partial/exact/over/prepay),
  FIFO `daysOverdue` anchoring, zone boundary (retained R2), reproduction regression.
- `OverdueServiceTests` — aggregation wiring (one un-windowed query, ids filtered by the
  pre-guard), projection to `OverdueRow`, service-level reproduction regression,
  ended-with-debt visibility and `endedOn` accrual cap.
- `LatePayerServiceTests` — ledger-driven event counting, late-but-paid event, cascade,
  archived/ended retention, window bounds via `through` dates.

### Integration Tests:

- None added — the suite's existing pattern is DB-free mock tests plus the `@SpringBootTest`
  smoke test; the new repository queries are derived/JPQL aggregations of the same shape as the
  ones they replace and are exercised via `ddl-auto=validate` context startup.

### Manual Testing Steps:

1. Start the app, create garage + tenant + contract 2026-06-01→2027-06-01, 300/month,
   payment day 1.
2. Record payments 300 on 2026-07-04 and 300 on 2026-07-05 (adjust app clock / run on a date
   where July's due date hasn't passed, or set `graceDays` context accordingly).
3. Dashboard + Dues: no overdue row for the contract. Tenant profile: 1 late event, not flagged.
4. Delete the second payment (or use a fresh contract paying only month 2 in month 2): the
   contract appears on Dues with one rent outstanding and `daysOverdue` counted from the first
   month's due date.
5. End a contract that still owes a month: it stays on Dashboard/Dues with the frozen balance;
   record the outstanding payment → the row disappears; archiving it instead also removes it
   (write-off).

## Performance Considerations

The live path improves: one `SUM ... GROUP BY` for the whole portfolio replaces one per
distinct period. The late-payer path keeps the same bounded profile (≤ windowMonths distinct
due-date boundaries per payment-terms combination). No new indexes needed — both aggregations
filter on `contract_id` (FK-indexed) and `date`/`archived_at` as before.

## Migration Notes

No schema or data migration. Behavior change is read-time only: some contracts currently listed
as overdue will drop off Dues (late-paid months now credited), some previously invisible
skipped months will surface as arrears, and **ended contracts with unsettled balances will
appear on Dues for the first time**. Late-payer counts may rise (late-but-paid months and
persistent-arrears cascades now count) — mention this to the owner on rollout.

## References

- Change notes + reproduction: `context/changes/overdue-cumulative-balance/change.md`
- Root cause docs: `src/main/java/com/example/garageops/payments/Payment.java:28`
- Prior related fix (in-effect-on-due-date guard): commit `b7ce5f5`,
  `context/changes/overdue-period-before-contract-start/change.md`
- Overdue engine origin: `context/archive/2026-06-11-payments-overdue/plan.md`
- Late-payer flag origin: `context/archive/2026-06-25-late-payer-flag/plan.md`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Cumulative rule (pure unit)

#### Automated

- [x] 1.1 Rule tests pass: `mvnw.cmd test -Dtest=OverdueRuleTests` — e73ce4d
- [x] 1.2 Full suite still compiles (call sites adapted mechanically): `mvnw.cmd test` — e73ce4d

### Phase 2: Live overdue path (Dues + Dashboard)

#### Automated

- [x] 2.1 Service tests pass: `mvnw.cmd test -Dtest=OverdueServiceTests` — 752518e
- [x] 2.2 Full suite green: `mvnw.cmd test` — 752518e

#### Manual

- [x] 2.3 Reproduction scenario shows no overdue on Dashboard/Dues — 752518e
- [x] 2.4 Skipped-month contract stays on Dues with one rent outstanding — 752518e
- [x] 2.5 Ended-with-debt contract stays on Dues until settled; settling (or archiving) removes it — 752518e

### Phase 3: Late-payer flag

#### Automated

- [x] 3.1 Late-payer tests pass: `mvnw.cmd test -Dtest=LatePayerServiceTests`
- [x] 3.2 Full build green: `mvnw.cmd verify`

#### Manual

- [x] 3.3 Tenant profile shows 1 late event / not flagged for the reproduction scenario
