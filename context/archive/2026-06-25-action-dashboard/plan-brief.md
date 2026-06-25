# Action Dashboard (S-06) — Plan Brief

> Full plan: `context/changes/action-dashboard/plan.md`

## What & Why

GarageOps' north-star slice: turn passive rental data into the owner's daily action list. Replace the placeholder landing screen with a post-login **action dashboard** showing the three signals that drive action — garages with overdue payments, currently-vacant garages, and contracts ending in the next 30 days — each row drillable into the underlying garage (US-01, FR-015–FR-018). This is the validation milestone that proves the whole product hypothesis.

## Starting Point

S-04 (contracts) and S-05 (payments & overdue) are done. The overdue derivation already exists and is reusable as-is (`OverdueService.currentDues()`), and `DuesView` renders it at `"dues"`. The landing route `""` is a placeholder `HomeView`. Vacant-garage and ending-soon signals don't exist yet. View, nav, grid, and drill-through patterns are all established in existing views.

## Desired End State

After login the owner lands on a dashboard with three urgency-ordered sections, each headed by a count and falling back to friendly empty copy when there's nothing to show. Every row drills to the garage detail. The data is recomputed each time the owner opens the dashboard — no manual refresh. The dedicated Dues page stays as-is.

## Key Decisions Made

| Decision                  | Choice                                                               | Why (1 sentence)                                                                 | Source |
| ------------------------- | ------------------------------------------------------------------- | ------------------------------------------------------------------------------- | ------ |
| Vacancy duration source   | `max(endedOn)` of the garage's contracts; fallback to creation date | Matches "empty since the last tenant left" and reuses existing contract data.    | Plan   |
| Landing & Dues page       | Dashboard at `""`; keep `DuesView` at `"dues"`                       | Dashboard stays action-focused; the richer FR-014 dues view is preserved.        | Plan   |
| Problem-flagged section   | Excluded                                                            | Ships the three must-have FRs exactly; avoids scope creep on the north star.      | Plan   |
| Drill targets             | All rows → garage detail (`garages/:id`)                            | One consistent target that's already the action hub (record payment, end early). | Plan   |
| Ordering                  | Most-urgent-first per section                                        | Matches the PRD's "ordered by urgency" Business Logic.                            | Plan   |
| Freshness                 | Recompute server-side on each navigation (no `@Push`)               | Satisfies US-01 as written; matches every other view; avoids over-engineering.   | Plan   |
| Testing                   | Unit-test new derivations; manual UI verification                   | Tests where bugs hide (date boundaries, fallbacks); matches repo's test style.   | Plan   |
| Presentation              | Counts in headers + per-section friendly empty copy                 | Exactly US-01's acceptance; counts give an at-a-glance read.                      | Plan   |

## Scope

**In scope:** Dashboard view at `""`; vacant-garage derivation (with vacancy-since); ending-soon query + service; reuse of overdue; counts, ordering, empty states, garage drill-through; nav entry; unit tests for new logic.

**Out of scope:** Problem-flagged section; `@Push` live updates; removing/reworking Dues; new contract-detail route; Vaadin view-test harness; long-vacant flag (FR-019); revenue/non-action signals.

## Architecture / Approach

A new `dashboard`-feature `DashboardService` computes the two new signals: vacant = active garages − rented (via `ContractService.rentedGarageIds`), each with vacancy-since from a batched `max(endedOn)` aggregation (fallback to creation date); ending-soon = a new `JOIN FETCH` window query on `Contract`. Both return off-session-safe row records. `DashboardView` (route `""`, under `MainLayout`) composes these plus `OverdueService.currentDues()` into three grids and recomputes on navigation. All "today" derivations use the injected `Clock`. No schema changes.

## Phases at a Glance

| Phase                            | What it delivers                                                      | Key risk                                                              |
| -------------------------------- | -------------------------------------------------------------------- | -------------------------------------------------------------------- |
| 1. Signal derivation backend     | Vacant + ending-soon services, queries, row records, unit tests       | Vacancy-since fallback & 30-day boundary correctness                  |
| 2. Dashboard view & landing wiring | `DashboardView` at `""`, sections/counts/empty/drill, nav, HomeView removal | Landing-route swap; off-session rendering (LazyInitialization) |

**Prerequisites:** S-04 and S-05 archived/done (met). JDK 21 on `JAVA_HOME`.
**Estimated effort:** ~1–2 sessions across 2 phases.

## Open Risks & Assumptions

- "Vacancy duration" is interpreted as a derived value (no stored field); the `max(endedOn)`-with-creation-fallback rule is the chosen semantics, not a PRD-pinned one.
- "Reflects current data without manual refresh" is interpreted as recompute-on-navigation, not real-time push.
- Off-session rendering depends on the ending-soon query's `JOIN FETCH`; a missed fetch surfaces as `LazyInitializationException` (open-in-view=false).

## Success Criteria (Summary)

- The owner lands on the dashboard after login and sees overdue / vacant / ending-soon with correct counts and urgency ordering.
- Every row drills to the correct garage; a fresh portfolio shows friendly empty copy in each section.
- New derivation logic is unit-tested and `mvnw.cmd verify` is green.
