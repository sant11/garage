<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Access-Control Foundation (F-01)

- **Plan**: context/changes/access-control-foundation/plan.md
- **Scope**: Full plan — Phases 1 & 2 of 2
- **Date**: 2026-05-27
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Automated verification: `mvnw.cmd verify` → BUILD SUCCESS; 4 tests run, 0 failures
(`GarageopsApplicationTests` contextLoads + 3 `SecurityGatingTests` cases). All five
planned changes implemented as described. The `/actuator/health`-only carve-out (never
blanket `/actuator/**`) holds; the matched BCrypt pair is consistent across
`application.properties`, `SecurityConfig`, the test, and `dev-credentials.md`.

## Findings

### F1 — Silent prod fallback to repo-published owner credentials

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: SecurityConfig.java:53 / application.properties:15
- **Detail**: If `OWNER_PASSWORD_HASH` / `OWNER_USERNAME` are unset in a deployed
  environment, the app boots on the local-dev fallback hash — credentials whose
  plaintext (`owner` / `owner-local-dev`) is published in the repo at
  `dev-credentials.md`. No fail-fast guard, no startup warning; the only mitigation is
  the prose reminder in the plan's Migration Notes. A misconfigured deploy silently
  leaves the gate openable with public credentials.
- **Fix A ⭐ Recommended**: Bind the fallback only under a local/dev profile so a
  non-local profile has no fallback and fails fast on the missing env var.
  - Strength: Prod cannot boot with the public dev hash; gating test still runs locally.
  - Tradeoff: Touches profile config now; small added surface this slice kept minimal.
  - Confidence: MED — clean Spring idiom, but the test runs with "No active profile set";
    verify the test still picks up the dev credential under the new arrangement.
  - Blind spot: Haven't traced how S-01 injects the real user; it replaces this
    `UserDetailsService` anyway.
- **Fix B**: Accept as risk for this slice, track against S-01.
  - Strength: Honors foundation-only scope; no domain data behind the gate yet, and S-01
    rewires this exact code with a DB-backed store.
  - Tradeoff: Relies on a human setting `OWNER_*` before first real deploy; risk lives
    only in prose.
  - Confidence: HIGH — consistent with the plan's placeholder intent.
  - Blind spot: F-01 may be deployed (healthcheck wiring) before S-01 lands.
- **Decision**: PENDING

### F2 — Dev fallback hash duplicated in two files

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: SecurityConfig.java:53 and application.properties:15
- **Detail**: The fallback BCrypt hash appears both as an inline `@Value` default in
  `SecurityConfig` and as the env-var default in `application.properties`. On rotation,
  both must change in lockstep or they silently diverge.
- **Fix**: Drop the inline `@Value` default in `SecurityConfig` (use
  `${garageops.owner.password-hash}` with no default) so `application.properties` is the
  single source of the fallback.
- **Decision**: PENDING

### F3 — CSRF disabled (confirmed documented, accepted risk)

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: SecurityConfig.java:35-36
- **Detail**: `csrf.disable()` matches the plan's explicit decision. The in-code comment
  and the plan's Risks section both flag S-01 to re-enable CSRF before shipping real
  forms. Confirming the trail is intact; re-enabling later is a breaking change for any
  interim form.
- **Fix**: No code change. Ensure the CSRF re-enable TODO is carried into the S-01 change
  so it isn't lost when this plan is archived.
- **Decision**: PENDING
