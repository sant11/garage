---
project: garageops
researched_at: 2026-05-23
recommended_platform: Railway
runner_up: Fly.io
context_type: mvp
tech_stack:
  language: Java 21
  framework: Spring Boot 4.0.6
  runtime: JVM
  build: Maven
  database: Postgres (managed, co-located on chosen platform)
---

## Recommendation

**Deploy GarageOps on Railway.**

Railway is the cleanest fit for a solo, after-hours, 3-week Spring Boot MVP under a "minimize cost" constraint with a single-region audience and a preference for a co-located managed database. It scored Pass on all five agent-friendly criteria, has the only published `llms.txt` of the three viable candidates, ships an official Remote MCP server, and provisions managed Postgres as a same-project add-on with usage-billed pricing typically under $1/mo at MVP scale. The realistic monthly cost (~$5–8 including the Hobby plan, JVM RAM, and Postgres) is materially below Fly.io (~$42–44/mo once Fly Managed Postgres' $38 floor lands) and Render (~$26/mo once the JVM-friendly tier and a 1GB Postgres are paired). The original tech-stack.md hint of Fly.io was made before Fly's Managed Postgres pricing was load-bearing — this research updates that call.

## Platform Comparison

### Scoring matrix

| Platform | CLI-first | Managed | Agent docs | Stable deploy API | MCP / integration | Total |
|---|---|---|---|---|---|---|
| **Railway** | Pass | Pass | Pass | Pass | Pass | **5 / 5** |
| **Fly.io** | Pass | Pass | Partial | Pass | Partial | **3P + 2◐** |
| **Render** | Pass | Partial | Partial | Pass | Pass | **3P + 2◐** |
| Cloudflare Containers | Pass | Pass | Pass | Pass | Pass | (excluded — co-location mismatch) |
| Vercel | — | — | — | — | — | dropped (no JVM) |
| Netlify | — | — | — | — | — | dropped (no JVM) |

### Scoring notes per platform

- **Railway — CLI**: `railway up / down / redeploy / logs / variables / link`, JSON output, `RAILWAY_TOKEN` env-var auth for CI. Pass.
- **Railway — Managed**: Railpack (beta) and Nixpacks auto-detect Maven `pom.xml`; Dockerfile is first-class. Pass.
- **Railway — Docs**: Markdown-first with `.md` per page and a published `llms.txt` index with "For AI Agents" section. Best in class. Pass.
- **Railway — Deploy API**: `railway up --detach`, `railway down` for rollback, deterministic exit codes. Pass.
- **Railway — MCP**: Official Remote MCP at `mcp.railway.com` (OAuth) plus the `railwayapp/railway-mcp-server` repo. Pass.

- **Fly.io — CLI**: `flyctl` is mature; `fly deploy`, `fly releases` + `fly deploy --image <prev>` for rollback, `fly logs`, `fly secrets`, `fly scale`. Pass.
- **Fly.io — Managed**: Managed Machines; user owns the Dockerfile but TLS/routing/scaling are platform-handled. Pass.
- **Fly.io — Docs**: Hosted HTML at `fly.io/docs`, no verified `llms.txt`. WebFetch renders cleanly to markdown but it's not a published agent surface. Partial.
- **Fly.io — Deploy API**: `fly deploy --remote-only`, deterministic, scriptable. Pass.
- **Fly.io — MCP**: `fly mcp server` is bundled with `flyctl` but **explicitly marked `[experimental]`** (checked 2026-05-23). Official GitHub Action exists. Partial.

- **Render — CLI**: `render-oss/cli` is GA (v2.18.0), `services create`, `deploys create --wait`, `logs`, JSON output, `RENDER_API_KEY` env for CI. Pass.
- **Render — Managed**: **No native Java runtime — Docker only.** The user owns a multi-stage Dockerfile. Higher operational surface than Railway's auto-detect. Partial.
- **Render — Docs**: Markdown-rendered, no `llms.txt` verified, bundles agent-skill packages with the CLI. Partial.
- **Render — Deploy API**: Deploy hooks (URL webhooks), CLI, REST API, non-zero exits on failure. Pass.
- **Render — MCP**: Official Render MCP server is GA (launched Aug 2025) at `mcp.render.com/mcp`, 20+ tools. Pass.

### Shortlisted Platforms

#### 1. Railway (Recommended)

Wins on cost (~$5–8/mo all-in), agent-readable docs (only one with a published `llms.txt`), default always-on (no JVM cold-start UX hit on Hobby), and a managed Postgres add-on that costs cents per month at MVP scale. CLI + Remote MCP together give the agent unattended ops. Auto-detection of a Maven Spring Boot project means the smallest day-1 surface to maintain.

#### 2. Fly.io (Runner-up)

Strongest community runway for JVM-on-PaaS, official GitHub Action for CI deploys, and `flyctl` is the most mature CLI of the three. Lost on two material points: (a) Fly Managed Postgres' $38/mo floor turns a $5/mo MVP into a $42/mo MVP — the original tech-stack hint preceded that pricing reality, and the unmanaged Postgres is now officially "unsupported"; (b) `fly mcp server` is still experimental. Pick this if Railway becomes blocked on cost-cap predictability or Railpack stability.

#### 3. Render

Genuinely agent-operable in 2026 — the CLI is GA and the MCP shipped Aug 2025 with 20+ tools. Lost on (a) no native Java runtime (Docker is the only path, raising operational surface) and (b) realistic floor is ~$26/mo for a JVM-friendly tier plus a non-expiring Postgres. The free tier's 30-day Postgres expiry and 15-minute spin-down make it unusable as a demo target for a Spring Boot app.

## Anti-Bias Cross-Check: Railway

### Devil's Advocate — Weaknesses

1. **Spring Boot 4.x is too new for Railway's auto-detection to be a "verified" path.** Railway's official Spring Boot guide example targets Java 17 / Spring Boot 3.x; Railpack is **beta** (pre-v1) and the new `spring-boot-starter-webmvc` artifact name in 4.x isn't documented as covered. First deploy will probably work; a subsequent builder update could silently change behavior.
2. **No `pause` / billing-hold for a personal project.** Letting the project idle does NOT stop charges on Hobby. To stop billing, delete the service or downgrade the account.
3. **`railway up` deploys the local directory, not `git archive HEAD`.** Half-finished local changes ship to production unless the user deploys from a clean tree. Anything on disk but in `.gitignore` (e.g., a stray `.env`) gets uploaded.
4. **JVM memory tuning is on the user, and Railway bills by GB-RAM-month.** A Spring Boot 4 app with Hibernate + Hikari + Flyway + Actuator can sit at 600–800 MB resident, pushing past the $5 Hobby included credit into $8–12/mo overage.
5. **Hobby plan = single environment.** Preview-deploy-per-PR requires Pro ($20/mo). Not blocking for the MVP — the tech-stack `auto-deploy-on-merge` flow doesn't need previews — but flagged for future evolution.

### Pre-Mortem — How This Could Fail

It's November 2026. GarageOps has been running for five months. Six weeks ago, Railway rolled Railpack from beta-0.x to v1, and the build subtly changed how it handled the Spring Boot 4 layered jar — switching to a single fat jar that pushed the image 80MB larger. The solo developer hadn't redeployed in that window, so the change sat dormant in the builder. When the owner asked for a small UI tweak and the dev pushed a commit, the auto-build silently picked up the new behavior and the container OOM'd at startup against the 512MB ceiling. The dashboard went dark at 8pm Sunday. The dev wasn't monitoring (no APM in MVP scope), saw the alert Tuesday morning. Two days of "garages went red and I couldn't act" — the owner's first-impression problem from the PRD lived through. In hindsight, the failure shape was "shipping on beta tooling without pinning the builder, then trusting auto-detect to behave the same way at v1." Ten extra minutes of writing a Dockerfile at week 1 would have given predictable, version-locked build behavior. The "auto-detect just works" pitch hid the trade-off.

### Unknown Unknowns

- **Railway's "Add Postgres" template gives a public-internet endpoint by default.** The displayed connection string is the public host. To get private intra-project networking, switch to `postgres.railway.internal`. The PRD's privacy guardrail makes this consequential.
- **Spring Boot 4 bootstrapper templates ship `spring-boot-devtools` by default.** In production this exposes restart/livereload behavior. Railway deploys whatever is built — scope devtools to `runtime` + `optional` or strip it before first prod deploy.
- **Railway's "always-on" default + no auto-spin-down means an abandoned project keeps billing.** The opposite discipline of Render's free tier. Set a usage cap and a monthly billing alert at account creation.
- **`railway up` uploads the local directory tarball, not `git archive HEAD`.** Files in `.gitignore` but on disk ship to prod. Use `railway up` only from a clean working tree, or prefer git-triggered deploys via the Railway GitHub integration.
- **Railpack auto-detection of `pom.xml` does not pin a JDK version by default.** Pin Java 21 explicitly via `NIXPACKS_JDK_VERSION=21` or in a Dockerfile from day 1, regardless of which builder is in use.

## Operational Story

- **Preview deploys**: not used in MVP. Hobby plan supports one environment (production). Auto-deploy-on-merge to `main` runs via Railway's GitHub integration. Preview-per-PR is a Pro plan feature deferred to post-MVP.
- **Secrets**: stored in Railway service Variables (encrypted, scoped per service). Set via `railway variables set KEY=value` from the CLI or in the dashboard. Database connection string injected by the Postgres add-on as `DATABASE_URL`. Spring Security signing keys and any future API keys live here, never in `application.properties` or git.
- **Rollback**: `railway down` rolls back to the previous successful deployment. CLI returns deterministic exit codes. Approximate time-to-revert: 30–90 seconds. **Caveat:** schema migrations applied via Flyway are NOT auto-reversed — a destructive migration requires a forward-fix migration, not a rollback.
- **Approval**: `railway up`, `railway down`, `railway logs`, `railway variables list` may run unattended. **Human-gated**: deleting the Postgres add-on, rotating `DATABASE_URL`, deleting the service, and any change to billing tier or usage cap. These are panel-by-hand by the owner.
- **Logs**: `railway logs --service garageops` (runtime) and `railway logs --build` (build pipeline). Both stream non-interactively for the agent. Structured logs via Spring Boot's JSON logging recommended for grep-ability.

## Risk Register

| Risk | Source | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| Railpack v0.x → v1 cutover changes Spring Boot 4 build behavior; silent OOM on next deploy | Pre-mortem | M | H | Use a pinned `Dockerfile` (multi-stage `maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre`) instead of Railpack auto-detect. Pin Java version regardless of builder. |
| Spring Boot 4.x not documented on Railway's Spring Boot guide (only 3.x / Java 17) | Devil's advocate | M | M | Smoke-deploy a hello-world Spring Boot 4 service before wiring real domain code. Capture the working `pom.xml` + `Dockerfile` in the repo. |
| Per-GB-RAM billing surprises with JVM overhead; $5 Hobby credit insufficient | Devil's advocate | M | L | Set `-XX:MaxRAMPercentage=75`, prefer `-XX:+UseSerialGC` at 512MB, monitor first month closely. Set Railway usage cap. |
| Forgotten project keeps billing forever (no auto-spin-down) | Unknown unknowns | M | M | Set a monthly usage cap + email alert at account creation. Calendar reminder to review monthly billing for the first 6 months. |
| `railway up` ships local dirty state, including `.env`-style files | Devil's advocate / Unknown unknowns | M | H | Use git-triggered deploys via the Railway GitHub integration (auto-deploy-on-merge to `main`). Reserve `railway up` for explicit one-off ops from clean tree. |
| Postgres add-on exposes a public-internet endpoint by default | Unknown unknowns | H | H | Use `postgres.railway.internal` in `DATABASE_URL` for the Spring Boot app. Verify with `nc` or a connection trace that public host is not in use. Document this in CLAUDE.md before first deploy. |
| `spring-boot-devtools` ships to production by accident | Unknown unknowns | M | M | Scope devtools to `<scope>runtime</scope>` + `<optional>true</optional>` in `pom.xml`, or strip it entirely. Verify with `mvn dependency:tree` before first prod deploy. |
| Fly MPG $38/mo floor would have made the original tech-stack hint wrong | Research finding | — | — | Recorded here as the reason for Railway over Fly. If Railway becomes unviable, the runner-up calculus must include this cost reality. |
| Schema migrations not auto-reversed on `railway down` | Research finding | M | H | Treat Flyway migrations as forward-only. Never write destructive migrations; backfill + drop in two releases with a manual gate between. |
| Single environment on Hobby blocks PR-preview workflows | Devil's advocate | L | L | Not blocking for MVP (auto-deploy-on-merge is the chosen CI flow). Revisit at Pro upgrade if preview-per-PR becomes load-bearing. |

## Getting Started

These steps are specific to **Spring Boot 4.0.6 / Java 21 / Maven** on Railway, validated against the research evidence captured above. CLI commands assume `railway` CLI v3+ and a Windows shell (PowerShell) per the project environment.

1. **Install the Railway CLI** and authenticate:
   ```powershell
   npm i -g @railway/cli
   railway login --browserless
   ```
2. **Strip `spring-boot-devtools` from production scope** in `pom.xml` (or remove it entirely if not used in dev). Verify with `mvnw.cmd dependency:tree | findstr devtools`.
3. **Add a pinned multi-stage Dockerfile to the repo root** — do NOT rely on Railpack auto-detect for a Spring Boot 4 + Java 21 build. Suggested skeleton:
   ```dockerfile
   FROM maven:3.9-eclipse-temurin-21 AS build
   WORKDIR /app
   COPY pom.xml .
   RUN mvn dependency:go-offline
   COPY src ./src
   RUN mvn package -DskipTests

   FROM eclipse-temurin:21-jre
   WORKDIR /app
   COPY --from=build /app/target/*.jar app.jar
   ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
   EXPOSE 8080
   CMD ["java", "-jar", "app.jar"]
   ```
4. **Initialize the Railway project** from the repo:
   ```powershell
   railway init
   railway link
   ```
5. **Provision Postgres** as a service in the same project (dashboard or CLI), then capture the **internal** connection URL: switch the default `DATABASE_URL` host to `postgres.railway.internal`. Set as a Variable on the GarageOps service.
6. **Set Spring profile and JVM tuning vars**:
   ```powershell
   railway variables set SPRING_PROFILES_ACTIVE=prod
   railway variables set SERVER_PORT=8080
   ```
7. **Configure the GitHub integration** for auto-deploy-on-merge to `main` (per `tech-stack.md` `ci_default_flow`). The Railway GitHub App watches the repo and triggers builds; `railway up` is reserved for one-off ops only.
8. **Set a monthly usage cap and billing alert** in the Railway dashboard before the first deploy. Without this, the "no auto-spin-down" risk has no guardrail.
9. **Smoke-test before wiring domain code**: deploy a `/health` endpoint and `mvnw verify`-built jar, confirm cold start time + steady-state memory under load. Capture the baseline.
10. **Document the deploy + rollback runbook** at `context/deployment/deploy-plan.md` (Plan Mode artifact, downstream skill).

## Out of Scope

The following were not evaluated in this research:
- Docker image configuration beyond the skeleton Dockerfile above (caching strategy, distroless base, build-arg secrets)
- CI/CD pipeline setup (GitHub Actions workflow YAML) — handled by Plan Mode deploy
- Production-scale architecture (multi-region failover, HA Postgres, read replicas, dedicated support tiers)
- Cost projections beyond MVP scope (sustained > 10k req/day, multi-environment topology)
- Backup strategy beyond what Railway Postgres provides on Hobby (daily snapshots only; PITR is Pro-only)
