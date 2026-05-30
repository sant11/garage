<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Owner Auth (Login / Logout) — Vaadin 25

- **Plan**: context/changes/owner-auth-signup-login/plan.md
- **Scope**: Phase 2 of 4
- **Date**: 2026-05-30
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Evidence

- Git scope (commit c674b77) touches exactly the 5 files the Phase 2 plan named — no extra files, none missing. No source changes since c674b77 (working tree edits are docs only).
- `SecurityConfig` migration matches the contract: `VaadinSecurityConfigurer` wired with `loginView(LoginView.class)`; `formLogin`, `csrf.disable()`, and the manual static-asset/`/login` matchers removed; `/actuator/health` carve-out kept tight (not broadened to `/actuator/**`); `PasswordEncoder` + in-memory `UserDetailsService` retained for this phase.
- CSRF re-enabled (configurer default) — settles the F-01 deferred debt.
- `LoginView` (`@AnonymousAllowed`, `@Route("login", autoLayout=false)`, `LoginForm.setAction("login")`, `BeforeEnterObserver` error handling), `MainLayout` (`AppLayout` + constructor-injected `AuthenticationContext.logout()`), and `HomeView` (`@Route("")`, `@PermitAll`) all match their contracts.
- Package-by-feature respected (security/, ui/); constructor injection only; tab indentation preserved.
- **Success criterion 2.2 verified live**: `mvnw.cmd test -Dtest=SecurityGatingTests` → Tests run: 3, Failures: 0, Errors: 0 (10.15s) against the real Vaadin chain. Context loading the Vaadin chain in mock-MVC mode also retires the plan's "frontend resources at context load" open risk.

## Findings

### F1 — Gating test couples to a dev plaintext documented only in an archived file

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/com/example/garageops/security/SecurityGatingTests.java:44
- **Detail**: `validOwnerLoginAuthenticates()` asserts login with plaintext `"owner-local-dev"`, which must BCrypt-match the hardcoded fallback hash in `SecurityConfig.java:55`. The comment points to "dev-credentials.md", but that file now lives only at `context/archive/2026-05-26-access-control-foundation/dev-credentials.md` (immutable archive) — the reference dangles. The coupling is proven green (3/3), but the only human-readable record of the pairing is in a folder readers are told not to touch; Phase 3/4 rework the credential source and could break this link silently.
- **Fix**: Update the comment to point at the live source of truth (the `garageops.owner.password-hash` fallback in `SecurityConfig.java` / `application.properties`) instead of the archived file.
- **Decision**: PENDING

### F2 — MainLayout @PermitAll is a required Vaadin detail the plan contract omitted

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/java/com/example/garageops/ui/MainLayout.java:24
- **Detail**: Plan contract #3 never mentioned an access annotation, but the implementation correctly adds `@PermitAll` with a javadoc explaining why: Vaadin denies a permitted child view if a parent layout in the navigation chain carries no access annotation. Correct, well-documented catch — not a defect. Flagged because it's a recurring Vaadin gotcha S-02+ layouts will hit again, and the plan was silent on it.
- **Fix**: No code change needed. Candidate for `/10x-lesson` — record "every parent layout in a Vaadin nav chain needs its own access annotation" as a recurring project rule.
- **Decision**: PENDING
