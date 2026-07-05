---
change_id: overdue-period-before-contract-start
title: Stop flagging contracts overdue for periods before their start date
status: implemented
created: 2026-07-05
updated: 2026-07-05
archived_at: null
---

## Notes

OverdueService flags contracts overdue for periods before their start date; add the missing due-date-vs-startDate guard (mirroring LatePayerService) plus regression test.

Diagnosed live in-session (user report: contract started 2026-07-01, payment day 1, default grace 5, Dashboard showed 28 days overdue for June — a period predating the contract). Root cause: `OverdueService.duesAsOf` only guarded against future-start contracts, not against the resolved period's due date falling before `startDate`. `LatePayerService` already had the correct in-effect-on-due-date guard (its javadoc names this exact false positive).

Fix (implemented directly — diagnosis served as frame/research, no separate plan.md):
- `OverdueRule.dueDate` made public (second caller appeared; removes the duplicated formula).
- `OverdueService`: future-start filter replaced by `inEffectOnDueDate` — skip a contract whose resolved period's due date precedes its `startDate` (subsumes the future-start case).
- `LatePayerService`: private `dueDate` duplicate replaced with `rule.dueDate`.
- Regression test `aContractStartedAfterThePeriodsDueDateOwesNothingForThatPeriod` in `OverdueServiceTests`.

Full suite green 2026-07-05. Known accepted semantic (consistent with S-07): a contract starting after a month's due date owes nothing for that month at all — flagged for PRD confirmation if proration is ever expected.
