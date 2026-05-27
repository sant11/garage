# Access-Control Foundation (F-01) — Plan Brief

> Full plan: `context/changes/access-control-foundation/plan.md`

## What & Why

Wire Spring Security so every GarageOps route is gated to the authenticated owner, with
unauthenticated visitors redirected to login. This is the privacy guardrail's load-bearing
enabler (PRD NFR-privacy): the gating contract must be proven before any data-bearing route
exists. It is a foundation, not the signup/login product — that's S-01.

## Starting Point

A bare Spring Boot 4.0.6 app: webmvc + actuator + Postgres/Flyway wired, zero controllers, no
security on the classpath, and a single `@SpringBootTest contextLoads` smoke test. `/actuator/health`
is the deploy healthcheck. No frontend (Thymeleaf deferred).

## Desired End State

The app boots with one `SecurityFilterChain`. Any route except `/actuator/health`, `/login`, and
static assets returns a 302 redirect to the generated login page when unauthenticated. The owner
logs in with BCrypt-hashed, config-sourced credentials. A MockMvc test pins the gating contract;
the existing smoke test stays green.

## Key Decisions Made

| Decision                  | Choice                                              | Why (1 sentence)                                                              | Source |
| ------------------------- | --------------------------------------------------- | ----------------------------------------------------------------------------- | ------ |
| Owner identity store      | In-memory owner from config                         | Proves login+gating now, stays independent of F-02 (JPA), swappable in S-01.  | Plan   |
| Credential source         | Env vars (`OWNER_USERNAME`/`OWNER_PASSWORD_HASH`) + dev fallback | No secret in source; mirrors existing `PG*` env pattern; prod-safe.   | Plan   |
| Public routes             | `/actuator/health` + `/login` + static public; all else gated | Keeps Railway healthcheck working; gates all data per privacy NFR.  | Plan   |
| CSRF                      | **Disabled**                                        | Owner's explicit choice; flagged for S-01 to re-enable before real forms.     | Plan   |
| Login surface             | Spring's auto-generated `/login` page               | Zero UI work; proves redirect+gating; S-01 swaps in a styled Thymeleaf page.  | Plan   |
| Test coverage             | MockMvc gating test + keep `contextLoads`           | Locks the load-bearing gating behavior so later slices can't break it silently. | Plan   |
| Scope line vs S-01        | Infra + gating only                                 | Signup, DB user store, logout UX, styled page all belong to S-01.             | Plan   |

## Scope

**In scope:** security starter; `SecurityConfig` (filter chain, BCrypt encoder, in-memory owner);
public carve-out for health/login/static; CSRF disabled; config-sourced owner credentials;
`spring-security-test` + a MockMvc gating test.

**Out of scope:** signup flow, DB-backed `UserDetailsService`, `User` entity (F-02/S-01), styled
login page, logout/session UX, re-enabling CSRF, any new domain route.

## Architecture / Approach

One `@Configuration @EnableWebSecurity` class in a new `com.example.garageops.security` package
contributes three beans — `SecurityFilterChain` (Security 7 lambda DSL: `authorizeHttpRequests` +
`requestMatchers` carve-out + `formLogin` + `csrf.disable()`), `BCryptPasswordEncoder`, and an
`InMemoryUserDetailsManager` holding one config-built owner. Defining our own filter chain +
user store makes Boot's default-user autoconfig back off; no autoconfig exclusion is needed.
Security autoconfig stays ON in tests (DataSource/Flyway exclusions unchanged) so the gating test
exercises the real chain.

## Phases at a Glance

| Phase                  | What it delivers                                              | Key risk                                                        |
| ---------------------- | ------------------------------------------------------------ | --------------------------------------------------------------- |
| 1. Security wiring     | Starter + `SecurityConfig`; app boots gated, owner can log in, `/health` public | Forgetting the health carve-out breaks the Railway deploy.      |
| 2. Lock with tests     | `spring-security-test` + MockMvc gating test; `contextLoads` stays green | Test must hit the real `SecurityConfig`, not a mocked chain.    |

**Prerequisites:** none (F-01 is the head of the dependency graph; parallel with F-02). JDK 21 on
`JAVA_HOME`. Set `OWNER_*` env vars in Railway before the deployed app uses auth.
**Estimated effort:** ~1 session across 2 phases.

## Open Risks & Assumptions

- **CSRF disabled** — leaves the session/form-login app exposed to CSRF; S-01 must re-enable
  before shipping real forms (re-enabling later breaks every interim form).
- **In-memory owner is a placeholder** — S-01 swaps the `UserDetailsService` for a DB-backed one;
  the filter chain should not need to change.
- Assumes the Boot 4.0.6 parent manages Spring Security 7.x and the starter artifactId is
  unchanged (`spring-boot-starter-security`) — verified against managed coordinates.

## Success Criteria (Summary)

- Unauthenticated access to any gated route redirects to `/login`; the owner can log in.
- `/actuator/health` returns 200 without auth (deploy healthcheck intact).
- `mvnw.cmd verify` passes, including a test that locks the gating contract.
