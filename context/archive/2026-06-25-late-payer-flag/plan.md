# Frequent-Late-Payer Flag (FR-020 / S-07) Implementation Plan

## Overview

Surface a "frequent late payer" flag on a tenant's profile when that tenant has a pattern of late payments — **≥ 2 overdue events in the last 6 months** (FR-020). An "overdue event" is a (contract, period) pair where the canonical FR-013 overdue rule, re-derived over a past period, shows a shortfall. The flag is informational and owner-only (never tenant-visible), derived live on profile load with no stored state and no schema change.

## Current State Analysis

The overdue machinery this slice needs already exists and was deliberately shaped to be re-runnable over the past:

- **`OverdueRule`** (`src/main/java/com/example/garageops/payments/OverdueRule.java:32-81`) is a pure, persistence-free unit. `evaluate(monthlyRent, paymentDayOfMonth, graceDays, paidInPeriod, asOf, zone)` (`:44`) resolves a single "latest fully-due period" from `asOf` and reports `overdue`/`amountDue`/`daysOverdue`. `latestFullyDuePeriod(...)` (`:65`) is public so a caller can resolve a period before knowing the paid sum. The S-05 plan called the explicit `asOf` parameter the seam "so S-07 can re-derive past periods."
- **`OverdueService.duesAsOf(Instant asOf)`** (`src/main/java/com/example/garageops/payments/OverdueService.java:68`) shows the orchestration pattern (resolve period → batch paid-sum grouped by period → run rule per contract). **It cannot be reused directly**: it scans `findActiveForOverdue()`, which filters to non-ended **and** non-archived contracts — we need ended/archived contracts in the lookback.
- **`ContractRepository.findByTenantIdOrderByStartDateDesc`** (`src/main/java/com/example/garageops/contracts/ContractRepository.java:28-30`) returns **all** of a tenant's contracts with **no `endedOn`/`archivedAt` filter** — exactly the include-everything set this lookback needs. No new contract finder required.
- **`PaymentRepository.sumPaidByContractIdInPeriod`** (`src/main/java/com/example/garageops/payments/PaymentRepository.java:50-54`) batch-sums payments per contract in a date range, **but filters `and p.archivedAt is null`**. Archiving a contract cascade-archives its payments, so reusing this query for an archived contract returns 0 for periods that were genuinely paid — a false-positive generator (see Critical Implementation Details).
- **`Clock`** is an injected, fixed-zone (Europe/Warsaw) bean (`ClockConfig`). All "now"-dependent logic uses it; tests pin `Clock.fixed(...)` (see `OverdueServiceTests`/`DashboardServiceTests`).
- **`TenantProfileView`** (`src/main/java/com/example/garageops/tenants/TenantProfileView.java:84-101`) renders the tenant name in a header `HorizontalLayout` with a **deliberately open slot** for this badge (`:88-89`). The codebase's badge idiom is a `Span` with a theme (`"badge error"` / `"badge success"` / `"badge contrast"`) and an optional `title` tooltip property (`statusBadge`, `:180-196`; `LocationsView.statusBadge`).

### Key Discoveries:

- A period's **overdue boolean is `asOf`-independent**: `evaluate` computes `amountDue = monthlyRent − paidInPeriod` and `overdue = amountDue > 0`; `asOf` only selects *which* period and computes `daysOverdue`. So `OverdueRule.evaluate` can be reused verbatim to judge any specific period by passing an `asOf` that resolves to it (`OverdueRule.java:46-56`).
- The Tenant entity has **no flag field and needs none** (`Tenant.java:18-21` documents the intentional omission) — the flag is purely derived.
- `tenants` already depends on `payments` (`TenantProfileView` imports `PaymentService`), and `payments` already depends on `contracts` (`OverdueService` imports `ContractRepository`) — the new service fits these existing dependency directions.

## Desired End State

Opening `tenants/<id>` for a tenant with ≥ 2 overdue events across the last 6 fully-due periods (counting their active, ended, and archived contracts) shows a "frequent late payer" badge beside the name, with a tooltip naming the event count. A tenant below the threshold shows no badge. The threshold and window are externalized config (defaults 2 / 6). Verify via unit tests over the derivation and a manual profile check.

## What We're NOT Doing

- **No schema change, no new entity, no stored flag.** The flag is derived on read.
- **No "paid-late" detection.** An event is a *still-unpaid* period (rent not fully covered within its calendar month), reusing the FR-013 rule verbatim — not "paid after the due date." (Decided in planning; see plan-brief Key Decisions.)
- **No portfolio-wide late-payer scan, no dashboard signal, no batch job, no caching.** Derivation runs only when a single tenant's profile loads.
- **No owner-editable threshold UI.** Tuning is via `application.properties` (PRD Open Q1).
- **No breakdown UI** (which months/contracts triggered the flag). The per-period detail is already reachable via the dues and contract drill-downs.
- **No change to `OverdueRule` / `OverdueService` public behavior.** They are consumed, not modified.

## Implementation Approach

Add a tenant-scoped derivation in the `payments` package that reuses the pure `OverdueRule`. For the given tenant, load all their contracts (`findByTenantIdOrderByStartDateDesc`, which already includes ended/archived). For each contract, compute its `windowMonths` most-recent fully-due periods (P0 = `latestFullyDuePeriod(now)`, then back `windowMonths-1`), keeping only periods where the contract was in effect on the period's due date. Batch the per-period paid sums (include-archived variant), grouped by period like `OverdueService`. Judge each (contract, period) with `OverdueRule.evaluate` driven at a synthetic `asOf = dueDate(period) + 1 day`. Count overdue pairs; flag when count ≥ `minEvents`. Surface the resulting `LatePayerFlag` as a header badge in `TenantProfileView`.

## Critical Implementation Details

- **Include-archived paid sum (correctness-critical).** The historical reconstruction must sum **all** payments in a period regardless of `archivedAt`. Archiving is retention (FR-021), not evidence of non-payment; the existing `sumPaidByContractIdInPeriod` excludes archived rows and would report 0-paid for archived contracts, flagging every in-window period as overdue. Use a new finder that drops the `archivedAt` predicate.
- **Period must be inside the contract's term, or it is a false positive.** A contract contributes a period only if it was in effect on that period's due date: `startDate ≤ dueDate(period) ≤ effectiveEnd`, where `effectiveEnd = endedOn != null ? endedOn : plannedEndDate`. Without this guard, months before the contract started or after it ended have zero payments and would each count as an overdue event. This mirrors how `duesAsOf` excludes future-start contracts (`OverdueService.java:71`).
- **Synthetic as-of to target a specific period with the verbatim rule.** To judge period `P` via `OverdueRule.evaluate`, pass `asOf = dueDate(P).plusDays(1)` resolved to an `Instant` in the clock's zone. Because due dates are ~monthly and the payment day is capped 1–28, `dueDate(P) < asOfDate ≤ dueDate(P+1)` holds, so the rule resolves exactly `P`. Only `.overdue()` is read; `daysOverdue` is irrelevant here.
- **Use the injected `Clock`, never `LocalDate.now()`.** `now = clock.instant()`, `zone = clock.getZone()`. (Note: `TenantProfileView.contractsSection` uses `LocalDate.now()` at `:119` for an unrelated status column — do not extend that; the derivation owns its clock.)

---

## Phase 1: Backend derivation

### Overview

Add the configurable threshold, the include-archived paid-sum query, and a `LatePayerService` that derives a `LatePayerFlag` for a tenant by reusing `OverdueRule`. Fully unit-testable headless.

### Changes Required:

#### 1. Threshold & window configuration

**File**: `src/main/java/com/example/garageops/payments/LatePayerProperties.java` (new)

**Intent**: Externalize the FR-020 defaults (PRD Open Q1 calls both numbers provisional) so they tune without a logic change.

**Contract**: `@ConfigurationProperties(prefix = "garageops.late-payer")` constructor-bound record `LatePayerProperties(int minEvents, int windowMonths)` with defaults `minEvents = 2`, `windowMonths = 6` (apply defaults in the canonical constructor when values are absent). Register it (e.g. `@EnableConfigurationProperties(LatePayerProperties.class)` on a `@Configuration` such as `ClockConfig`, or `@ConfigurationPropertiesScan` on the application class — match whatever registration the codebase already uses; if none, add `@EnableConfigurationProperties`).

**File**: `src/main/resources/application.properties`

**Intent**: Document the two keys with their defaults alongside the other `garageops.*` blocks.

**Contract**: Add `garageops.late-payer.min-events=2` and `garageops.late-payer.window-months=6` with a short comment noting these are FR-020 tuning defaults.

#### 2. Include-archived per-period paid sum

**File**: `src/main/java/com/example/garageops/payments/PaymentRepository.java`

**Intent**: Provide a paid-sum aggregation that counts archived payments, for historical reconstruction of contracts that may since have been archived (the live overdue path must keep excluding archived rows — add a sibling, don't change the existing query).

**Contract**: New method returning the existing `ContractPaidSum` projection, identical to `sumPaidByContractIdInPeriod` but **without** the `and p.archivedAt is null` predicate. Name it to make the difference obvious, e.g. `sumPaidByContractIdInPeriodIncludingArchived(List<Long> contractIds, LocalDate periodStart, LocalDate periodEnd)`. Javadoc must state why archived payments are included (FR-021 retention ≠ non-payment) and that the live overdue path deliberately does not use it.

#### 3. Late-payer flag value type

**File**: `src/main/java/com/example/garageops/payments/LatePayerFlag.java` (new)

**Intent**: Carry the derivation result off-session to the view, including enough to render the tooltip.

**Contract**: `record LatePayerFlag(boolean flagged, int eventCount, int windowMonths, int minEvents)`. Plain values only (no entity references), consistent with `OverdueRow`/`VacantGarageRow`.

#### 4. Derivation service

**File**: `src/main/java/com/example/garageops/payments/LatePayerService.java` (new)

**Intent**: Derive the flag for one tenant by reusing the pure `OverdueRule` over each contract's recent fully-due periods, counting overdue (contract, period) events and comparing to the threshold.

**Contract**: `@Service` with constructor injection mirroring `OverdueService` — `Clock`, `ObjectProvider<ContractRepository>`, `ObjectProvider<PaymentRepository>`, `LatePayerProperties`; hold a plain `private final OverdueRule rule = new OverdueRule();` (the rule is a pure value, as `OverdueService.java:44` does). Public method `LatePayerFlag evaluate(Long tenantId)`.

Algorithm:
1. `now = clock.instant()`, `zone = clock.getZone()`.
2. `contracts = contractRepo.findByTenantIdOrderByStartDateDesc(tenantId)` (already includes ended + archived).
3. For each contract, build its window: `p0 = rule.latestFullyDuePeriod(paymentDayOfMonth, graceDays, now, zone)`; periods = `p0, p0.minusMonths(1), … , p0.minusMonths(windowMonths-1)`.
4. Keep only periods where the contract was in effect on the due date: `!startDate.isAfter(dueDate(period))` and `!effectiveEnd.isBefore(dueDate(period))`, with `effectiveEnd = endedOn != null ? endedOn : plannedEndDate` and `dueDate(period) = period.atDay(paymentDayOfMonth).plusDays(graceDays)`. (See Critical Implementation Details — this guard prevents out-of-term false positives.)
5. Batch paid sums grouped by `YearMonth`: for each distinct in-window period, call `sumPaidByContractIdInPeriodIncludingArchived(idsForThatPeriod, period.atDay(1), period.atEndOfMonth())`; store results in a `Map` keyed by **(contractId, period)** (a contract appears in several periods, so the key cannot be contractId alone).
6. For each surviving (contract, period): `asOf = dueDate(period).plusDays(1).atStartOfDay(zone).toInstant()`; `overdue = rule.evaluate(monthlyRent, paymentDayOfMonth, graceDays, paidByKey.get(key), asOf, zone).overdue()`. Count `true`.
7. `flagged = eventCount >= minEvents`; return `new LatePayerFlag(flagged, eventCount, windowMonths, minEvents)`.

Note the `dueDate`/period helpers are private to `OverdueRule`; recompute `dueDate` locally in the service (one-liner) rather than widening `OverdueRule`'s API.

### Success Criteria:

#### Automated Verification:

- Single-file compile of each new/changed file passes (per-edit javac check).
- New unit test class passes: `mvnw.cmd test -Dtest=LatePayerServiceTests`
- Full suite passes: `mvnw.cmd test`
- No `@ManyToOne` added without `fetch = FetchType.LAZY` (none expected — no entity change); no field `@Autowired`.

#### Manual Verification:

- None for this phase (pure backend; covered by unit tests).

**Implementation Note**: After completing this phase and all automated verification passes, pause for human confirmation before Phase 2.

---

## Phase 2: Profile badge

### Overview

Render the flag in the tenant profile header's open slot, with a count tooltip; show nothing when not flagged.

### Changes Required:

#### 1. Inject the service and render the badge

**File**: `src/main/java/com/example/garageops/tenants/TenantProfileView.java`

**Intent**: Drop a "frequent late payer" badge into the existing header `HorizontalLayout` slot (`:88-93`) when the derived flag is set; leave the header unchanged otherwise.

**Contract**: Add `LatePayerService` (from `com.example.garageops.payments`) as a constructor-injected field alongside the existing services (`:58-62`). In `render(...)` (`:84`), after building the `header`, call `latePayerService.evaluate(tenant.getId())`; if `flag.flagged()`, add a `Span("frequent late payer")` with theme `"badge error"` and a `title` tooltip property such as `"<eventCount> overdue events in the last <windowMonths> months"` (follow the `Span` + `getElement().getThemeList().add(...)` + `setProperty("title", ...)` idiom from `statusBadge` `:193-195` and `LocationsView.statusBadge`). When not flagged, add nothing (honoring the `:88-89` "no always-absent badge" note). Update the class javadoc (`:34-35`) to record that the S-07 slot is now filled.

### Success Criteria:

#### Automated Verification:

- Single-file compile of `TenantProfileView.java` passes.
- Full suite passes: `mvnw.cmd test`
- App boots: `mvnw.cmd spring-boot:run` starts without `LazyInitializationException` or context-wiring errors (Ctrl-C after confirming startup).

#### Manual Verification:

- A tenant with ≥ 2 overdue events in the last 6 months shows the badge beside their name on `tenants/<id>`; hovering shows the event-count tooltip.
- A tenant with 0–1 events shows **no** badge.
- A tenant whose only late history is on an **ended or archived** contract still shows the badge (history survives the contract).
- A tenant flagged purely by a period **outside any contract's term** does **not** appear (no out-of-term false positive).
- Overriding `garageops.late-payer.min-events=3` changes who is flagged as expected.
- Badge is visible and legible on a phone-width viewport (NFR mobile).

**Implementation Note**: After Phase 2 automated verification passes, pause for human confirmation of the manual checks before closing the change.

---

## Testing Strategy

Mirror `OverdueServiceTests` / `DashboardServiceTests`: no Spring context — pure JUnit 5 + Mockito + AssertJ, a pinned `Clock.fixed(Instant.parse("..."), ZoneId.of("Europe/Warsaw"))`, sibling repositories mocked via `ObjectProvider`, and a real `OverdueRule` (it is pure and already unit-tested).

### Unit Tests (`LatePayerServiceTests`):

- **Below threshold → not flagged**: tenant with exactly `minEvents - 1` overdue periods.
- **At threshold → flagged**: exactly `minEvents` overdue periods; assert `flagged`, `eventCount`.
- **Per-(contract, period) counting**: tenant with two contracts each overdue in the same month counts as 2 events (confirms the chosen aggregation).
- **Ended/archived contract counts**: an overdue period on an ended contract and on an archived contract (with archived payments) both contribute — exercises `findByTenantIdOrderByStartDateDesc` returning them and the include-archived sum.
- **Archived-but-paid is NOT an event**: an archived contract fully paid in a period must not count (guards the include-archived query against the false-positive it fixes).
- **Out-of-term exclusion**: periods before `startDate` / after `effectiveEnd` are not counted (no zero-payment false positives).
- **Thin history (< window)**: a tenant with 2 months of history evaluates only the in-term periods and is not flagged unless genuinely slipping.
- **Window boundary**: the `windowMonths`-th period back is included; the one beyond is not.
- **Config respected**: `LatePayerProperties(3, 6)` changes the flag outcome on the same data.
- **No contracts / no payments**: returns `flagged = false`, `eventCount = 0` (no exceptions).

### Manual Testing Steps:

1. Seed (or use existing) a tenant with two unpaid past months on one contract; open `tenants/<id>` → badge present, tooltip shows "2 …".
2. Record enough payments to clear one month → reload → no badge (count drops to 1).
3. End or archive that contract → reload → badge state unchanged (history persists).
4. Open a tenant with no overdue history → no badge.
5. Resize to phone width → badge still legible beside the name.

## Performance Considerations

Derivation runs only on single-tenant profile load. Cost is one contract query plus at most `windowMonths` (default 6) grouped paid-sum queries — independent of portfolio size, well within the NFR 1-second acknowledgement at the PRD's small-data / low-QPS scale. The group-by-period batching mirrors `OverdueService` and avoids any per-(contract, period) N+1. No caching by design (the "derive, don't store" convention); revisit only if a portfolio-wide late-payer scan is ever added.

## Migration Notes

None. No schema change, no new table, no data backfill. The two new `application.properties` keys have safe in-code defaults, so existing deploys need no env changes.

## References

- Roadmap slice: `context/foundation/roadmap.md` (S-07, lines 167-178)
- PRD: FR-020 (`context/foundation/prd.md:117`), Open Q1 (threshold tuning, `:159`), FR-021 (archive retention, `:121`)
- Canonical overdue rule: `src/main/java/com/example/garageops/payments/OverdueRule.java:32-81`
- Orchestration pattern to mirror: `src/main/java/com/example/garageops/payments/OverdueService.java:68-106`
- All-contracts-for-tenant finder: `src/main/java/com/example/garageops/contracts/ContractRepository.java:28-30`
- Paid-sum query to clone (sans archived filter): `src/main/java/com/example/garageops/payments/PaymentRepository.java:50-54`
- Badge slot + idiom: `src/main/java/com/example/garageops/tenants/TenantProfileView.java:88-93`, `:180-196`
- Test patterns: `src/test/java/com/example/garageops/payments/OverdueRuleTests.java`, `src/test/java/com/example/garageops/dashboard/DashboardServiceTests.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Backend derivation

#### Automated

- [x] 1.1 Single-file compile of each new/changed file passes — 1c48c7c
- [x] 1.2 `LatePayerServiceTests` passes (`mvnw.cmd test -Dtest=LatePayerServiceTests`) — 1c48c7c
- [x] 1.3 Full suite passes (`mvnw.cmd test`) — 1c48c7c
- [x] 1.4 No field `@Autowired` and no `@ManyToOne` without explicit LAZY introduced — 1c48c7c

### Phase 2: Profile badge

#### Automated

- [x] 2.1 Single-file compile of `TenantProfileView.java` passes — 407ddf1
- [x] 2.2 Full suite passes (`mvnw.cmd test`) — 407ddf1
- [x] 2.3 App boots without `LazyInitializationException` or wiring errors — 407ddf1

#### Manual

- [x] 2.4 Tenant with ≥ 2 events shows badge + count tooltip on `tenants/<id>` — owner-confirmed 2026-06-25
- [x] 2.5 Tenant with 0–1 events shows no badge — owner-confirmed 2026-06-25
- [x] 2.6 Late history on an ended/archived contract still flags — owner-confirmed 2026-06-25
- [x] 2.7 Out-of-term period produces no false-positive flag — owner-confirmed 2026-06-25
- [x] 2.8 `min-events=3` override changes who is flagged — owner-confirmed 2026-06-25
- [x] 2.9 Badge legible on phone-width viewport — owner-confirmed 2026-06-25
