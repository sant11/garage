# Payments & Overdue (S-05) — Plan Brief

> Full plan: `context/changes/payments-overdue/plan.md`
> Research: `context/changes/payments-overdue/research.md`

## What & Why

Build the entire payments side of GarageOps (FR-012/013/014): record rent payments against contracts and derive a per-period "overdue" status from them. The contracts slice was deliberately shaped to hand off here, leaving two obligations — a per-contract `grace_days` and an injectable zone-fixed `Clock` — for this slice to land.

## Starting Point

No `Payment` entity, repository, service, table, or view exists; migrations stop at `V6`. Every pattern needed already lives in the `contracts` slice to copy (`ArchivableEntity`, `@Service` + `ObjectProvider`, `@Transactional`-writes-only, `@ManyToOne(LAZY)` + `JOIN FETCH`, `HasUrlParameter<Long>` views, the new-contract dialog). No aggregation query (`SUM`/`GROUP BY`) exists anywhere yet.

## Desired End State

The owner records a payment from a contract's detail view, sees a contract's and a tenant's payment history, and opens a **Dues** view listing every overdue garage (garage, tenant, amount due, days overdue). Overdue is computed live by a pure clock-driven rule that accepts an "as-of" instant — so the S-06 dashboard and S-07 late-payer flag can consume it later unchanged, with no overdue state persisted now.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Overdue history model | As-of-date derivation (Option A) | Stateless, self-healing, matches "derived, never stored"; keeps S-07 viable without a scheduler/writer. | Plan |
| `grace_days` representation | Per-contract column, default 5 | A per-contract historical fact so changing one never reclassifies another's past events. | Plan |
| Period membership | By `payment.date` in period | Natural FR-013 reading; keeps `Payment` lean (no stored period column). | Plan |
| Overdue summing | Batch JPQL `SUM ... GROUP BY` | One round-trip for the portfolio scan, avoids N+1 (codebase's first aggregation). | Plan |
| Evaluation boundary | Latest fully-due period, look back | Catches an unpaid prior month at month boundaries; still one period at a time. | Plan |
| Record-payment UX | Dialog from contract context | Reuses the new-contract dialog precedent exactly; payment always contract-scoped. | Plan |
| `payment_day_of_month` cap | Keep 1–28 | Short-month due-date edge stays structurally impossible; no clamping logic. | Plan |

## Scope

**In scope:** Payment entity + `V7` migration (payments table + `grace_days`); injectable Europe/Warsaw `Clock`; pure as-of-date overdue rule; `PaymentService` (record + archive cascade); overdue derivation service + `OverdueRow` DTO; record-payment dialog; per-contract & per-tenant payment history; `DuesView` + side-nav.

**Out of scope:** S-06 dashboard; S-07 late-payer flag; persisted `overdue_event` rows / scheduler; cross-period arrears; relaxing the day cap; global grace-days property; hard-delete UI.

## Architecture / Approach

Engine-first. A pure `OverdueRule.evaluate(rent, paymentDay, graceDays, paidInPeriod, asOf)` (no DB, no clock-reading inside) resolves "the latest fully-due period" and returns overdue/amountDue/daysOverdue. A batch `SUM ... GROUP BY` query produces `paidInPeriod` for many contracts at once; `OverdueService` joins the two with `asOf = clock.instant()` and projects `OverdueRow` DTOs for the off-session-rendered Dues view. Writes (`record`, archive-cascade) follow the `@Transactional`-writes-only service idiom.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Overdue engine + Clock | Pure as-of-date rule + fixed-zone Clock; R1/R2/R3 tests | Period-resolution / timezone-boundary correctness |
| 2. Data model & migration | Payment entity, V7, `grace_days`, batch aggregation repo | `ddl-auto=validate` mismatch; first aggregation query |
| 3. Payment & overdue services | `PaymentService`, `OverdueService`, `OverdueRow` DTO | Archive cascade must stamp-not-delete (FR-021) |
| 4. Views & navigation | Record dialog, payment history, Dues view + nav | Off-session lazy-load (open-in-view=false) |

**Prerequisites:** JDK 21 on `JAVA_HOME`; contracts slice in place (it is). 
**Estimated effort:** ~3–4 sessions across 4 phases.

## Open Risks & Assumptions

- Period-resolution + timezone math (R2) is the subtle part — fully covered by Phase 1 pure-unit tests with a fixed clock.
- The batch aggregation is the codebase's first `SUM ... GROUP BY`; must keep date math in Java (the rule), not dialect-specific SQL.
- Threading `asOf` everywhere is load-bearing for S-07 — a `today`-only signature is the named trap and is explicitly avoided.

## Success Criteria (Summary)

- Recording a partial payment surfaces the garage in **Dues** with correct amount due / days overdue; paying the remainder removes it.
- Payments appear in both the contract's and the tenant's history; archiving a contract retains its payments.
- Overdue flips at the expected due+grace boundary, deterministically (fixed-zone clock).
