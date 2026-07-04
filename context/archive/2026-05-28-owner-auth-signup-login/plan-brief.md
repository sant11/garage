# Owner Auth (Login / Logout) — Plan Brief

> Full plan: `context/changes/owner-auth-signup-login/plan.md`

## What & Why

S-01 is the first user-visible slice and the first **Vaadin Flow 25** code in the repo. It replaces the F-01 placeholder (an in-memory, config-driven owner behind Spring's auto-generated `/login`) with a real, **DB-backed owner login fronted by Vaadin**, and lays down the Vaadin platform (deps, production build, app-shell, security integration) that every later slice (S-02–S-07) inherits. FR-001/FR-002: the owner logs in and out from any device with one set of credentials.

## Starting Point

Spring Security is wired (F-01): one `SecurityFilterChain` with `formLogin` + `csrf.disable()`, a BCrypt encoder, and an `InMemoryUserDetailsManager` owner from `OWNER_*` env vars. JPA conventions exist (F-02): `BaseEntity`/`ArchivableEntity`, Flyway-owned schema (`ddl-auto=validate`, only `V1__init.sql`). No frontend, no Vaadin, no DB test harness (tests are DB-free by convention).

## Desired End State

An unauthenticated visitor is redirected to a Vaadin `LoginView`; signing in with the owner credentials lands on a placeholder `HomeView` inside a reusable `MainLayout` app-shell, with a working logout. The owner is a row in a `users` table, provisioned idempotently on startup from `OWNER_*` env vars (no signup screen). CSRF is active (Vaadin-managed), the app deploys to Railway with a real frontend bundle, and the DB-free test suite still green-locks the gating contract.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Owner provisioning | Idempotent env-var bootstrap runner; **no signup UI** | Single-owner internal tool — no public registration surface to hijack; reuses F-01's env pattern | Plan |
| FR-001 "sign up" | Documented deviation (provisioned, not self-registered) | Owner still controls credentials via the env-provided BCrypt hash | Plan |
| User data model | `OwnerAccount extends BaseEntity` (not archivable) | FR-021 archive-only is for portfolio records, not the single login identity | Plan |
| Auth feature scope | Login + logout only | No change-password / reset (email is a PRD non-goal) / remember-me | Plan |
| Login UI | Vaadin `LoginForm` in an `@AnonymousAllowed` `@Route("login")` view | Canonical, mobile-friendly, posts straight to Spring form-login | Plan |
| Post-login landing | Minimal `MainLayout` app-shell + `@PermitAll` `HomeView` | Establishes the shell + logout pattern once for S-02–S-07 | Plan |
| Security integration | `VaadinSecurityConfigurer` (v25), CSRF re-enabled | v25 API; settles F-01's deferred CSRF debt with no manual wiring | Plan |
| Deploy scope | Wire production profile + Dockerfile `-Pproduction`, verify on Railway | First Vaadin slice must prove the frontend build deploys; FR-002 implies a real deployment | Plan |
| Test depth | MockMvc gating + DB-free unit tests; **no real-DB / UI test lib** | Repo has no DB test harness; "Module 3 owns test strategy" | Plan |
| Vaadin version | 25.1.6 | Only line aligned with Spring Boot 4 / Java 21 (24 targets SB3) | Plan |

## Scope

**In scope:** Vaadin 25 deps + production build + Dockerfile; `VaadinSecurityConfigurer` migration + CSRF re-enable; Vaadin `LoginView`, `MainLayout` shell, placeholder `HomeView`, logout; `OwnerAccount` entity + repository + Flyway V2 `users` table; repo-backed `UserDetailsService`; idempotent owner bootstrap; DB-free unit + gating tests; Railway deploy verification.

**Out of scope:** signup/self-registration UI; change/reset password; remember-me; real-DB automated persistence test (Testcontainers/H2); Vaadin UI test libs; any domain views/navigation beyond a placeholder home; owner archivability; multi-tenant scoping.

## Architecture / Approach

Plain `@Bean SecurityFilterChain` composes an explicit `/actuator/health` permit rule with `http.with(VaadinSecurityConfigurer.vaadin(), cfg -> cfg.loginView(LoginView.class))`. Views are gated by annotations (`@AnonymousAllowed` login, `@PermitAll` home). Authentication flows `LoginForm → /login → DaoAuthenticationProvider → repo-backed UserDetailsService → users table`; logout via the shared `AuthenticationContext`. The owner row is seeded by an idempotent `ApplicationRunner`. New code is package-by-feature: `security`, `account` (identity), `ui` (shell/home).

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Vaadin platform & production build | Vaadin 25 deps, maven plugin + `production` profile, Dockerfile `-Pproduction` | Frontend build / Node install inside Docker |
| 2. Auth UI + `VaadinSecurityConfigurer` | Login/home/logout on Vaadin, CSRF re-enabled (in-memory owner still) | Locking everyone out during the configurer migration |
| 3. DB-backed owner store + bootstrap | `users` table, entity/repo, repo-backed `UserDetailsService`, idempotent seed | `ddl-auto=validate` entity/schema mismatch; seed idempotency |
| 4. Lock tests + verify deploy | DB-free unit + gating tests; Railway cross-device login | Vaadin/`@SpringBootTest` context load; deploy |

**Prerequisites:** F-01 (done), F-02 (done); reachable Postgres for Phase 3 manual checks; Railway env vars for Phase 4.
**Estimated effort:** ~3–4 focused sessions across the four phases.

## Open Risks & Assumptions

- Vaadin's autoconfig may need `prepare-frontend` to have run for `@SpringBootTest` to load — mitigated by running via the full `verify` lifecycle; confirm in Phase 2.
- Docker `build-frontend` assumes outbound network for Node install (already true in the build stage).
- No automated proof the V2 migration matches the entity until Module 3 — `ddl-auto=validate` fails fast at boot as the safety net.

## Success Criteria (Summary)

- Owner is redirected to a Vaadin login, signs in, reaches the home shell, and logs out — from any device (FR-002).
- Authentication is DB-backed; the owner is provisioned idempotently; CSRF is on; `/actuator/health` stays public.
- `mvnw verify` and the production build pass; the app deploys and serves login on Railway.
