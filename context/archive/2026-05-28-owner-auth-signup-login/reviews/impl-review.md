<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Owner Auth (Login / Logout) — Vaadin 25

- **Plan**: context/changes/owner-auth-signup-login/plan.md
- **Scope**: Phases 1–4 of 4 (full plan)
- **Date**: 2026-05-30
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

Automated success criteria re-run during review: `mvnw test` → 14 tests, 0 failures, BUILD SUCCESS (was 13 before the F1 guard test was added).

## Findings

### F1 — Committed dev BCrypt hash is a silent production fallback

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: application.properties:18, OwnerBootstrap.java:37
- **Detail**: The same local-dev BCrypt hash was the fallback in both `application.properties` and the `@Value` default in `OwnerBootstrap`. If `OWNER_PASSWORD_HASH` were unset in production, the bootstrap would silently seed the owner with a publicly-known committed credential, with no fail-fast guard.
- **Fix B (chosen)**: Fail fast — refuse to seed with the built-in dev hash under the `production` profile, and activate that profile on the deployed app.
  - Extracted the dev hash to `OwnerBootstrap.DEFAULT_DEV_PASSWORD_HASH`; injected `Environment`; `run()` throws `IllegalStateException` when `passwordHash == DEFAULT_DEV_PASSWORD_HASH && environment.matchesProfiles("production")`.
  - `Dockerfile` runtime stage sets `ENV SPRING_PROFILES_ACTIVE=production` so the guard is live in deployment; local dev (no profile) keeps the fallback.
  - Added `OwnerBootstrapTests.abortsWhenSeedingWithDevFallbackHashUnderProductionProfile`.
- **Decision**: FIXED via Fix B

### F2 — ObjectProvider indirection not described in the plan

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: OwnerDetailsService.java:31, OwnerBootstrap.java:46
- **Detail**: Both inject `ObjectProvider<OwnerAccountRepository>` rather than the repository directly (plan said inject the repository / a plain `ApplicationRunner`). Deliberate, documented adaptation so the beans stay constructible in the DB-free `SecurityGatingTests` context. Functionally correct.
- **Decision**: ACCEPTED — justified and documented; code and plan unchanged.

### F3 — existsByUsername declared but unused

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: OwnerAccountRepository.java
- **Detail**: `existsByUsername` was declared but the bootstrap uses `count()`. The repository javadoc already documented only `findByUsername` + `count`.
- **Fix**: Removed the unused `existsByUsername` method.
- **Decision**: FIXED
