# Test Plan

> Phased test rollout for this project. Strategy is frozen at the top
> (§1–§5); cookbook patterns at the bottom (§6) fill in as phases ship.
> Read before writing any new test.
>
> Refresh: re-run `/10x-test-plan --refresh` when stale (see §8).
>
> Last updated: 2026-06-03

## 1. Strategy

Tests follow three non-negotiable principles for this project:

1. **Cost × signal.** The cheapest test that gives a real signal for the
   risk wins. Do not promote to e2e because e2e "feels safer." Do not put a
   vision model on top of a deterministic visual diff that already catches
   the regression.
2. **User concerns are first-class evidence.** Risks anchored in "the team
   is worried about X, and the failure would surface somewhere in <area>"
   carry the same weight as PRD lines or hot-spot data.
3. **Risks are scenarios, not code locations.** This plan documents *what
   could fail* and *why we believe it's likely* — drawn from documents,
   interview, and codebase *signal* (churn, structure, test base). It does
   NOT claim to know which line owns the failure. That knowledge is
   produced by `/10x-research` during each rollout phase. If the plan and
   research disagree about where the failure lives, research is the
   ground truth.

Hot-spot scope used for likelihood weighting: `src/` (main + test Java,
`src/main/resources`); docs, archive, and build output excluded.

## 2. Risk Map

The top failure scenarios this project must protect against, ordered by
risk = impact × likelihood. Risks are failure scenarios in user / business
terms, not test names. The Source column cites the *evidence that surfaced
this risk* — never a specific file as "where the failure lives" (that is
research's job, see §1 principle #3).

| # | Risk (failure scenario) | Impact | Likelihood | Source (evidence — not anchor) |
|---|---|---|---|---|
| 1 | **Overdue false-negative** — a contract is shown paid / not-overdue when it is actually overdue; the owner stops chasing money they are owed. | High | High | interview Q1, Q4; PRD FR-013, US-01 |
| 2 | **Overdue boundary / timezone error** — the period window or `payment_day + grace_days` is computed off-by-one or in the wrong zone; the overdue flag fires a day early or late. | High | High | interview Q2, Q3; PRD FR-013 |
| 3 | **Partial-payment mis-sum** — multiple payments within one period do not sum correctly against monthly rent; a fully-paid contract reads overdue, or a half-paid one reads paid. | High | Medium | interview Q3; PRD FR-012, FR-013 |
| 4 | **Archive-retention violation** — archiving a location, garage, or tenant drops or orphans the underlying contracts/payments (or a delete hard-cascades); silent data loss reverses the migration from Excel. | High | Medium | PRD FR-021, NFR (no silent data loss); AGENTS hard rule (archive-only) |
| 5 | **Domain route not owner-gated** — a new view or query ships ungated (view access is annotation-driven and easy to forget); owner data reaches an unauthenticated visitor or leaks via an error page. | High | Medium | PRD NFR (privacy), Access Control; archive `2026-05-28-owner-auth-signup-login/plan.md` discovery (view access is annotation-driven) |
| 6 | **Dashboard vacant / ending-soon signal wrong** — a garage with an active contract reads vacant, an ended-early contract is not reflected, or the 30-day ending window is off; the dashboard misleads the owner. | Medium | Medium | PRD FR-016, FR-017, FR-010, US-01 |

**Impact × Likelihood rubric.** Both axes scored coarse High / Medium / Low.
R1–R3 decompose the single most load-bearing rule (FR-013): the interview
weighted overdue in 4 of 5 answers, and each row needs a distinct test
oracle and data set (missing money vs. annoying a paying tenant vs. summing
logic), so they are kept separate rather than merged. R5 is the mandatory
abuse/security row; the product is single-owner, so it collapses from
cross-user IDOR to "is the route gated at all, and does the error page leak
owner data." Hot-spot churn (`account/`, `security/`, `persistence/`) is the
just-shipped auth foundation, not where these risks live — likelihood for
R1–R6 is weighted by the roadmap (the domain is still ahead) and the
interview, not by current churn.

### Risk Response Guidance

| Risk | What would prove protection | Must challenge | Context `/10x-research` must ground | Likely cheapest layer | Anti-pattern to avoid |
|------|-----------------------------|----------------|--------------------------------------|-----------------------|-----------------------|
| #1 | Given a period whose payments sum to less than rent past `payment_day + grace_days`, the engine returns overdue; given a full on-time payment, not-overdue. | "A passing happy-path (full on-time payment) implies the calc is correct." | Where the overdue decision is computed; what "current period" means; whether the calc is a pure function or DB-coupled. | unit (pure) | Oracle lifted from the implementation's own calc (tautology) — derive expected values from FR-013 worked examples, not the code. |
| #2 | Boundary cases (payment exactly on the due day, on the grace-end day, across a month boundary, under DST/zone shifts) classify identically regardless of server time zone. | "Tests pass in CI's time zone, therefore the calc is correct." | Which clock/zone the period boundary uses; whether `today` is injectable. | unit (injected clock) | A single fixed-zone test; relying on the system-default time zone. |
| #3 | Two partial payments summing to rent ⇒ not overdue; the same two summing short ⇒ overdue. | "One payment per period is the only case worth testing." | How payments are aggregated within a period. | unit | Testing only the single-full-payment path. |
| #4 | After archiving a parent, its contracts and payments still exist and remain queryable; no row is hard-deleted. | "`archived_at` is set, therefore the data is safe." | Archive cascade behaviour; how children are fetched after a parent is archived. | integration (real DB) | Asserting only the parent's archived flag; never verifying children survive and stay queryable. |
| #5 | An anonymous request to a new domain route redirects to login and returns no owner data in the body or the error page. | "An authenticated happy-path test implies the anonymous path is blocked." | How new views inherit gating; what an error page renders. | integration | Testing only the logged-in path; never asserting the denied/redirect path or error-page leakage. |
| #6 | A garage with an active contract is not vacant; an ended (or ended-early) contract flips it vacant; the ending-soon list matches the 30-day boundary exactly. | "No active-contract row means vacant," ignoring ended-early and future-dated contracts. | How vacancy and the 30-day window are derived from contract dates. | integration | Zero-contract-only fixtures; ignoring the ended-early edge. |

## 3. Phased Rollout

Each row is a discrete rollout phase that will open its own change folder
via `/10x-new`. Status moves left-to-right through the values below; the
orchestrator updates Status as artifacts appear on disk.

| # | Phase name | Goal (one line) | Risks covered | Test types | Status | Change folder |
|---|---|---|---|---|---|---|
| 1 | Overdue engine — pure-unit coverage | Prove the FR-013 derivation is correct and zone-stable; force the calc to be an extractable, dependency-free unit. | #1, #2, #3 | unit (pure, injected clock) | not started | — |
| 2 | Domain integration harness + retention & gating | Stand up the deferred real-DB test harness; prove archiving never drops children and new domain routes are owner-gated and leak nothing. | #4, #5 | integration (real DB), security gating | not started | — |
| 3 | Dashboard signals — thin e2e + selective visual | Prove the north-star dashboard reflects the derived overdue/vacant/ending signals end-to-end and empty-states read friendly (US-01). | #6, #1 (wiring) | slice/e2e (one critical flow) + selective multimodal review (one screen) | not started | — |
| 4 | Quality-gates wiring | Lock the floor in CI: lint/build/unit/integration required, e2e-on-critical-flow gated, and close the `-DskipTests` gap in the Docker build. | cross-cutting | gates | not started | — |

**Status vocabulary** (fixed — parser literals):

| Value | Meaning |
|---|---|
| `not started` | No change folder for this rollout phase yet. |
| `change opened` | `context/changes/<id>/` exists with `change.md`; research not done. |
| `researched` | `research.md` exists in the change folder. |
| `planned` | `plan.md` exists with a `## Progress` section. |
| `implementing` | Progress section has at least one `[x]` and at least one `[ ]`. |
| `complete` | Progress section is fully `[x]`. |

## 4. Stack

The classic test base for this project. AI-native tools (if any) carry a
`checked:` date so future readers can see which lines need re-verification.
Test-base profile (2026-06-03): **sparse** — a runner is configured
(`spring-boot-starter-webmvc-test`, JUnit 5) and four real test files plus a
smoke test exist, all clustered in the auth foundation (`account/`,
`security/`, `persistence/`); the entire domain is unbuilt and untested.

| Layer | Tool | Version | Notes |
|---|---|---|---|
| unit + integration | JUnit 5 (`spring-boot-starter-webmvc-test`) | Boot 4.0.6 | Already wired. Test classes end in `Tests`. Suite is DB-free today (JPA autoconfig excluded in the test profile). |
| real-DB integration | none yet — see §3 Phase 2 | — | Testcontainers vs H2 decision deferred by S-01; Phase 2 makes the call. Postgres + Flyway in prod, so Testcontainers-Postgres is the fidelity-preserving candidate. |
| UI / e2e | none yet — see §3 Phase 3 | — | UI is Vaadin Flow 25 (server-side Java). Karibu-Testing (browserless) vs Playwright is a Phase 3 decision; no Playwright MCP in this session. |
| mocking | Mockito (transitive via starter) | — | Used DB-free in `OwnerBootstrapTests`/`SecurityGatingTests`; mock at the boundary, not internal logic. |
| (optional) AI-native | multimodal visual review — checked: 2026-06-03 | n/a | **When NOT to use:** never on the overdue/vacant logic (deterministic — use units/integration); reserved for 1 screen of dashboard empty-state copy in Phase 3. |

**Stack grounding tools (current session):**
- Docs: Vaadin MCP available — can ground Vaadin 25 view/security and test-utility APIs for Phase 2/3; Context7 / framework-docs MCP not available in current session; checked: 2026-06-03
- Search: web search (WebSearch/WebFetch) available — for current AI-native and Karibu/Testcontainers status; Exa.ai not available in current session; checked: 2026-06-03
- Runtime/browser: no Playwright/browser MCP in current session — e2e tooling choice deferred to §3 Phase 3; checked: 2026-06-03
- Provider/platform: Railway MCP available (logs, metrics, deploy status) — relevant to §5 pre-prod smoke / gate verification; live deploy is Railway (`railway.json`), not the Fly.io note in `tech-stack.md`; checked: 2026-06-03

## 5. Quality Gates

The full set of gates that must pass before a change reaches production.
"Required after §3 Phase <N>" means the gate is enforced once that rollout
phase lands; before that, the gate is `planned`.

| Gate | Where | Required? | Catches |
|---|---|---|---|
| compile + build | local + CI (`mvnw verify`) | required | syntactic drift, broken wiring |
| schema/entity validation (`ddl-auto=validate`) | app boot | required | Flyway migration vs entity mismatch (fail-fast at boot) |
| unit + integration | local + CI | required after §3 Phase 1 | overdue/period logic and retention/gating regressions |
| e2e on critical flow (dashboard) | CI on PR | required after §3 Phase 3 | broken north-star dashboard path |
| post-edit hook | local (agent loop) | recommended (configured in a later Module-3 lesson) | regressions at edit time |
| multimodal visual review | CI on PR | optional after §3 Phase 3 | empty-state/copy issues on the dashboard a diff misses |
| pre-prod smoke | between merge and prod (Railway) | optional | environment-specific failures on deploy |

The CI `deploy.yml` currently builds the Docker image with `-DskipTests` —
tests do not gate deploys today. §3 Phase 4 closes that gap.

## 6. Cookbook Patterns

How to add new tests in this project. Each sub-section is filled in once the
relevant rollout phase ships; before that, it reads "TBD — see §3 Phase <N>."

### 6.1 Adding a unit test (overdue / domain logic)

- TBD — see §3 Phase 1 (FR-013 overdue derivation: false-negative,
  boundary/timezone, partial-payment summing). Will name the location,
  naming pattern, the injected-clock approach, a reference test, and the run
  command.

### 6.2 Adding an integration test (real DB + archive retention)

- TBD — see §3 Phase 2 (archive-retention: children survive and stay
  queryable). Will name the real-DB harness chosen (Testcontainers vs H2),
  the mocking policy (boundary only), a reference test, and the run command.

### 6.3 Adding a route-gating / privacy test

- TBD — see §3 Phase 2 (new domain route denies anonymous and leaks nothing
  via the error page). Builds on `SecurityGatingTests` without re-litigating
  the S-01 auth foundation (see §7).

### 6.4 Adding a dashboard signal test (vacant / ending-soon / overdue wiring)

- TBD — see §3 Phase 3 (one critical end-to-end flow proving the dashboard
  reflects derived signals; selective empty-state visual review).

### 6.5 Per-rollout-phase notes

(Optional. After each phase lands, `/10x-implement` appends a 2–3 line note
here capturing anything surprising the phase taught.)

## 7. What We Deliberately Don't Test

Exclusions agreed during the rollout (Phase 2 interview, Q5). Future
contributors should respect these unless the underlying assumption changes.

- **Auth foundation (S-01)** — `SecurityGatingTests` and the bootstrap
  idempotency tests already cover signup/login/logout and owner
  provisioning. Do not re-litigate them. Re-evaluate only if the auth model
  changes (e.g. a second role is added). (Source: Phase 2 interview Q5.)
- **Vaadin component rendering / UI snapshot tests** — break on layout
  tweaks and catch nothing real. Re-evaluate only if a screen develops
  complex client-side state. (Source: cost × signal; §1 principle #1.)
- **Nice-to-haves** — FR-019 (long-vacant flag) and FR-020 threshold tuning
  are low-impact defaults; defer test budget until they ship and matter.
  (Source: PRD priorities; roadmap Parked/Open Questions.)
- **Non-goals** — marketplace, online payments/gateways, e-signature,
  multi-tenant scoping do not exist to test (PRD Non-Goals).

## 8. Freshness Ledger

- Strategy (§1–§5) last reviewed: 2026-06-03
- Stack versions last verified: 2026-06-03
- AI-native tool references last verified: 2026-06-03

Refresh (`/10x-test-plan --refresh`) when:

- a new top-3 risk surfaces from the roadmap or archive,
- a recommended tool's `checked:` date is older than three months,
- the project's tech stack changes (new framework, new test runner),
- §7 negative-space no longer matches what the team believes.
