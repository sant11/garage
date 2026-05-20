---
project: "GarageOps"
context_type: greenfield
created: 2026-05-19
updated: 2026-05-20
checkpoint:
  current_phase: 8
  phases_completed: [1, 2, 3, 4, 5, 6, 7]
  gray_areas_resolved:
    - topic: "pain category"
      decision: "data trapped + missing capability + decision paralysis + workflow friction (all four)"
    - topic: "insight / market gap"
      decision: "garage rental is too small / cheap per-unit to justify generic property-management SaaS"
    - topic: "primary persona scope"
      decision: "single named owner (single-tenant MVP, not multi-account / multi-landlord)"
    - topic: "auth model"
      decision: "email + password login; survives device changes and phone+desktop use"
    - topic: "roles"
      decision: "flat; only the owner in MVP — no assistant / viewer / multi-role"
    - topic: "MVP flow shape"
      decision: "dashboard-first — owner sees overdue / vacant / ending-soon on landing after setup + payment entry"
    - topic: "MVP timeline"
      decision: "3 weeks of after-hours work — user judged plausible"
    - topic: "v1 scope cuts"
      decision: "notes/event history → v2; monthly revenue total → v2. Three-state garage status (free/rented/problem) KEPT in MVP. Long-vacant + frequent-late detection KEPT in MVP."
    - topic: "secondary success"
      decision: "owner replaces Excel as source of truth within a month"
    - topic: "guardrails"
      decision: "(1) no silent data loss — records never disappear without explicit user action; (2) privacy — owner's data not accessible to anyone else"
  frs_drafted: 21
  quality_check_status: accepted
product_type: web-app
target_scale:
  users: small
  qps: low
  data_volume: small
timeline_budget:
  mvp_weeks: 3
  hard_deadline: 2026-06-30
  after_hours_only: true
---

# Shape notes — GarageOps

Source idea: `idea-notes.md` (Polish original).

## Vision & Problem Statement

A garage owner who manages multiple garages across one or more locations tracks rentals in Excel. Rental data sits there passively: overdue payments, vacant garages aging without notice, contracts approaching their end, and tenants with patterns of late payment are not surfaced until they translate into lost revenue. The owner has to manually scan the spreadsheet to detect any of this — and in practice they often don't, because the spreadsheet doesn't ask to be looked at.

The insight: garage rental units are too small and too cheap per-unit (in the Polish market, roughly 50–300 PLN/month) to justify generic property-management SaaS, which is priced and scoped for residential and commercial leases. Owners fall back to Excel, which works passively but cannot detect problems on its own. A focused tool that turns the same rental data into a small set of decisions the owner has to make today — *which garages are losing money, which tenants are slipping, which units have been empty too long* — captures the value that generic PM tools cannot price for.

## User & Persona

**Primary persona — the Owner.** A single named owner who manages a known portfolio of garages across one or more locations. They are the sole user of the tool; the MVP is single-tenant and does not anticipate multi-account or multi-landlord usage. They are responsible end-to-end: signing tenants, collecting payments, chasing overdue, deciding when to re-list a vacant unit, deciding when to part ways with a problem tenant. They currently keep everything in Excel and rely on memory + ad-hoc reminders.

The tool exists for *this* owner — not a property-management firm, not a co-managed portfolio, not a marketplace participant.

## Access Control

The owner signs in with an email and password. The same credentials give access from any device, so the owner can use the app on phone and desktop with one set of data.

Role model is **flat**: there is one role, the owner, and the owner has full read/write access to everything in their portfolio. No assistant role, no read-only viewer, no multi-landlord separation in MVP. If a real second-user need emerges later (e.g. an accountant who only needs reports), a roles layer can be added without changing the data model.

## Success Criteria

### Primary

- The owner opens GarageOps and, on the landing dashboard, sees the three signals that drive action: garages with **overdue payments**, garages that are **currently vacant**, and contracts **ending in the next 30 days**. From any of these, they can drill into the underlying garage / tenant and record an action (payment received, contract renewed, garage re-listed).

### Secondary

- The owner replaces Excel as the source of truth for the rental portfolio within a month of starting to use GarageOps. Excel either disappears or becomes archival.

### Guardrails

- **No silent data loss.** Contracts, payments, and tenants must never disappear without explicit user action. The owner is migrating from a spreadsheet they fully control — the moment GarageOps loses a record, trust collapses and the migration reverses.
- **Privacy.** The owner's rental data — tenant names, payment histories, contract terms — must not be accessible to anyone other than the owner. No shared links, no leaks via error pages or logs.

## User Stories

### US-01: Owner sees what needs action today on the dashboard

- **Given** an owner with at least one location, garage, and active rental contract recorded
- **When** they log into GarageOps
- **Then** they land on a dashboard that lists, in three sections:
  - Garages with **overdue payments** (with tenant name, amount, days overdue)
  - Garages that are **currently vacant** (with vacancy duration)
  - Contracts **ending in the next 30 days** (with tenant, garage, days remaining)

#### Acceptance Criteria
- The dashboard is the default landing view after login.
- Each signal has a friendly empty-state copy (no overdue → "No overdue payments." not "0 results").
- Each row is clickable and drills into the underlying garage / tenant / contract.
- Signals reflect the current data without requiring a manual refresh of the page.

## Functional Requirements

### Authentication
- FR-001: Owner can sign up with email + password. Priority: must-have
  > Socrates: Counter-argument considered: "Why have signup for a single-owner MVP? Could be hardcoded." Resolution: stands as written; email+password gives the owner control over their own credentials and avoids hardcoded secrets in source.
- FR-002: Owner can log in and out from any device with the same credentials. Priority: must-have
  > Socrates: Counter-argument considered: "Cross-device session adds work; phone-only could ship faster." Resolution: stands as written; mobile + desktop parity is required by the success criteria.

### Portfolio structure
- FR-003: Owner can add, rename, and archive a location. Priority: must-have
  > Socrates: Counter-argument considered: "'Location' could be a free-text field on garage, not a separate entity." Resolution: stands as written; grouping by location is load-bearing for the dashboard and matches the owner's mental model.
- FR-004: Owner can add a garage to a location with a label (e.g. "G-12") and a default monthly rent. Priority: must-have
  > Socrates: Counter-argument considered: "Rent belongs on the contract, not the garage." Resolution: stands as written; default rent on garage is a UX pre-fill for new contracts, not the authoritative rent — that lives on the contract.
- FR-005: Owner can view all garages grouped by location, with each garage's current status (free / rented / problem). Priority: must-have
  > Socrates: Counter-argument considered: "Status is derived from contracts — redundant flag." Resolution: stands as written; "problem" is owner-set (non-derivable), so the trio belongs together as the displayed status.
- FR-006: Owner can mark a garage as "problem" with a short free-text reason; owner clears the flag manually when resolved. Priority: must-have
  > Socrates: Counter-argument considered: "Problem state needs a state machine (who clears it, when, what triggers it) that may slip MVP." Resolution: REVISED — kept as a simple owner-toggled flag with free-text reason. No state machine, no auto-clear.

### Tenants
- FR-007: Owner can add a tenant with at minimum a name and contact info. Priority: must-have
  > Socrates: Counter-argument considered: "PL rentals may need PESEL/ID for legal contracts." Resolution: stands as written; the MVP doesn't handle formal legal documents. Owner can put ID in the contact field if they want; formal document handling is out of scope.
- FR-008: Owner can view a tenant's profile, including their current and past contracts. Priority: must-have
  > Socrates: Counter-argument considered: "Tenant profile rarely visited; the relationship lives on the contract." Resolution: stands as written; tenant profile is the drill-through target for FR-020 (frequent-late flagging) and is needed for tenants who rent multiple garages.

### Contracts
- FR-009: Owner can create a rental contract linking one tenant to one garage, with start date, a REQUIRED end date, and monthly rent. Priority: must-have
  > Socrates: Counter-argument considered: "Open-ended contracts are common in PL garage rental — end_date should be optional." Resolution: REVISED — `end_date` is REQUIRED (user override of the optional-default recommendation). Every contract has a planned end date in MVP; owners with month-to-month tenants pick a near-term end date and renew explicitly.
- FR-010: Owner can end a contract early when a tenant moves out before the planned end date. Priority: must-have
  > Socrates: Counter-argument considered: "End early vs end on time — one action or two?" Resolution: stands as written; one action — owner records the actual end date, which can be ≤ planned end_date.
- FR-011: Owner can view a garage's full rental history (all past + current contracts on that garage). Priority: must-have
  > Socrates: Counter-argument considered: "Rental history is mostly for problem analysis — defer to v2?" Resolution: stands as written; FR-020 and dashboard drill-through need history queryable from MVP onward.

### Payments
- FR-012: Owner can record a payment for a contract with amount, date, and an optional note. Multiple payments per contract-month are allowed (partial / cash payments). Priority: must-have
  > Socrates: Counter-argument considered: "Partial / cash payments common — single amount + note may not be enough." Resolution: stands as written; multiple payments per period are allowed (no uniqueness constraint), and the overdue rule (FR-013) sums payments within a period.
- FR-013: System derives "overdue" status per contract using this rule: each contract carries a `payment_day_of_month` (set when the contract is created) and a `grace_days` value (default 5). The contract is overdue if the sum of payments recorded for the current period (the calendar month containing `payment_day_of_month`) is less than the contract's monthly rent by `payment_day_of_month + grace_days`. Priority: must-have
  > Socrates: Counter-argument considered: "Overdue logic is under-specified (grace period, due day, partial-payment handling)." Resolution: REVISED — explicit rule pinned: per-contract `payment_day_of_month` + `grace_days` (default 5). Multiple payments in a period sum together for the overdue check.
- FR-014: Owner can view all current dues / overdue payments for one tenant and across the portfolio. Priority: must-have
  > Socrates: Counter-argument considered: "Per-tenant view duplicates the portfolio view." Resolution: stands as written; per-tenant is a drill-through filter on the same query, not a separate page.

### Dashboard
- FR-015: Owner sees, on a single landing screen, garages currently OVERDUE on payment. Priority: must-have
  > Socrates: Counter-argument considered: "What if overdue is empty on day 1? Empty dashboard kills first impression." Resolution: stands as written; empty-state copy in US-01 acceptance criteria addresses this ("No overdue payments." rather than "0 results").
- FR-016: Owner sees, on the same screen, garages currently VACANT (no active contract). Priority: must-have
  > Socrates: Counter-argument considered: "Some owners want some vacancy (seasonal); flagging adds noise." Resolution: stands as written; "vacant" is informational, not a problem flag. Long-vacant (FR-019) is the actionable variant.
- FR-017: Owner sees, on the same screen, contracts ENDING IN THE NEXT 30 DAYS. Priority: must-have
  > Socrates: Counter-argument considered: "30 days is arbitrary; auto-renewing contracts complicate this." Resolution: stands as written; 30 days is the default display window. Auto-renewal is out of scope — owners create a new contract manually when one ends (consistent with FR-009 requiring an explicit end_date).
- FR-018: Owner can drill from any dashboard signal into the underlying garage / tenant / contract. Priority: must-have
  > Socrates: Counter-argument considered: "'Drill' is implementation detail, not a real FR." Resolution: stands as written; drill-through is a user capability — without it the dashboard is a dead list. Acceptance criteria in US-01 spell out the behavior.

### Alerts & analysis
- FR-019: System flags garages that have been vacant longer than a configurable threshold (default 30 days). Priority: nice-to-have
  > Socrates: Counter-argument considered: "Cut entirely (already nice-to-have)?" Resolution: stands as written; remains nice-to-have. If the 3-week budget runs tight, this drops first.
- FR-020: System flags tenants with a pattern of late payments (≥ 2 overdue events in the last 6 months). Priority: must-have
  > Socrates: Counter-argument considered: "Late ≠ bad; flagging tenants risks bias. The 2-in-6 number is arbitrary." Resolution: stands as written; the flag is informational for the owner only, never visible to tenants. The 2-in-6 threshold is a default that can be tuned post-MVP — listed in Open Questions for downstream review.

### Data retention
- FR-021: Archive-only deletion semantics: archiving a tenant, ending a contract, or archiving a garage retains the underlying records (contracts, payments, history). MVP exposes no permanent-delete UI. Priority: must-have
  > Socrates: Counter-argument considered: "Archive-only means data grows forever; GDPR cost-of-deletion deferred." Resolution: stands as written for MVP; tenant-data-deletion-on-request (GDPR / RODO) is recorded in Open Questions for v2.

## Business Logic

GarageOps continuously surfaces the rental records that require owner action — overdue payments, vacant garages, soon-to-end contracts, problem-flagged units — ordered so that the owner can act on the most urgent first.

The inputs the rule consumes are the owner's portfolio data: garages and their status, tenants, contracts (with start date, end date, monthly rent, payment day-of-month), and recorded payments. From these inputs the rule derives a small set of action-worthy facts about each rental record — "this contract is overdue by N days", "this garage has been vacant for N days", "this contract ends in N days", "this garage is flagged as a problem". The product's job is to present these facts on a single landing screen, ordered by urgency, so the owner sees what to act on the moment they open the app.

The owner encounters the rule on the dashboard. The dashboard is the rule's output surface — three sections (overdue / vacant / ending) plus the problem-flagged subset. Every row is drillable so that the owner can take action (record a payment, end or extend a contract, re-list a garage, clear a problem flag) without leaving the urgency context.

## Non-Functional Requirements

- The product is usable on mobile in every core path. Adding a payment, viewing the dashboard, drilling into a contract, and editing tenant info are all reachable and operable on a phone-sized screen.
- The owner sees acknowledgement of any input within 1 second on the dashboard and on primary CRUD operations (add tenant, record payment, create contract, view garage); longer operations show continuous visible progress.
- The owner's rental data — tenant names, payment histories, contract terms — is not accessible to anyone other than the authenticated owner. No shared links, no leaks via error pages, server logs, or analytics.

## Non-Goals

- **No marketplace, no public garage listings.** GarageOps is the owner's internal tool. It never publishes garages to tenants or the public; there is no tenant-facing surface in MVP.
- **No online payments, no payment-gateway integrations (BLIK / bank transfer).** Payments are recorded manually after the owner observes them in their bank account or in cash. GarageOps is the ledger, not the cashier.
- **No e-signature, no automatic invoicing, no PDF generation.** Document handling and accounting artifacts sit outside MVP. Owner uses their existing tools.
- **No multi-tenant / multi-landlord SaaS shape.** Single-tenant per the persona decision — locked in. One owner per install / account.
- **No advanced accounting or financial analytics** (from idea-notes.md). GarageOps tracks payments and dues, not P&L, tax, depreciation, or cross-period analytics.
- **No SMS / email notifications, no AI / chatbot** (from idea-notes.md). The owner reads the dashboard themselves; the system does not push alerts to them or to tenants.
- **No integration with physical gates / smart locks** (from idea-notes.md). Access control to the physical garage is out of scope.
- **No role / permission system beyond the single owner role.** Locked from Phase 2.

## Open Questions

1. **Late-payment threshold tuning (FR-020)** — the "≥ 2 overdue events in 6 months" default is arbitrary. Owner to validate against real portfolio history after MVP launch.
2. **GDPR / RODO tenant-data-deletion (FR-021)** — MVP is archive-only. Add an explicit hard-delete-on-request flow for tenant personal data in v2.
3. **Grace-period default (FR-013)** — `grace_days = 5` is a starting value. May need adjustment after the owner observes real payment patterns.
4. **Auto-renewal semantics (FR-009, FR-017)** — MVP requires explicit contract creation per period. Whether auto-renewing contracts make sense in v2 is open.

## Phase 7 cross-check

All 6 applicable elements present (Access Control, Business Logic, Project artifacts, Timeline-cost ack, Non-Goals; Preserved behavior n/a for greenfield). `quality_check_status: accepted`. No gaps to mirror into Open Questions.
