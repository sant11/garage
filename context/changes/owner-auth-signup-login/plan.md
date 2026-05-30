# Owner Auth (Login / Logout) — Vaadin 25 Implementation Plan

## Overview

Turn the F-01 placeholder authentication into a real, **Vaadin-Flow-fronted, DB-backed owner login**, and lay down the Vaadin 25 platform that every later user-visible slice (S-02–S-07) will build on. The owner is a single account, **provisioned via an idempotent env-var bootstrap** (not self-registered), and signs in / out through a Vaadin `LoginForm` hosted in an app-shell layout.

This slice spends the three debts F-01 explicitly deferred to S-01: swap the in-memory `UserDetailsService` for a DB-backed one, ship the real (now Vaadin) login page, and re-enable CSRF before any real form ships.

## Current State Analysis

- **Security (F-01, live):** `com.example.garageops.security.SecurityConfig` is a `@Configuration @EnableWebSecurity` class with one `SecurityFilterChain` using the Security 7 lambda DSL — `authorizeHttpRequests` (public carve-out for `/actuator/health`, `/login`, static assets; `anyRequest().authenticated()`), `formLogin(Customizer.withDefaults())` (Spring's auto-generated `/login`), and **`csrf(csrf -> csrf.disable())`**. It also defines a `BCryptPasswordEncoder` bean and a `UserDetailsService` bean = `InMemoryUserDetailsManager` holding one `OWNER`-role user built from `garageops.owner.username` / `garageops.owner.password-hash` (env vars `OWNER_USERNAME` / `OWNER_PASSWORD_HASH`, with a documented local-dev BCrypt fallback in `application.properties:14-15`).
- **Persistence (F-02, live):** `com.example.garageops.persistence.BaseEntity` (`@MappedSuperclass`, `@Id @GeneratedValue(IDENTITY) Long id`) and `ArchivableEntity` (adds `archived_at` + `created_at`/`updated_at` lifecycle audit). Flyway owns schema (`spring.jpa.hibernate.ddl-auto=validate`); only migration is `V1__init.sql` (`deploy_smoke_test`). Repositories extend `JpaRepository` and live in the `persistence` package (shared-kernel exception to package-by-feature).
- **Frontend:** none. No Vaadin, no Thymeleaf. The view-layer choice is now resolved to **Vaadin Flow 25** (`tech-stack.md`). This slice introduces the first Vaadin code in the repo.
- **Tests:** DB-free by convention. `src/test/resources/application.properties` excludes DataSource/Flyway/JPA autoconfig via Boot-4 per-module FQNs. `SecurityGatingTests` (`@SpringBootTest` + `@AutoConfigureMockMvc`) logs in as the in-memory owner; `ArchivableEntityTests` is a pure-POJO unit test. There is **no database-backed test harness** (no H2, no Testcontainers).
- **Deploy:** `Dockerfile` is a multi-stage build — JDK build stage runs `./mvnw -B -ntp -DskipTests package`, JRE stage runs the jar. `railway.json` builds from the Dockerfile and healthchecks `/actuator/health` (300s timeout).

### Key Discoveries:

- **Vaadin 25.1 + Spring Boot 4.0.6 is an officially supported pairing.** Vaadin docs show `spring-boot-starter-parent 4.0.6` with `<vaadin.version>25.1.6</vaadin.version>` together. Vaadin 24 targets Spring Boot 3 — do not use it.
- **Vaadin 25 security is the composable `VaadinSecurityConfigurer`**, not v24's `extends VaadinWebSecurity`. Pattern: `http.with(VaadinSecurityConfigurer.vaadin(), cfg -> cfg.loginView(LoginView.class)).build()`. It auto-applies `FormLoginConfigurer`, **`CsrfConfigurer` (CSRF enabled by default, allowing Vaadin internal requests)**, `LogoutConfigurer`, `RequestCacheConfigurer`, `ExceptionHandlingConfigurer`, and an `AuthorizeHttpRequestsConfigurer` that permits framework + `@AnonymousAllowed` requests and **denies everything else by default**.
- **View access is annotation-driven:** `@AnonymousAllowed` (login), `@PermitAll` (any authenticated user — correct for the flat single-owner role), `@RolesAllowed`, `@DenyAll`. Unannotated views are inaccessible.
- **`LoginForm` posts to `/login`** which Spring form-login processes; failure redirects to `/login?error`, handled in the view via `BeforeEnterObserver`. The `LoginView` uses `@Route(value="login", autoLayout=false)`.
- **Post-login lands on `/`** — requires a `@Route("")` view to exist, or the owner gets a 404/blank page. The `MainLayout` + `HomeView` satisfies this.
- **Logout** is wired by the configurer's `LogoutConfigurer`; a UI button triggers it via the shared `AuthenticationContext.logout()` bean.
- **The shared `AuthenticationContext` bean** (provided by the configurer) is also how a view reads the current principal.
- **Production build:** `vaadin-maven-plugin`'s `build-frontend` goal (in a `production` profile) installs Node if missing, then bundles the frontend; `prepare-frontend` covers dev mode. `vaadin-dev` (`<optional>true</optional>`) supplies the Vite dev server and is excluded from the production jar by `spring-boot-maven-plugin`.

## Desired End State

After this plan:

1. Vaadin 25.1.6 is on the classpath; `mvnw.cmd spring-boot:run` starts the app in dev mode (Vite), and `mvnw.cmd -Pproduction package` produces a single executable jar with the optimized frontend bundle.
2. The Railway Dockerfile builds with `-Pproduction`; the deployed app serves the Vaadin login at a public URL and `/actuator/health` stays 200 unauthenticated.
3. An unauthenticated visitor to any route is redirected to the Vaadin `LoginView`. Signing in with the owner credentials lands them on the `HomeView` inside `MainLayout`; a logout control returns them to the login screen.
4. The owner is a row in a `users` table, provisioned idempotently on startup from `OWNER_*` env vars when the table is empty. The `InMemoryUserDetailsManager` is gone; authentication is DB-backed.
5. CSRF protection is active (managed by `VaadinSecurityConfigurer`); the F-01 `csrf.disable()` is removed.
6. The DB-free test suite is green: `SecurityGatingTests` still asserts the three gating facts (unauth→login, `/health` public, valid login→authenticated) against the real Vaadin filter chain, and new unit tests cover the `User` entity and the bootstrap-runner idempotency.

**Verify:** `mvnw.cmd verify` passes; `mvnw.cmd -Pproduction package` succeeds; on Railway the deployed app shows the Vaadin login, login from two devices works (FR-002), logout works, `/actuator/health` is public.

## What We're NOT Doing

- **No signup UI / self-registration.** The owner is provisioned via the env-var bootstrap runner (decision: documented deviation from FR-001's "sign up"; single-owner internal tool, no public registration surface). No `/signup` view.
- **No change-password, forgot/reset-password, or remember-me.** Out of scope per the feature-scope decision (reset would need email — a PRD non-goal). Session-cookie auth only.
- **No real-DB automated persistence test (Testcontainers/H2).** Deferred to Module 3's test-strategy harness; S-01 stays DB-free and relies on `ddl-auto=validate` at boot + manual verification for the real round-trip.
- **No Vaadin UI test library (Karibu/browserless, TestBench).** UI flow is covered by manual verification this slice.
- **No domain views or navigation beyond login + a placeholder home.** S-02+ own portfolio/tenant/contract views; the `MainLayout` shell is intentionally minimal.
- **No archivability on the owner account.** `User extends BaseEntity`, not `ArchivableEntity` — FR-021 archive-only is for portfolio records, not the single login identity.
- **No multi-tenant scoping** (PRD non-goal; AGENTS hard rule).

## Implementation Approach

Build outward in four phases, each independently verifiable:

1. Get the **Vaadin platform and production build** working first, with auth behavior unchanged — so the riskiest new thing (a frontend build inside Docker) is proven before any security rewrite.
2. **Migrate Spring Security to `VaadinSecurityConfigurer`** and build the Vaadin login/home/logout UI, still against the existing in-memory owner — so the full Vaadin auth flow is demoable before the persistence swap.
3. **Swap the auth source to the database** (entity + migration + repository + repo-backed `UserDetailsService` + idempotent bootstrap), retiring the in-memory placeholder.
4. **Lock the behavior with DB-free tests and verify the cross-device production deploy.**

All new code is package-by-feature under `com.example.garageops.<feature>` (AGENTS): security stays in `security`; the owner identity model goes under a new `account` package; views under `ui` (shell) / feature packages. Constructor injection only; `@Configuration`/`@Service`/`@RestController` roles never plain `@Component`; tab indentation; test classes end in `Tests`.

## Critical Implementation Details

- **Do not lock yourself out during the security migration.** `VaadinSecurityConfigurer` denies every request that isn't a framework request, an `@AnonymousAllowed` view, or an explicitly permitted matcher. The `LoginView` (`@AnonymousAllowed`) and the `HomeView` (`@PermitAll`, `@Route("")`) must exist in the same change as the configurer migration (Phase 2), or the app has no reachable entry point. Keep CSRF enabled (configurer default) — do **not** carry over `csrf.disable()`.
- **`/actuator/health` carve-out must be re-expressed for the configurer.** Add an explicit `http.authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health").permitAll())` rule *in addition to* the `http.with(VaadinSecurityConfigurer.vaadin(), …)` block (the documented "Custom Authorization Rules" composition). Do not broaden to `/actuator/**` — privacy NFR.
- **`ddl-auto=validate` is unforgiving.** The Phase 3 `User` entity column mapping and the V2 migration must match exactly (column names, nullability, `Instant`→`timestamptz` if any audit columns are added). A mismatch fails context startup at boot — this is the intended safety net given there's no automated DB test.
- **Bootstrap runner must be idempotent and DB-only.** It runs on `ApplicationReadyEvent`/`ApplicationRunner` in the main profile (where JPA is active); it must no-op when an owner already exists (check `count()`/`existsByUsername`) so redeploys don't duplicate or overwrite. It must not run in the DB-free test context (it depends on `UserRepository`, which isn't loaded when JPA autoconfig is excluded — so the gating test's context must not require it; see Phase 4).
- **Gating test stays DB-free.** With JPA excluded in tests, a repo-backed `UserDetailsService` bean can't be constructed. In `SecurityGatingTests`, provide the `UserDetailsService` (and `UserRepository` if needed) as a `@MockitoBean` returning a known BCrypt-hashed owner, so the real `SecurityConfig`/`VaadinSecurityConfigurer` filter chain is exercised without a database.

## Phase 1: Vaadin 25 platform & production build

### Overview

Put Vaadin 25.1.6 on the classpath, wire the dev and production frontend builds, and make the Docker/Railway build produce a frontend-bundled jar — with no change to auth behavior yet.

### Changes Required:

#### 1. Vaadin dependencies & version property

**File**: `pom.xml`

**Intent**: Add Vaadin 25.1.6 via the BOM + Spring Boot starter so Vaadin auto-configuration and components are available, with the dev tooling excluded from the production jar.

**Contract**: Add `<vaadin.version>25.1.6</vaadin.version>` to `<properties>`. Add a `<dependencyManagement>` block importing `com.vaadin:vaadin-bom:${vaadin.version}` (`<type>pom</type>`, `<scope>import</scope>`). Add dependencies `com.vaadin:vaadin-spring-boot-starter` and `com.vaadin:vaadin-dev` (`<optional>true</optional>`), versions managed by the BOM. Preserve tab indentation and the existing Boot-4 starter naming. Leave the intentional empty `<name>`/`<description>`/`<licenses>`/`<developers>`/`<scm>` blocks untouched (AGENTS hard rule).

#### 2. Vaadin Maven plugin + production profile

**File**: `pom.xml`

**Intent**: Run `prepare-frontend` in the default build (dev mode) and `build-frontend` only under a `production` profile, so `mvnw package` is fast in dev and `mvnw -Pproduction package` emits the optimized bundle.

**Contract**: Add `com.vaadin:vaadin-maven-plugin` (version `${vaadin.version}`) to `<build><plugins>` with a `prepare-frontend` execution. Add a `<profiles>` section with a `production` profile that re-declares the plugin with a `build-frontend` execution and `<configuration>`: `<forceProductionBuild>true</forceProductionBuild>` and `<ciBuild>true</ciBuild>`. The `spring-boot-maven-plugin` stays as-is (it excludes the optional `vaadin-dev` from the jar).

#### 3. Docker production build

**File**: `Dockerfile`

**Intent**: Make the image build run the Vaadin production frontend build so the deployed jar serves a real bundle.

**Contract**: Change the build-stage package command to activate the production profile: `./mvnw -B -ntp -DskipTests -Pproduction package`. The `vaadin-maven-plugin` auto-installs Node in the build stage (network available, as `dependency:go-offline` already runs there) — no separate Node base image required. JRE runtime stage unchanged.

### Success Criteria:

#### Automated Verification:

- Project compiles with Vaadin on the classpath: `mvnw.cmd -q compile`
- Context still boots (existing tests green): `mvnw.cmd test`
- Production build emits a frontend-bundled jar: `mvnw.cmd -q -Pproduction -DskipTests package`

#### Manual Verification:

- `mvnw.cmd spring-boot:run` starts in dev mode without frontend-build errors (Vite dev server initializes).
- `docker build .` completes through the `-Pproduction package` stage (frontend bundle built, Node auto-installed).

**Implementation Note**: After this phase and all automated verification passes, pause for manual confirmation before Phase 2. Phase blocks use plain bullets — checkboxes live in `## Progress`.

---

## Phase 2: Auth UI + Spring Security on VaadinSecurityConfigurer

### Overview

Migrate `SecurityConfig` to `VaadinSecurityConfigurer`, build the Vaadin login screen, app-shell, placeholder home, and logout — re-enabling CSRF — while still authenticating against the existing in-memory owner so the flow is demoable end-to-end on Vaadin.

### Changes Required:

#### 1. Migrate the security filter chain

**File**: `src/main/java/com/example/garageops/security/SecurityConfig.java`

**Intent**: Replace the plain `formLogin` + `csrf.disable()` chain with the Vaadin configurer, keep `/actuator/health` public, and let the configurer manage CSRF, logout, and view access. Keep the `PasswordEncoder` and (for now) the in-memory `UserDetailsService` beans.

**Contract**: The `SecurityFilterChain` bean becomes:

```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
	http.authorizeHttpRequests(auth -> auth
			.requestMatchers("/actuator/health").permitAll());
	http.with(VaadinSecurityConfigurer.vaadin(), cfg -> cfg.loginView(LoginView.class));
	return http.build();
}
```

Remove `formLogin(...)`, `csrf(... .disable())`, and the manual static-asset/`/login` matchers (the configurer's `defaultPermitMatcher` covers framework requests, `@AnonymousAllowed` routes, and web assets). Keep `@Configuration @EnableWebSecurity`, constructor injection, `PasswordEncoder passwordEncoder()`, and the in-memory `UserDetailsService users(...)` bean **unchanged in this phase**.

#### 2. Login view

**File**: `src/main/java/com/example/garageops/security/LoginView.java` (or `account` package — keep with the auth surface)

**Intent**: The `@AnonymousAllowed` login screen using Vaadin's `LoginForm`, posting to Spring's form-login endpoint and showing an error on `?error`.

**Contract**: `@Route(value = "login", autoLayout = false)`, `@PageTitle("Login")`, `@AnonymousAllowed`. Hosts a `LoginForm` with `setAction("login")`, centered in a full-size layout. Implements `BeforeEnterObserver` to call `loginForm.setError(true)` when the `error` query parameter is present. No custom POST handling — Spring form-login processes `/login`.

#### 3. App-shell layout

**File**: `src/main/java/com/example/garageops/ui/MainLayout.java`

**Intent**: A reusable app-shell (`@Route`-able parent layout) carrying the app header and the logout control, into which later slices plug their views.

**Contract**: Extends `AppLayout` (or a `Div`/`VerticalLayout` shell). Header shows the app name and a logout `Button`. Logout calls the injected shared `AuthenticationContext.logout()` bean (constructor-injected). This is the canonical app-shell; S-02+ add navigation here.

#### 4. Placeholder home view

**File**: `src/main/java/com/example/garageops/ui/HomeView.java`

**Intent**: Give the post-login redirect (`/`) a real target and a visible "you're signed in" surface with logout reachable, until S-06's dashboard replaces it.

**Contract**: `@Route(value = "", layout = MainLayout.class)`, `@PageTitle`, **`@PermitAll`**. Minimal content (greeting + note that the dashboard arrives in a later slice). It does not pre-empt S-06's dashboard design.

#### 5. Gating test adjusted for the Vaadin chain

**File**: `src/test/java/com/example/garageops/security/SecurityGatingTests.java`

**Intent**: Keep the three gating facts green against the new `VaadinSecurityConfigurer` chain while still using the in-memory owner (DB-free).

**Contract**: Verify unauthenticated GET of a gated path → redirect to the login view; `/actuator/health` → 200; `formLogin().user(owner).password(<dev-pw>)` → `authenticated()`. Adjust the expected redirect target / matchers to the Vaadin login route as needed. Still `@SpringBootTest` + `@AutoConfigureMockMvc`, JPA excluded.

### Success Criteria:

#### Automated Verification:

- Compiles: `mvnw.cmd -q compile`
- Gating test passes against the Vaadin chain: `mvnw.cmd test -Dtest=SecurityGatingTests`
- Full suite green: `mvnw.cmd verify`

#### Manual Verification:

- Unauthenticated visit to `http://localhost:8080/` redirects to the Vaadin `LoginView` (styled `LoginForm`, not Spring's generated page).
- Logging in with the in-memory owner credentials lands on `HomeView` inside `MainLayout`.
- The logout control returns to the login screen and the session is invalidated (re-visiting `/` redirects to login again).
- `curl -i http://localhost:8080/actuator/health` → 200 unauthenticated.
- A wrong password shows the `LoginForm` error state (`/login?error`).

**Implementation Note**: Pause for manual confirmation after automated verification passes before Phase 3.

---

## Phase 3: DB-backed owner store + idempotent bootstrap

### Overview

Replace the in-memory owner with a persisted `users` table, a repo-backed `UserDetailsService`, and an idempotent startup bootstrap that seeds the owner from `OWNER_*` env vars.

### Changes Required:

#### 1. Owner entity

**File**: `src/main/java/com/example/garageops/account/OwnerAccount.java` (entity name reflects single-owner intent; `users` table)

**Intent**: The persisted identity row backing authentication. Not archivable (FR-021 is for portfolio records, not the login identity).

**Contract**: `@Entity @Table(name = "users")`, `extends BaseEntity` (inherits `IDENTITY` id). Fields: `username` (unique, not null), `passwordHash` (not null), `email` (not null/unique per provisioning). Protected no-arg constructor + a constructor/factory for the bootstrap. Getters; no setters beyond what the store needs. Column mapping must match the V2 migration exactly (`ddl-auto=validate`).

#### 2. Repository

**File**: `src/main/java/com/example/garageops/account/OwnerAccountRepository.java`

**Intent**: Spring Data access for lookup-by-username and existence checks.

**Contract**: `extends JpaRepository<OwnerAccount, Long>` with `Optional<OwnerAccount> findByUsername(String username)` and `boolean existsByUsername(String)` / reuse `count()`.

#### 3. Flyway V2 migration

**File**: `src/main/resources/db/migration/V2__users.sql`

**Intent**: Create the `users` table the entity validates against. V1 is immutable — this is a new versioned migration.

**Contract**: `CREATE TABLE users` with `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY` (matching `BaseEntity` `IDENTITY`), `username` (unique, not null), `password_hash` (not null), `email` (unique, not null). Column names/types/nullability must match the entity. No `archived_at`/`created_at`/`updated_at` (not archivable).

#### 4. DB-backed UserDetailsService

**File**: `src/main/java/com/example/garageops/security/SecurityConfig.java` (+ a `UserDetailsService` impl, e.g. `account/OwnerDetailsService.java`)

**Intent**: Authenticate against the DB; retire the in-memory placeholder.

**Contract**: Replace the `InMemoryUserDetailsManager` bean with a `UserDetailsService` that loads the owner via `OwnerAccountRepository.findByUsername`, mapping to a Spring `UserDetails` with role `OWNER` and the stored BCrypt hash. The `PasswordEncoder` (BCrypt) bean stays. Spring's default `DaoAuthenticationProvider` wires the two together. Remove the `garageops.owner.*` in-memory wiring from this bean (the values move to the bootstrap runner).

#### 5. Idempotent owner bootstrap

**File**: `src/main/java/com/example/garageops/account/OwnerBootstrap.java`

**Intent**: Provision the single owner on startup from env vars when none exists, replacing self-registration.

**Contract**: An `ApplicationRunner` (or `@EventListener(ApplicationReadyEvent.class)`) bean that, if `repository.count() == 0`, creates one `OwnerAccount` from `OWNER_USERNAME` / `OWNER_EMAIL` / `OWNER_PASSWORD_HASH` (reuse the existing `garageops.owner.*` properties; add `garageops.owner.email`). The password value is already a BCrypt **hash** (stored as-is, never re-encoded). No-ops when an owner already exists (idempotent across redeploys). Logs which path it took. Lives in the main profile only (depends on the repository).

#### 6. Owner credential config

**File**: `src/main/resources/application.properties`

**Intent**: Keep the env-var credential pattern; add the email property; update the comment to reflect DB-backed provisioning.

**Contract**: Keep `garageops.owner.username` / `garageops.owner.password-hash`; add `garageops.owner.email=${OWNER_EMAIL:owner@example.com}` (local-dev fallback). Update the inline comment (the "in-memory placeholder; S-01 swaps…" note is now fulfilled — describe it as the bootstrap seed).

### Success Criteria:

#### Automated Verification:

- Compiles: `mvnw.cmd -q compile`
- Existing tests still green (gating test now mocks the DB-backed `UserDetailsService` — see Phase 4): `mvnw.cmd test`
- Full build: `mvnw.cmd verify`

#### Manual Verification:

- Against a real Postgres (`mvnw.cmd spring-boot:run`): Flyway applies `V2__users.sql`; the app boots (no `ddl-auto=validate` mismatch).
- On first boot with an empty `users` table, the bootstrap seeds the owner from `OWNER_*` (or local-dev fallback); a second restart does **not** duplicate it.
- Login with the seeded owner credentials succeeds end-to-end (Vaadin login → home); the previous in-memory-only path is gone.
- Wrong credentials are rejected.

**Implementation Note**: Pause for manual confirmation (requires a reachable Postgres) before Phase 4.

---

## Phase 4: Lock with tests + verify cross-device deploy

### Overview

Add DB-free unit coverage for the new persistence/bootstrap logic, keep the gating test DB-free against the DB-backed config, and verify the real Railway deployment satisfies FR-002.

### Changes Required:

#### 1. Owner entity unit test

**File**: `src/test/java/com/example/garageops/account/OwnerAccountTests.java`

**Intent**: Pure-POJO test of the entity's construction/invariants (mirrors `ArchivableEntityTests`; no DB).

**Contract**: Assert a constructed `OwnerAccount` exposes the username/email/passwordHash it was built with and stores the hash verbatim (no re-encoding). Class name ends in `Tests`.

#### 2. Bootstrap idempotency test

**File**: `src/test/java/com/example/garageops/account/OwnerBootstrapTests.java`

**Intent**: Verify the runner seeds exactly once, with a mocked repository (no DB).

**Contract**: With a Mockito-mocked `OwnerAccountRepository`: when `count()==0`, the runner saves one owner built from the configured env values; when an owner already exists, it saves nothing. No Spring context required.

#### 3. Keep the gating test DB-free

**File**: `src/test/java/com/example/garageops/security/SecurityGatingTests.java`

**Intent**: Exercise the real Vaadin filter chain without a database now that auth is DB-backed.

**Contract**: Provide the `UserDetailsService` (and `OwnerAccountRepository` if the context requires it) as `@MockitoBean`s returning a known owner whose stored BCrypt hash matches a known plaintext. Re-assert the three facts: unauth→login redirect, `/health`→200, valid `formLogin` → `authenticated()`. JPA stays excluded in the test profile.

#### 4. Verify the production deploy

**File**: — (no code; deploy + manual verification)

**Intent**: Prove the Vaadin production build deploys and satisfies "log in from any device" (FR-002).

**Contract**: Set `OWNER_USERNAME` / `OWNER_EMAIL` / `OWNER_PASSWORD_HASH` as Railway env vars, deploy via the `-Pproduction` Dockerfile, and confirm the live URL serves the Vaadin login, login/logout works from two distinct devices/browsers with the same credentials, and `/actuator/health` is public.

### Success Criteria:

#### Automated Verification:

- New unit tests pass: `mvnw.cmd test -Dtest=OwnerAccountTests,OwnerBootstrapTests`
- Gating test green (DB-free) against DB-backed config: `mvnw.cmd test -Dtest=SecurityGatingTests`
- Full build + suite: `mvnw.cmd verify`

#### Manual Verification:

- Railway deploy (built with `-Pproduction`) comes up healthy (`/actuator/health` green within the 300s window).
- The deployed app serves the Vaadin `LoginView`; login with the seeded owner succeeds from two different devices (FR-002), and logout works on each.
- No tenant/portfolio data routes are reachable unauthenticated (privacy NFR spot-check).

**Implementation Note**: After automated verification and the deploy check, the slice is complete and ready for `/10x-impl-review`.

---

## Testing Strategy

### Unit Tests:

- `OwnerAccountTests` — entity invariants, hash stored verbatim (pure POJO).
- `OwnerBootstrapTests` — seeds once when empty, no-ops when an owner exists (mocked repository).

### Integration Tests:

- `SecurityGatingTests` — `@SpringBootTest` + `@AutoConfigureMockMvc` loads the real `SecurityConfig`/`VaadinSecurityConfigurer` chain (JPA excluded; `UserDetailsService` mocked). Doubles as the wiring check that the Vaadin security chain boots.

### Manual Testing Steps:

1. `mvnw.cmd spring-boot:run` (Postgres reachable) → open `/` → redirected to the Vaadin login.
2. Log in with the seeded owner → land on `HomeView` in `MainLayout`.
3. Click logout → back to login; re-visiting `/` redirects to login.
4. `curl -i /actuator/health` → 200 unauthenticated; wrong password → `LoginForm` error.
5. Restart the app → bootstrap does not duplicate the owner.
6. Deploy to Railway (`-Pproduction`) → log in from a phone and a desktop with the same credentials.

## Performance Considerations

Negligible at this scale (single owner, low QPS). BCrypt verification cost is per-login only. The production frontend build adds build/deploy time (not runtime); Railway's 300s healthcheck timeout absorbs it. `vaadin-dev` is excluded from the production jar.

## Migration Notes

- **New Flyway migration `V2__users.sql`** — additive, V1 untouched/immutable.
- **Railway env vars** — set `OWNER_USERNAME`, `OWNER_EMAIL`, `OWNER_PASSWORD_HASH` (a BCrypt hash) before/at first deploy; the local-dev fallbacks apply otherwise. The first deploy with an empty `users` table seeds the owner; subsequent deploys are idempotent.
- **CSRF** is now enabled (configurer-managed) — settles the F-01 deferred debt; every Vaadin form built hereafter inherits it.

## References

- Roadmap: `context/foundation/roadmap.md` → S-01 (lines 94–104), Baseline → Frontend (Vaadin Flow 25)
- PRD: FR-001/FR-002 (lines 67–70), Access Control (138–142), NFR-privacy (128), NFR-mobile (126)
- Stack: `context/foundation/tech-stack.md` → "Frontend / UI: Vaadin Flow"
- F-01 plan + deferred debts: `context/archive/2026-05-26-access-control-foundation/plan.md` (lines 71–78, 285–290)
- F-02 base entities/convention: `src/main/java/com/example/garageops/persistence/{BaseEntity,ArchivableEntity}.java`
- Live security config: `src/main/java/com/example/garageops/security/SecurityConfig.java`; gating test: `src/test/java/com/example/garageops/security/SecurityGatingTests.java`
- Vaadin 25.1 docs: `VaadinSecurityConfigurer`, Add Login (LoginForm/LoginView), Production Build, Spring Boot integration

## Open Risks & Assumptions

- **Vaadin + `@SpringBootTest` context load.** With JPA excluded, the gating test must mock the `UserDetailsService`; if Vaadin's autoconfig requires a generated frontend at context-load even in mock-MVC mode, the test may need `prepare-frontend` to have run (it's bound to the default build). Mitigation: `prepare-frontend` runs in `mvnw verify`; if a bare `-Dtest=` run fails to find frontend resources, run via the full lifecycle. Verify in Phase 2.
- **Docker Node install.** `build-frontend` installs Node in the build stage; assumes outbound network in the Railway/Docker build (already true — `dependency:go-offline` runs there). If the build environment is offline, a Node base image or `<nodeVersion>`/frontend cache is needed.
- **No automated proof the V2 migration matches the entity** until Module 3's DB test harness. Mitigation: `ddl-auto=validate` fails fast at boot (manual verification in Phase 3).
- **FR-001 deviation (provisioning, not self-signup)** is a conscious owner decision recorded here; the roadmap/PRD wording ("sign up") is satisfied in spirit (owner controls credentials via env-provisioned hash), not by a registration screen.
- **Assumption:** `com.vaadin:vaadin-spring-boot-starter` + `vaadin-bom` 25.1.6 resolve cleanly under the Boot 4.0.6 parent (docs confirm the pairing). If resolution fails, re-check the BOM coordinates before proceeding.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Vaadin 25 platform & production build

#### Automated

- [x] 1.1 Project compiles with Vaadin on the classpath: `mvnw.cmd -q compile` — 80c74ed
- [x] 1.2 Context still boots (existing tests green): `mvnw.cmd test` — 80c74ed
- [x] 1.3 Production build emits a frontend-bundled jar: `mvnw.cmd -q -Pproduction -DskipTests package` — 80c74ed

#### Manual

- [x] 1.4 `mvnw.cmd spring-boot:run` starts in dev mode without frontend-build errors — 80c74ed
- [x] 1.5 `docker build .` completes through the `-Pproduction package` stage — 80c74ed

### Phase 2: Auth UI + Spring Security on VaadinSecurityConfigurer

#### Automated

- [x] 2.1 Compiles: `mvnw.cmd -q compile` — c674b77
- [x] 2.2 Gating test passes against the Vaadin chain: `mvnw.cmd test -Dtest=SecurityGatingTests` — c674b77
- [x] 2.3 Full suite green: `mvnw.cmd verify` — c674b77

#### Manual

- [x] 2.4 Unauthenticated `/` redirects to the Vaadin `LoginView` (not Spring's generated page) — c674b77
- [x] 2.5 Login with owner credentials lands on `HomeView` inside `MainLayout` — c674b77
- [x] 2.6 Logout returns to login and invalidates the session — c674b77
- [x] 2.7 `/actuator/health` returns 200 unauthenticated — c674b77
- [x] 2.8 Wrong password shows the `LoginForm` error state — c674b77

### Phase 3: DB-backed owner store + idempotent bootstrap

#### Automated

- [x] 3.1 Compiles: `mvnw.cmd -q compile`
- [x] 3.2 Existing tests still green: `mvnw.cmd test`
- [x] 3.3 Full build: `mvnw.cmd verify`

#### Manual

- [x] 3.4 Flyway applies `V2__users.sql` and the app boots (no `ddl-auto=validate` mismatch)
- [x] 3.5 First boot seeds the owner from `OWNER_*`; restart does not duplicate it
- [x] 3.6 Login with the seeded owner succeeds end-to-end (Vaadin login → home)
- [x] 3.7 Wrong credentials rejected

### Phase 4: Lock with tests + verify cross-device deploy

#### Automated

- [ ] 4.1 New unit tests pass: `mvnw.cmd test -Dtest=OwnerAccountTests,OwnerBootstrapTests`
- [ ] 4.2 Gating test green (DB-free) against DB-backed config: `mvnw.cmd test -Dtest=SecurityGatingTests`
- [ ] 4.3 Full build + suite: `mvnw.cmd verify`

#### Manual

- [ ] 4.4 Railway deploy (built with `-Pproduction`) comes up healthy
- [ ] 4.5 Deployed app serves the Vaadin login; login works from two devices (FR-002) and logout works on each
- [ ] 4.6 No data routes reachable unauthenticated (privacy NFR spot-check)
