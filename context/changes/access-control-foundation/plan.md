# Access-Control Foundation (F-01) Implementation Plan

## Overview

Wire Spring Security into the GarageOps Spring Boot 4 app so that **every route is gated
to the authenticated owner**, with unauthenticated visitors redirected to a login page.
Establish BCrypt password hashing and a session/form-login mechanism backed by a
config-driven **in-memory owner** placeholder. This is the privacy guardrail's load-bearing
enabler (PRD NFR-privacy): no data-bearing route is exposed before the gating contract is proven.

It is a *foundation* slice. It delivers security infrastructure and the gating contract —
not the user-visible signup/login product. The DB-backed user store, signup flow, logout UX,
and styled login page belong to **S-01 (`owner-auth-signup-login`)**, which depends on this change.

## Current State Analysis

- **No security at all.** `pom.xml` (lines 32–74) declares webmvc, actuator, jdbc, flyway,
  devtools, webmvc-test — no `spring-boot-starter-security`. `GarageopsApplication.java` is a
  bare `@SpringBootApplication`; there are zero controllers, services, or config classes.
- **Actuator `/health` is load-bearing for deploy.** `application.properties:7-9` exposes only
  `health`; the Railway container healthcheck / Actuator probes hit `/actuator/health`. Spring
  Security secures all endpoints by default, so `/actuator/health` MUST get an explicit public
  carve-out or the deploy is marked unhealthy.
- **No frontend.** No Thymeleaf, no templates (the view-layer choice was deferred at stack
  selection — `tech-stack.md`). The foundation relies on Spring Security's auto-generated
  `/login` page; the styled page is S-01's job.
- **Existing test is a `@SpringBootTest contextLoads`** (`GarageopsApplicationTests.java`). The
  test profile (`src/test/resources/application.properties`) excludes DataSource + Flyway
  autoconfig by their **Boot 4 per-module FQNs** (`org.springframework.boot.jdbc.autoconfigure.*`,
  `…flyway.autoconfigure.*`) — see commit `2ab8848`. Adding Security means a real filter chain
  loads in tests; that is fine and needs no DB.
- **F-02 (JPA) runs in parallel, not before.** This change must not depend on a persisted
  `User` entity — hence the in-memory owner placeholder.

### Key Discoveries:

- Boot 4.0.6 manages **Spring Security 7.x**. The starter is still
  `org.springframework.boot:spring-boot-starter-security` (NOT renamed the way
  `-starter-web` → `-starter-webmvc` was). Test support is
  `org.springframework.security:spring-security-test` (test scope, parent-managed, no version).
- Security 7 config is the **lambda DSL** on a `@Bean SecurityFilterChain`
  (`authorizeHttpRequests`, `requestMatchers`, `formLogin`, `csrf`).
  `WebSecurityConfigurerAdapter`, `authorizeRequests()`, `antMatchers()` are **removed**.
  `InMemoryUserDetailsManager`, `User.withUsername(...)`, `BCryptPasswordEncoder` are unchanged.
- Defining our own `SecurityFilterChain` + a `UserDetailsService`/`InMemoryUserDetailsManager`
  bean makes Boot's `UserDetailsServiceAutoConfiguration` (the random-password default user)
  back off — no autoconfig exclusion needed.
- `formLogin(Customizer.withDefaults())` still auto-generates `GET /login` and `POST /logout`.
  With CSRF disabled the `CsrfFilter` is removed, so the generated form's POST is accepted
  without a token — login still works (see the CSRF note under Risks).

## Desired End State

After this plan:

1. `spring-boot-starter-security` is on the classpath and the app boots with a single
   `SecurityFilterChain`.
2. An unauthenticated request to any route other than the public carve-out is redirected
   (302) to `GET /login`.
3. `/actuator/health` returns 200 **without** authentication (deploy healthcheck unaffected).
4. The owner can authenticate through the generated login page using credentials sourced
   from configuration (env vars with a local-dev fallback), hashed with BCrypt.
5. An automated MockMvc test locks the gating contract (unauthenticated→redirect, public
   `/health`→200, valid login→authenticated), and the existing `contextLoads` test stays green.

Verify: `mvnw.cmd verify` passes; manually, hitting a gated path in a browser redirects to
`/login`, logging in with the owner credentials succeeds, and `curl /actuator/health` returns 200.

## What We're NOT Doing

- **No signup flow, no DB-backed `UserDetailsService`, no `User` JPA entity** — that's S-01
  (and F-02 owns JPA). The owner is an in-memory placeholder here.
- **No styled login page, no custom login/logout templates** — we use Spring's generated page.
  S-01 swaps in a Thymeleaf page via `loginPage(...)`.
- **No logout/session UX, remember-me, or "log in from any device" acceptance work** — S-01 (FR-002).
- **No new domain routes/controllers.** Gating is proven against arbitrary paths; no placeholder
  controller is added.
- **No re-enabling CSRF** (disabled per decision; flagged for S-01 to revisit — see Risks).
- **No changes to the Flyway smoke migration or the JDBC starter** — F-02 owns the JDBC→JPA swap.

## Implementation Approach

Add the security starter, then introduce one `@Configuration @EnableWebSecurity` class under a
new `com.example.garageops.security` package (package-by-feature per AGENTS.md). It contributes
three beans: a `SecurityFilterChain` (gating rules + public carve-out + form login + CSRF
disabled), a `PasswordEncoder` (BCrypt), and a `UserDetailsService` (`InMemoryUserDetailsManager`
holding one owner built from configuration). Owner credentials come from env vars
(`OWNER_USERNAME`, `OWNER_PASSWORD_HASH`) with a local-dev fallback, mirroring the existing `PG*`
env pattern in `application.properties`. Then add `spring-security-test` and a MockMvc test that
pins the gating behavior so downstream slices can't silently break it.

## Critical Implementation Details

- **Public carve-out must be tight.** Only `/actuator/health`, the login endpoints
  (`/login`), and static assets are `permitAll`; everything else is `authenticated()`. Do NOT
  `permitAll` the whole `/actuator/**` tree — that would leak env/beans/mappings and violate the
  privacy NFR. Health-only exposure is already set in `application.properties`, but the security
  carve-out must match it.
- **Owner password is stored as a BCrypt hash, never plaintext.** `OWNER_PASSWORD_HASH` holds a
  pre-computed BCrypt hash; the `InMemoryUserDetailsManager` user is built with that hash and the
  configured `PasswordEncoder`. The local-dev fallback hash must correspond to a known dev
  password documented in the change folder, not a real secret.
- **Security autoconfig stays ON in tests.** Do not add any security class to the test
  `spring.autoconfigure.exclude` — the DataSource/Flyway exclusions stay as-is; the security
  filter chain must load so the gating test exercises the real configuration.

## Phase 1: Security wiring

### Overview

Add the security dependency and the `SecurityConfig` class so the app boots fully gated, the
owner can log in via the generated page, and `/actuator/health` stays public.

### Changes Required:

#### 1. Add the Spring Security starter

**File**: `pom.xml`

**Intent**: Put Spring Security 7 (managed by the Boot 4.0.6 parent) on the classpath so the
filter chain and login machinery are available.

**Contract**: Add a `<dependency>` for `org.springframework.boot:spring-boot-starter-security`
with no `<version>` (parent-managed), placed alongside the other `spring-boot-starter-*` entries.
Preserve tab indentation and the existing Boot 4 starter naming convention.

#### 2. Security configuration

**File**: `src/main/java/com/example/garageops/security/SecurityConfig.java`

**Intent**: Define the gating contract, password hashing, and the in-memory owner in one
`@Configuration @EnableWebSecurity` class using the Security 7 lambda DSL. Constructor injection
only; `@Configuration` (never `@Component`) per AGENTS.md.

**Contract**: Three beans:
- `SecurityFilterChain filterChain(HttpSecurity http)` — `authorizeHttpRequests` with
  `requestMatchers("/actuator/health")` and static assets + `/login` as `permitAll`, `anyRequest().authenticated()`;
  `formLogin(Customizer.withDefaults())`; `csrf(csrf -> csrf.disable())`.
- `PasswordEncoder passwordEncoder()` — `new BCryptPasswordEncoder()`.
- `UserDetailsService users(PasswordEncoder encoder, <owner config>)` — an
  `InMemoryUserDetailsManager` with one owner built via `User.withUsername(username).password(hash).roles("OWNER")`,
  reading `username`/`hash` from configuration.

#### 3. Owner credential configuration

**File**: `src/main/resources/application.properties`

**Intent**: Source the placeholder owner's username and BCrypt password hash from env vars with a
local-dev fallback, matching the existing `PG*` env-var pattern — no real secret committed.

**Contract**: Add two properties (e.g. `garageops.owner.username=${OWNER_USERNAME:owner}` and
`garageops.owner.password-hash=${OWNER_PASSWORD_HASH:<dev-bcrypt-hash>}`) bound into `SecurityConfig`
(via `@Value` or a small `@ConfigurationProperties` record). The hash, not the plaintext, lives in
config. The fallback hash and its plaintext are a **matched BCrypt pair** — generate them together
(`new BCryptPasswordEncoder().encode("<dev-pw>")`) and record **both** the plaintext and the hash in
a `dev-credentials.md` note in the change folder. Phase 2's login test (case 3) signs in with that
exact plaintext, so the pair MUST stay in sync or the test fails with a misleading "bad credentials".

### Success Criteria:

#### Automated Verification:

- Project compiles: `mvnw.cmd -q compile`
- Application context boots with security on the classpath: `mvnw.cmd -q test -Dtest=GarageopsApplicationTests`
- Full build passes: `mvnw.cmd verify`

#### Manual Verification:

> **Prerequisite:** these steps run the full app (`mvnw.cmd spring-boot:run`), which boots DataSource + Flyway (main profile, no exclusions). A reachable Postgres is required — start a local one on `:5433` or point `PG*` at the Railway DB — or the app fails at the DataSource stage before any gating check. The gating contract itself is already proven by the DB-excluded automated test in Phase 2; these browser/curl checks are confirmatory, not the sole proof.

- Hitting a gated path (e.g. `http://localhost:8080/`) while unauthenticated redirects to `/login`.
- Logging in on the generated `/login` page with the configured owner credentials succeeds (no longer redirected to login).
- `curl -i http://localhost:8080/actuator/health` returns `200` without authentication.
- Wrong credentials are rejected at `/login` (redirect to `/login?error`).

**Implementation Note**: After completing this phase and all automated verification passes, pause
here for manual confirmation from the human that the browser/curl checks above succeeded before
proceeding to Phase 2. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes live
in the `## Progress` section at the bottom.

---

## Phase 2: Lock the gating contract with tests

### Overview

Add Spring Security's test support and a MockMvc test that pins the gating behavior, so S-01 and
every later slice inherit a regression guard rather than an untested filter chain.

### Changes Required:

#### 1. Add the security test dependency

**File**: `pom.xml`

**Intent**: Bring in `@WithMockUser` / `SecurityMockMvcRequestPostProcessors` and the form-login
test helpers.

**Contract**: Add `<dependency>` for `org.springframework.security:spring-security-test` with
`<scope>test</scope>` and no `<version>` (parent-managed), alongside `spring-boot-starter-webmvc-test`.

#### 2. Gating contract test

**File**: `src/test/java/com/example/garageops/security/SecurityGatingTests.java`

**Intent**: Assert the three load-bearing facts of the foundation against the real `SecurityConfig`:
unauthenticated access is redirected, `/actuator/health` is public, and a valid login authenticates.

**Contract**: A MockMvc test (e.g. `@SpringBootTest` + `@AutoConfigureMockMvc`, relying on the
existing test-profile DataSource/Flyway exclusions) with three cases:
- GET a gated path while unauthenticated → `3xx` redirect to `**/login`.
- GET `/actuator/health` while unauthenticated → `200`.
- `formLogin().user(<owner>).password(<dev-pw>)` → `authenticated()`, where `<dev-pw>` is the
  documented plaintext from Phase 1's `dev-credentials.md` that matches the fallback BCrypt hash.

Class name ends in `Tests` per the project test convention.

#### 3. Confirm test autoconfig untouched

**File**: `src/test/resources/application.properties`

**Intent**: Ensure security autoconfig stays active in tests (so the filter chain loads) while the
DataSource/Flyway exclusions remain.

**Contract**: No security class is added to `spring.autoconfigure.exclude`; the existing two
exclusions are left exactly as-is. (Verification step, likely a no-op edit.)

### Success Criteria:

#### Automated Verification:

- New gating test passes: `mvnw.cmd test -Dtest=SecurityGatingTests`
- Existing smoke test still passes: `mvnw.cmd test -Dtest=GarageopsApplicationTests`
- Full build + suite passes: `mvnw.cmd verify`

#### Manual Verification:

- Reviewer confirms the test asserts all three facts (redirect, public health, valid login) against
  the real `SecurityConfig`, not a mock chain.

**Implementation Note**: After automated verification passes, pause for manual confirmation before
considering the change complete.

---

## Testing Strategy

### Unit / slice tests:

- `SecurityGatingTests` — unauthenticated redirect, public `/actuator/health`, valid form login.

### Integration tests:

- The `@SpringBootTest`-based gating test loads the real `SecurityConfig` and filter chain (DB
  autoconfig excluded), so it doubles as the integration check that security wiring boots correctly.

### Manual Testing Steps:

1. `mvnw.cmd spring-boot:run`, then open `http://localhost:8080/` → expect redirect to `/login`.
2. Log in with the configured owner credentials → expect to pass the gate.
3. `curl -i http://localhost:8080/actuator/health` → expect `200` unauthenticated.
4. Try a wrong password on `/login` → expect `/login?error`.

## Performance Considerations

Negligible. One filter chain and an in-memory user store; no DB calls on the auth path.
BCrypt verification cost is per-login only and acceptable for a single-owner tool.

## Migration Notes

Before the deployed app works with auth, set `OWNER_USERNAME` and `OWNER_PASSWORD_HASH` (a BCrypt
hash) as Railway environment variables. Until then the local-dev fallback applies. No schema or
data migration; the Flyway smoke migration and JDBC starter are untouched (F-02 owns the JPA swap).

## References

- Roadmap item: `context/foundation/roadmap.md` → F-01 (lines 66–77)
- PRD: Access Control (lines 138–142), NFR-privacy (line 128), FR-001/FR-002 (lines 67–70)
- Deploy healthcheck dependency: `src/main/resources/application.properties:7-9`
- Boot 4 autoconfig-FQN precedent: commit `2ab8848`
- Downstream consumer: S-01 `owner-auth-signup-login` (swaps the in-memory user for DB-backed)

## Open Risks & Assumptions

- **CSRF is disabled (explicit decision).** A session-cookie form-login app with CSRF off is
  exposed to cross-site request forgery against authenticated state. The generated login still
  works (no `CsrfFilter`), but **S-01 should re-enable CSRF before shipping real forms** — doing
  so later is a breaking change for every form built in the interim. Recorded here so it is not lost.
- **In-memory owner is a placeholder.** It exists only to prove the gating contract end-to-end;
  S-01 replaces the `UserDetailsService` with a DB-backed one. The filter chain should not need to change.
- **Assumption:** the parent-managed Spring Security version is 7.x and the starter artifactId is
  unchanged (verified via Spring Boot 4.0.6 managed coordinates). If `mvnw.cmd compile` reports an
  unresolved starter, re-check the artifactId before proceeding.
- **Assumption:** `/actuator/health` is the only actuator endpoint that must stay public; matches
  the current `management.endpoints.web.exposure.include=health`.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Security wiring

#### Automated

- [x] 1.1 Project compiles: `mvnw.cmd -q compile` — 6dec72c
- [x] 1.2 Application context boots with security: `mvnw.cmd -q test -Dtest=GarageopsApplicationTests` — 6dec72c
- [x] 1.3 Full build passes: `mvnw.cmd verify` — 6dec72c

#### Manual

- [x] 1.4 Unauthenticated gated path redirects to `/login` — 6dec72c
- [x] 1.5 Login with configured owner credentials succeeds — 6dec72c
- [x] 1.6 `/actuator/health` returns 200 unauthenticated — 6dec72c
- [x] 1.7 Wrong credentials rejected (`/login?error`) — 6dec72c

### Phase 2: Lock the gating contract with tests

#### Automated

- [x] 2.1 New gating test passes: `mvnw.cmd test -Dtest=SecurityGatingTests`
- [x] 2.2 Existing smoke test still passes: `mvnw.cmd test -Dtest=GarageopsApplicationTests`
- [x] 2.3 Full build + suite passes: `mvnw.cmd verify`

#### Manual

- [x] 2.4 Reviewer confirms test asserts redirect, public health, and valid login against real `SecurityConfig`
