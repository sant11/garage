---
change_id: overdue-cumulative-balance
title: Compute overdue from cumulative balance instead of payment-date windows
status: implementing
created: 2026-07-05
updated: 2026-07-05
archived_at: null
---

## Notes

Fix overdue calculation to use cumulative balance (periods due since contract start × rent − total paid since start) instead of per-calendar-month payment-date windows; affects OverdueRule/OverdueService and LatePayerService.

Motivating case: contract 2026-06-01 → 2027-06-01, 300 zł/month, payment day 1, grace 5; two 300 zł payments on 2026-07-04 and 2026-07-05. As of 2026-07-05 the resolved period is June (July's due date 2026-07-06 hasn't passed), but both payments fall in July's calendar window, so June sums to 0 and the dashboard shows 300 zł / 29 days overdue — despite the tenant having paid 600 zł against 300 zł due. The inverse error also exists: a genuinely skipped month vanishes from the dashboard once the next month's due date passes and is paid within its own window. Root cause: `Payment` has no period attribution — `sumPaidByContractIdInPeriod` buckets by payment date (`Payment.java` "period membership is by date alone").

Chosen semantics: cumulative balance — overdue = max(0, dueSoFar − paidSinceStart) where dueSoFar = (count of periods whose due date has passed, counting only periods on/after contract start per commit b7ce5f5's guard) × monthlyRent. No schema change. `LatePayerService` (per-period re-derivation) must be reconciled with the same semantics.
