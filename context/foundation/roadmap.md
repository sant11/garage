---
project: GarageOps
version: 1
status: draft
created: 2026-05-26
updated: 2026-05-28
prd_version: 1
main_goal: speed
top_blocker: time
---

# Roadmap: GarageOps

> Derived from `context/foundation/prd.md` (v1) + auto-researched codebase baseline.
> Edit-in-place; archive when superseded.
> Slices below are listed in dependency order. The "At a glance" table is the index.

## Vision recap

A single garage owner tracks rentals in Excel, where overdue payments, aging-vacant units, and soon-to-end contracts sit passively and go unnoticed until they cost money. GarageOps turns the same rental data into a small set of decisions the owner has to make *today* — which garages are losing money, which tenants are slipping, which units have been empty too long — surfaced on one landing dashboard. It is a single-owner internal tool, not a marketplace and not a payment gateway: it is the ledger and the alarm, not the cashier.

## North star

**S-06: Owner lands on a dashboard showing overdue / vacant / ending-soon, each drillable** — this is the validation milestone, the single slice whose delivery proves the product's core hypothesis (passive spreadsheet data becomes the day's action list), tied to `main_goal: speed` because it is the whole reason the MVP ships.

> "North star" here means the smallest end-to-end slice whose successful delivery would prove the core product hypothesis — placed as early as its Prerequisites allow, because everything else only matters if this works. The dashboard's three signals each depend on portfolio, contract, and payment data, so the north star necessarily sits after those slices rather than first.

## At a glance

| ID    | Change ID                  | Outcome (user can …)                                              | Prerequisites | PRD refs                          | Status   |
| ----- | -------------------------- | ----------------------------------------------------------------- | ------------- | --------------------------------- | -------- |
| F-01  | access-control-foundation  | (foundation) Spring Security wired; all routes gated to login      | —             | Access Control, NFR-privacy       | ready    |
| F-02  | jpa-persistence-foundation | (foundation) JPA persistence + archive-only convention established | —             | FR-021, NFR-no-data-loss          | done     |
| S-01  | owner-auth-signup-login    | sign up, log in from any device, and log out                      | F-01          | FR-001, FR-002                    | proposed |
| S-02  | portfolio-locations-garages| manage locations & garages, see each garage's status              | S-01, F-02    | FR-003, FR-004, FR-005, FR-006, FR-021 | proposed |
| S-03  | tenant-management          | add tenants and view a tenant profile with contract history       | S-01, F-02    | FR-007, FR-008, FR-021            | proposed |
| S-04  | rental-contracts           | create / end contracts and view a garage's rental history         | S-02, S-03    | FR-009, FR-010, FR-011, FR-021    | proposed |
| S-05  | payments-and-overdue       | record payments and see dues / overdue per tenant & portfolio     | S-04          | FR-012, FR-013, FR-014            | proposed |
| S-06  | action-dashboard           | land on a dashboard of overdue / vacant / ending-soon, drillable  | S-04, S-05    | US-01, FR-015, FR-016, FR-017, FR-018 | proposed |
| S-07  | late-payer-flag            | see a frequent-late-payer flag on a tenant's profile              | S-05, S-03    | FR-020                            | proposed |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives in the dependency graph below; this table is the proposed reading order across parallel tracks.

| Stream | Theme               | Chain                                  | Note                                                                          |
| ------ | ------------------- | -------------------------------------- | ----------------------------------------------------------------------------- |
| A      | Identity gate       | `F-01` → `S-01`                        | Unblocks every gated slice; head of the user-visible chain.                   |
| B      | Portfolio & contracts | `F-02` → `S-02` / `S-03` → `S-04`    | `S-02`/`S-03` run in parallel; both also need `S-01` from Stream A. Critical path under `speed`. |
| C      | Insight surface     | `S-05` → `S-06` / `S-07`               | Joins Stream B at `S-04`. `S-06` (north star) and `S-07` run in parallel off `S-05`. |

## Baseline

What's already in place in the codebase as of 2026-05-26 (auto-researched + user-confirmed).
Foundations below assume these are present and do NOT re-scaffold them.

- **Frontend:** absent — no UI layer; no Thymeleaf/templates, no SPA. The view-layer choice was explicitly deferred at stack selection (`tech-stack.md`).
- **Backend / API:** partial — Spring Boot 4.0.6 app boots (`GarageopsApplication.java`); webmvc + actuator wired. Zero controllers/services/domain; only `/actuator/health`.
- **Data:** partial — Postgres + Flyway wired and tuned (`application.properties`, HikariCP), but the scaffold uses `spring-boot-starter-jdbc`. **Per user correction, the persistence layer will be JPA, not JDBC** (F-02 owns the swap). Only migration is `V1__init.sql` = a `deploy_smoke_test` table; no domain schema.
- **Auth:** absent — no Spring Security dependency or config. FR-001/FR-002 not started.
- **Deploy / infra:** present — `Dockerfile`, `railway.json`, GitHub Actions `deploy.yml` (auto-deploy on push to `main`); Railway platform decided and Phase A/B deployed.
- **Observability:** partial — Actuator `/health` only; no logging library beyond Spring defaults, no error tracking or metrics.

## Foundations

### F-01: Access-control foundation

- **Outcome:** (foundation) Spring Security on the classpath; password hashing + session login mechanism in place; all non-auth routes gated, unauthenticated visitors redirected to login.
- **Change ID:** access-control-foundation
- **PRD refs:** Access Control section, NFR (privacy — "not accessible to anyone other than the authenticated owner")
- **Unlocks:** S-01 (signup/login surface); gated access for S-02–S-07
- **Prerequisites:** —
- **Parallel with:** F-02
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Spring Security ceremony is flagged in `tech-stack.md` as known friction; sequenced first so the gating contract is proven before any data-bearing route is exposed. This is the privacy guardrail's load-bearing enabler.
- **Status:** ready

### F-02: JPA persistence & archive-only foundation

- **Outcome:** (foundation) Persistence layer on JPA (replacing the scaffolded `spring-boot-starter-jdbc`); base entity + repository conventions and the archive-only soft-delete pattern established; Flyway continues to own schema migrations.
- **Change ID:** jpa-persistence-foundation
- **PRD refs:** FR-021 (archive-only deletion semantics), NFR (no silent data loss guardrail)
- **Unlocks:** S-02, S-03, S-04 (all entity-backed slices); enforces the FR-021 archive-only convention so each slice inherits it rather than reinventing it
- **Prerequisites:** —
- **Parallel with:** F-01
- **Blockers:** —
- **Unknowns:** —
- **Risk:** the JDBC scaffold (and its `V1__init.sql` smoke table) must be swapped to JPA per the stack correction; doing it once as a foundation avoids each slice reinventing persistence. Keep it thin — the entities themselves land in their slices, not here. Over-broadening this into "build the whole schema" is the anti-pattern to avoid.
- **Status:** done

## Slices

### S-01: Owner authentication

- **Outcome:** Owner can sign up with email + password, log in from any device with the same credentials, and log out.
- **Change ID:** owner-auth-signup-login
- **PRD refs:** FR-001, FR-002
- **Prerequisites:** F-01
- **Parallel with:** F-02
- **Blockers:** —
- **Unknowns:** —
- **Risk:** first user-visible slice and the entry to every gated flow; thin (single owner, one-time signup) but proves the F-01 gating contract end-to-end before any data slice depends on it.
- **Status:** proposed

### S-02: Manage locations & garages

- **Outcome:** Owner can add, rename, and archive locations; add garages (label + default monthly rent) to a location; and view all garages grouped by location with each garage's status (free / problem; "rented" activates once contracts exist).
- **Change ID:** portfolio-locations-garages
- **PRD refs:** FR-003, FR-004, FR-005, FR-006, FR-021
- **Prerequisites:** S-01, F-02
- **Parallel with:** S-03
- **Blockers:** —
- **Unknowns:** —
- **Risk:** garage status "rented" (part of FR-005) is derived from active contracts (S-04); this slice ships free/problem and the rented state activates once S-04 lands. Archiving a location/garage exercises the FR-021 retain-underlying-records rule.
- **Status:** proposed

### S-03: Manage tenants

- **Outcome:** Owner can add a tenant (name + contact info) and view a tenant profile listing their current and past contracts.
- **Change ID:** tenant-management
- **PRD refs:** FR-007, FR-008, FR-021
- **Prerequisites:** S-01, F-02
- **Parallel with:** S-02
- **Blockers:** —
- **Unknowns:** —
- **Risk:** the profile's contract list is empty until S-04, but the profile page is also the drill-through target (FR-018) and the surface for the late-payer flag (S-07), so it is built now. Archiving a tenant exercises FR-021.
- **Status:** proposed

### S-04: Create & manage rental contracts

- **Outcome:** Owner can create a contract linking one tenant to one garage (start date, required end date, monthly rent, payment day-of-month), end a contract early on the actual move-out date, and view a garage's full rental history.
- **Change ID:** rental-contracts
- **PRD refs:** FR-009, FR-010, FR-011, FR-021
- **Prerequisites:** S-02, S-03
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** completes the garage "rented" status (FR-005) and populates tenant profiles (FR-008); the contract's required end-date and payment-day fields are exactly the inputs the dashboard and the overdue rule consume, so this slice's shape gates S-05 and S-06. Ending a contract retains its records (FR-021).
- **Status:** proposed

### S-05: Record payments & derive overdue

- **Outcome:** Owner can record payments against a contract (amount, date, optional note; multiple payments per period allowed) and view current dues / overdue per tenant and across the portfolio, with overdue derived per the FR-013 rule.
- **Change ID:** payments-and-overdue
- **PRD refs:** FR-012, FR-013, FR-014
- **Prerequisites:** S-04
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:**
  - The grace-period default (5 days, FR-013) is a starting value to revisit against real payment patterns — Owner: owner. Block: no.
- **Risk:** FR-013's overdue derivation (per-period payment-sum vs monthly rent by `payment_day + grace_days`) is the most load-bearing business rule in the product; getting it right here is what makes the dashboard's overdue signal trustworthy.
- **Status:** proposed

### S-06: Action dashboard

- **Outcome:** Owner lands, after login, on a dashboard listing garages overdue on payment, garages currently vacant, and contracts ending in the next 30 days — each row drillable into the underlying garage / tenant / contract.
- **Change ID:** action-dashboard
- **PRD refs:** US-01, FR-015, FR-016, FR-017, FR-018
- **Prerequisites:** S-04, S-05
- **Parallel with:** S-07
- **Blockers:** —
- **Unknowns:** —
- **Risk:** the north star — the validation milestone that proves the whole product hypothesis. Sequenced as early as its three signals' data sources allow, which is necessarily after contracts (S-04, for vacant + ending) and payments (S-05, for overdue). Empty-state copy per US-01 acceptance criteria matters here so a day-one empty dashboard still reads well.
- **Status:** proposed

### S-07: Frequent-late-payer flag

- **Outcome:** Owner sees, on a tenant's profile, a flag when that tenant has a pattern of late payments (≥ 2 overdue events in the last 6 months).
- **Change ID:** late-payer-flag
- **PRD refs:** FR-020
- **Prerequisites:** S-05, S-03
- **Parallel with:** S-06
- **Blockers:** —
- **Unknowns:**
  - The 2-in-6 threshold is an arbitrary default to validate against real portfolio history (PRD Open Q1) — Owner: owner. Block: no.
- **Risk:** informational and owner-only (never tenant-visible); depends on a history of overdue events existing, so it follows S-05. Lower urgency than the dashboard — runs parallel to S-06 or after it without affecting the north star.
- **Status:** proposed

## Backlog Handoff

| Roadmap ID | Change ID                  | Suggested issue title                                  | Ready for `/10x-plan` | Notes |
| ---------- | -------------------------- | ------------------------------------------------------ | --------------------- | ----- |
| F-01       | access-control-foundation  | Wire Spring Security + gate all routes to login        | yes                   | Run `/10x-plan access-control-foundation`. Parallel with F-02. |
| F-02       | jpa-persistence-foundation | Establish JPA persistence + archive-only convention    | yes                   | Run `/10x-plan jpa-persistence-foundation`. Swaps the JDBC scaffold. Parallel with F-01. |
| S-01       | owner-auth-signup-login    | Owner signup / login / logout                          | no                    | Needs F-01 |
| S-02       | portfolio-locations-garages| Manage locations & garages with status                 | no                    | Needs S-01, F-02 |
| S-03       | tenant-management          | Add tenants & tenant profile                            | no                    | Needs S-01, F-02; parallel with S-02 |
| S-04       | rental-contracts           | Create/end contracts & garage rental history           | no                    | Needs S-02, S-03 |
| S-05       | payments-and-overdue       | Record payments & derive overdue                        | no                    | Needs S-04 |
| S-06       | action-dashboard           | Action dashboard (overdue / vacant / ending-soon)      | no                    | Needs S-04, S-05 — north star |
| S-07       | late-payer-flag            | Frequent-late-payer flag on tenant profile             | no                    | Needs S-05, S-03; parallel with S-06 |

## Open Roadmap Questions

1. **Late-payment threshold tuning (FR-020)** — the "≥ 2 overdue events in 6 months" default is arbitrary. Owner: owner. Block: none (default ships with S-07; the flag is informational only).
2. **GDPR / RODO tenant-data hard-delete (FR-021)** — MVP is archive-only; an explicit hard-delete-on-request flow is v2. Owner: owner. Block: none (deferred past MVP).
3. **Grace-period default (FR-013)** — `grace_days = 5` is a starting value to tune against real payment patterns. Owner: owner. Block: none (affects S-05 tuning, not its delivery).
4. **Auto-renewal semantics (FR-009, FR-017)** — MVP requires explicit contract creation per period; whether auto-renewal makes sense is open. Owner: owner. Block: none (v2 question).

## Parked

- **Long-vacant flag (FR-019)** — Why parked: nice-to-have; the PRD states it "drops first" under budget pressure, and `main_goal: speed` parks non-essentials rather than sequencing them late. Revisit post-MVP if vacancy patterns warrant it.
- **Marketplace / public garage listings** — Why parked: PRD §Non-Goals — internal owner tool, no tenant-facing surface.
- **Online payments / payment-gateway integrations (BLIK, bank transfer)** — Why parked: PRD §Non-Goals — GarageOps is the ledger, payments are recorded manually.
- **E-signature / automatic invoicing / PDF generation** — Why parked: PRD §Non-Goals — document handling sits in the owner's existing tools.
- **Multi-tenant / multi-landlord SaaS shape** — Why parked: PRD §Non-Goals — single-tenant locked by the persona decision.
- **Advanced accounting / financial analytics (P&L, tax, depreciation)** — Why parked: PRD §Non-Goals — tracks payments and dues only.
- **SMS / email notifications, AI / chatbot** — Why parked: PRD §Non-Goals — the owner reads the dashboard; the system pushes nothing.
- **Physical gate / smart-lock integration** — Why parked: PRD §Non-Goals — physical access control is out of scope.
- **Roles / permissions beyond the single owner** — Why parked: PRD §Non-Goals — flat role model, locked in Access Control.
- **Notes / event-history feature** — Why parked: PRD §Non-Goals — deferred to v2.
- **Monthly revenue total on the dashboard** — Why parked: PRD §Non-Goals — v2; MVP dashboard is strictly action-worthy signals.

## Done

(Empty on first generation. `/10x-archive` appends entries here — and flips the matching item's `Status` to `done` — when a change whose `Change ID` matches a roadmap item is archived. Do NOT pre-populate.)

- **F-02: (foundation) JPA persistence + archive-only convention established** — Archived 2026-05-28 → `context/archive/2026-05-27-jpa-persistence-foundation/`. Lesson: —.
