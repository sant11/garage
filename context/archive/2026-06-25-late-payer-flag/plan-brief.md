# Frequent-Late-Payer Flag — Plan Brief

> Full plan: `context/changes/late-payer-flag/plan.md`

## What & Why

Surface a "frequent late payer" flag on a tenant's profile when they have **≥ 2 overdue events in the last 6 months** (FR-020 / roadmap S-07). The owner needs to spot slipping tenants at a glance instead of mentally reconstructing payment history. The flag is informational and owner-only — never visible to tenants.

## Starting Point

The overdue machinery already exists and was built to be re-run over the past: the pure `OverdueRule` resolves and judges a single period from any `asOf` instant (S-05 explicitly named this the S-07 seam). Overdue is never stored — it is derived. The Tenant entity and `TenantProfileView` were both left with a deliberately empty slot for this badge.

## Desired End State

Opening `tenants/<id>` for a tenant who slipped on rent in ≥ 2 of the last 6 fully-due periods (across their active, ended, and archived contracts) shows a badge beside their name with a count tooltip. Below the threshold, no badge. The threshold and window are config-tunable (defaults 2 / 6). No schema change, no stored flag.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Meaning of "overdue event" | Still-unpaid period, reuse `OverdueRule` verbatim | Avoids a second, divergent overdue definition diverging from the dashboard | Plan |
| Counting | Per (contract, period) | Most faithful to "events"; a multi-garage tenant slipping on both is genuinely higher-risk | Plan |
| Contract scope | All overlapping the window, incl. ended & archived | A tenant's late history must not vanish when a contract ends (FR-021 retains it) | Plan |
| Threshold | `application.properties` (defaults 2 / 6) | PRD Open Q1 calls both numbers provisional defaults to tune | Plan |
| Window | 6 most-recent **fully-due** periods (exclude in-progress month) | You can't be "late" for a month not yet due; aligns with `latestFullyDuePeriod` | Plan |
| Surfacing | Badge when flagged, nothing otherwise | Matches the code's own note that an always-present badge falsely reads as "clean" | Plan |
| Cost model | Derive on profile load only, no cache | Tiny per-load cost at MVP scale; matches "derive, don't store" convention | Plan |

## Scope

**In scope:** a configurable threshold, an include-archived paid-sum query, a `LatePayerService` + `LatePayerFlag` reusing `OverdueRule`, unit tests, and a header badge on the tenant profile.

**Out of scope:** schema/entity change, stored flag, "paid-late" detection, portfolio-wide scan, dashboard signal, caching, threshold UI, triggering-period breakdown UI, any change to `OverdueRule`/`OverdueService` behavior.

## Architecture / Approach

New `LatePayerService` in the `payments` package. For a tenant: load all contracts (`findByTenantIdOrderByStartDateDesc` — already includes ended/archived) → per contract compute its 6 most-recent fully-due periods, keeping only periods where the contract was in effect on the due date → batch the per-period paid sums (include-archived variant), grouped by period like `OverdueService` → judge each (contract, period) with `OverdueRule.evaluate` driven at a synthetic `asOf = dueDate(period)+1 day` → count overdue pairs, flag at the threshold. `TenantProfileView` renders the result as a badge.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Backend derivation | Config, include-archived query, `LatePayerService` + `LatePayerFlag`, unit tests | False positives from the archived-payment exclusion or out-of-term periods (both explicitly guarded) |
| 2. Profile badge | Header badge + count tooltip on the tenant profile | Off-session rendering / wiring; verified by app boot + manual checks |

**Prerequisites:** S-05 (payments & overdue) and S-03 (tenant profile) — both done.
**Estimated effort:** ~1 session across 2 phases.

## Open Risks & Assumptions

- **Archived payments must be summed** for historical truth — the existing live query excludes them, which would flag every period of an archived contract as overdue. A dedicated include-archived finder fixes this; a test guards it.
- **"Still-unpaid" semantics** mean a tenant who chronically pays *late but eventually in full* is not flagged. This matches the trusted dashboard definition; revisit if the owner finds it too lenient (ties to PRD Open Q1).
- Assumes `findByTenantIdOrderByStartDateDesc` continues to apply no `archivedAt`/`endedOn` filter (verified at plan time, `ContractRepository.java:28-30`).

## Success Criteria (Summary)

- The owner sees a clear late-payer badge on the profile of any tenant with ≥ 2 overdue events in the last 6 months, and none on tenants without the pattern.
- The flag reflects ended and archived contracts (history survives), with no out-of-term or archived-but-paid false positives.
- The threshold/window can be tuned via config without a code change.
