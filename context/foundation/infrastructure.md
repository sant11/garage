---
project: garageops
researched_at: 2026-05-24
recommended_platform: Railway
runner_up: Render
context_type: mvp
tech_stack:
  language: Java 21
  framework: Spring Boot 4.0.6
  runtime: JVM (HotSpot)
  build: Maven
  database: PostgreSQL (managed, co-located)
---

## Recommendation

**Deploy on Railway.**

Railway scores 5/5 across the agent-friendly criteria, runs Spring Boot 4 / Java 21 with Maven auto-detection via Railpack, ships an official MCP server + Claude Code plugin, and lands at a realistic floor of **~$5/month** (Hobby plan: $5/mo includes $5 of usage credit, which covers a 512MB Spring Boot service + small managed Postgres at MVP traffic). The interview confirmed cost minimization is the top constraint and single-region EU is sufficient — Railway's Amsterdam region serves the Polish owner at ~30ms latency, and the platform's co-located managed Postgres satisfies the co-location preference. The cost gap to Render (~3×) and the managed-Postgres economics on Fly.io ($38/mo floor) settled the decision.

## Platform Comparison

### Scoring matrix (5 agent-friendly criteria, verified 2026-05-24)

| Platform | CLI-first | Managed / Serverless | Agent-readable docs | Stable deploy API | MCP / Integration | Total |
|---|---|---|---|---|---|---|
| **Railway** | Pass | Pass | Pass | Pass | Pass | **5/5** |
| **Render** | Pass | Pass | Pass | Pass | Pass | **5/5** |
| **Fly.io** | Pass | Pass | Partial | Pass | Partial | **3.5/5** |
| Cloudflare Workers | — | — | — | — | — | **DROPPED (no JVM)** |
| Vercel | — | — | — | — | — | **DROPPED (no JVM)** |
| Netlify | — | — | — | — | — | **DROPPED (no JVM)** |

**Hard filter applied first**: Cloudflare Workers, Vercel Functions, and Netlify Functions support JS / Python / Go / Ruby / Rust / WASM but not JVM. A Spring Boot jar cannot be deployed natively to any of them (confirmed against each platform's runtime support docs, 2026-05-24). They are excluded from scoring.

### Shortlisted platforms

#### 1. Railway (Recommended)

- **CLI-first**: `railway` CLI covers `up` (deploy), `redeploy`, `restart`, `down`, `logs`, `variable set/list`, `service`, `scale`, `delete`. Non-interactive auth via `RAILWAY_TOKEN` / `RAILWAY_API_TOKEN`. No dedicated `rollback` — rollback is `redeploy <earlier-deployment>`.
- **Managed**: Railpack (successor to Nixpacks) auto-detects Maven; Spring Boot fat-jar launch is `java -Dserver.port=$PORT $JAVA_OPTS -jar target/*jar` by default. Managed Postgres provisioned via `+ New` with SSL on, exposes `DATABASE_URL` and `PG*` env vars.
- **Docs**: `llms.txt` published at `railway.com/llms.txt`; every `docs.railway.com/...` URL has a `.md` sibling; source repo on GitHub (`railwayapp/docs`).
- **Deploy API**: `railway up --detach --json`, idempotent env-var management, GitHub Actions integration documented.
- **MCP / integration**: Official Railway MCP server (`@railway/mcp-server`, remote at `https://mcp.railway.com` with OAuth) + official Claude Code plugin with skills/hooks. GA status of MCP not explicitly stated on the docs page fetched — treat as recently-launched.
- **Cost**: Hobby $5/mo includes $5 credit; rates $10/GB-month RAM, $20/vCPU-month, $0.15/GB-month volume, $0.05/GB egress. Realistic MVP floor ~$5/mo at single-tenant traffic.
- **EU region**: Amsterdam-only (`europe-west4-drams3a`). No Frankfurt.

#### 2. Render

- **CLI-first**: `render` CLI is GA (v2.18.0), `deploys create`, log tailing, `psql`, `-o json` output, `--confirm` for non-interactive CI. Auth via `RENDER_API_KEY`.
- **Managed**: Java path is Dockerfile (no native Maven buildpack listed); managed Postgres co-located in the same region.
- **Docs**: Publishes both `llms.txt` and **`llms-full.txt`** — the richest agent doc surface in the candidate pool. Every doc article reachable as `.md`.
- **Deploy API**: Deploy hooks (webhook URL), official GitHub Action, CLI.
- **MCP / integration**: Official Render MCP server GA since August 2025 (`https://mcp.render.com/mcp`, 20+ tools) + three official "Skills" (render-deploy / render-debug / render-monitor). **Caveat**: MCP does NOT trigger deploys, modify scaling, or delete resources.
- **Cost**: Starter web $7/mo + Starter Postgres $7/mo = ~$14/mo realistic floor. Free tier exists (512MB, 750hrs/mo, 15-min idle spin-down, ~60s cold-start) but **not practically viable for Spring Boot** — JVM cold-start adds 10-30s on top of Render's spin-up, breaking the PRD's "1-second acknowledgement" NFR.
- **EU region**: Frankfurt GA (sole EU region). Closer to PL than Amsterdam.

#### 3. Fly.io

- **CLI-first**: `flyctl` mature — `fly deploy`, `fly logs`, `fly secrets`, `fly scale`, `fly releases --json`. No `fly rollback`; rollback is `fly deploy --image <prior-ref>`.
- **Managed**: Dockerfile-only for Java — `fly launch` has no Maven auto-detection. Multi-stage `eclipse-temurin:21-jdk → eclipse-temurin:21-jre` is the de-facto pattern.
- **Docs**: HTML-only, no `llms.txt` (`fly.io/docs/llms.txt` returns 404), no GitHub markdown source. Convertible to markdown via WebFetch but not natively agent-formatted. **Partial**.
- **Deploy API**: Stable, scriptable, mature GitHub Actions (`superfly/flyctl-actions/setup-flyctl@master`).
- **MCP / integration**: `superfly/flymcp` exists + `fly mcp launch` in flyctl ≥ 0.3.125, but **the repo has 4 commits and no tagged releases** — early/experimental. **Partial**.
- **Cost**: Compute is cheap (shared-cpu-1x 512MB ≈ $3.32/mo in `fra`), but **Fly Managed Postgres minimum tier is $38/mo** (Basic, shared CPU, 1GB RAM). Co-location is satisfied only at this price. The cheap-compute + external-DB pattern (Neon/Supabase free tier) breaks the user's co-location preference.
- **EU region**: Frankfurt, Amsterdam, Paris, London, Stockholm. No Warsaw.

## Anti-Bias Cross-Check: Railway

### Devil's Advocate — Weaknesses

1. **EU presence is single-region (Amsterdam) and recently incident-prone.** Northflank's outage commentary cites Amsterdam as disproportionately affected by the December 2025 fleet-wide resource-exhaustion incident, and notes multiple major incidents on Railway since November 2025. For a tool whose value is "I open it daily to act on overdue payments", a 3-hour Saturday outage is high-impact relative to a $5/month spend.
2. **No documented Spring Boot 4.x reference.** Railway's official Spring Boot guide targets Boot 3.3.4 / Java 17. Spring Boot 4.0 changed several auto-config defaults and renamed starters (`spring-boot-starter-webmvc` per `AGENTS.md`); Railpack auto-detection has a community history of friction on Java versions (Help Station thread: "java release version jdk 21 not supported" until `NIXPACKS_JDK_VERSION=21` was set). Boot 4 may need a fallback Dockerfile that Railway docs do not yet ship.
3. **Managed Postgres backups are NOT enabled by default.** Railway's Backups feature is opt-in. The PRD's "No silent data loss" guardrail (Success Criteria → Guardrails) is exactly the foot-gun this targets — easy to skip on day-one provisioning.
4. **The $5 floor is a credit, not a cap.** Per-second metering means a Spring Boot OOM-restart loop on a 512MB machine can burn the credit in hours and silently begin overage charges. "Minimize cost" assumes a stable floor, but the floor breaks the moment usage exceeds $5 — and JVM apps press memory limits more easily than Node/Go apps.
5. **MCP server auth is account-scoped, not project-scoped.** Wiring Claude into the Railway MCP grants read/write to **all** projects on the account, not just GarageOps. Manageable for a solo developer with one project; risk grows with project count.

### Pre-Mortem — How This Could Fail

Six months in, the GarageOps owner is uneasy. The "$5/month MVP" promise held for the first six weeks while the Postgres was empty and the JVM idled at 380MB. Then the payment ledger accumulated — every owner-recorded payment per FR-013, every contract history per FR-011 — and by month three the Postgres crossed 1GB and the JVM heap began pressing 512MB during dashboard queries. Railway began OOM-restarting twice a week; each restart chewed credit. On a Saturday in March, the Amsterdam region went down for three hours during the exact moment the owner was trying to re-list an empty garage — the dashboard's job. When the app came back, the owner discovered Backups had never been toggled on: the implementation step provisioned the Postgres add-on but no one enabled the daily snapshot. Recovery worked because the volume survived, but the trust collapse from "I almost lost my tenant data" reversed the Excel → GarageOps migration. By month seven the bill was $24/mo, and the owner had quietly moved back to the spreadsheet.

### Unknown Unknowns

- **CLOUD Act exposure.** Railway is US-incorporated; Polish tenant PII (FR-007 contact info, FR-012 payment histories) sits in a US-controlled platform's EU datacenter. The PRD calls privacy a Guardrail — below the disqualification threshold, above the "should know" threshold.
- **PR Preview Environments inherit secrets from the main service by default.** Unless overridden at preview-creation time, a PR preview can point at the production Postgres — a real foot-gun for a single-tenant privacy-critical app.
- **Railpack is the default builder now (superseded Nixpacks in 2025).** Some docs reference Nixpacks, some Railpack; Spring Boot 4 quirks not covered in either. Expect docs lag to bite once on any non-trivial build configuration.
- **App-sleeping breaks the 1-second NFR.** Opt-in feature; if enabled, the JVM cold-start (5-15s for Spring Boot) directly violates the PRD's Non-Functional Requirement that "the owner sees acknowledgement of any input within 1 second on the dashboard and on primary CRUD operations".
- **Outbound egress is metered at $0.05/GB.** Negligible at MVP scale, but billed — relevant if bulk exports later route through the app.

## Operational Story

- **Preview deploys**: Railway PR Environments are first-class and auto-deleted on PR close. **Critical: override the environment in PR previews to use a non-production Postgres** — by default, previews inherit main-service env vars including `DATABASE_URL`. For GarageOps's single-tenant privacy-critical posture, configure preview environments with a dedicated empty Postgres or skip previews entirely until a separation pattern is set.
- **Secrets**: Live in Railway's per-service environment variable store, set via `railway variable set KEY=value` or dashboard. `RAILWAY_TOKEN` (project-scoped) and `RAILWAY_API_TOKEN` (account-scoped) used for CI; store as GitHub Actions secrets, never commit. Rotation: regenerate token in Railway dashboard, update GitHub secret, re-run workflow.
- **Rollback**: `railway redeploy <deployment-id>` against an earlier deployment (no dedicated `rollback` verb). Time-to-revert: ~30-60s for a small Spring Boot jar. **Caveat**: database schema migrations do not roll back automatically — Flyway/Liquibase backward-compatible migration discipline required, or accept manual recovery.
- **Approval**: Human-only operations: deleting a Postgres add-on, rotating `RAILWAY_API_TOKEN`, deleting a project, enabling/disabling Backups, modifying the production environment's secrets. Agent-permitted: `railway up` (deploy from current branch), `railway logs`, `railway variable list`, listing deployments — read-only ops and the deploy that the agent owns end-to-end.
- **Logs**: `railway logs --service <name>` for runtime logs, `railway logs --build` for build logs, `railway logs -n 200 --json` for structured tail consumable by the agent. Railway MCP server also exposes log tools as structured calls.

## Risk Register

| Risk | Source | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| Amsterdam-region outage during high-need owner action | Devil's advocate / Research finding | Medium | High | Accept single-region risk for MVP; document the manual workaround (owner falls back to Excel for the outage window). Revisit multi-region post-MVP if pattern emerges. |
| Spring Boot 4 + Java 21 build fails on Railpack auto-detect | Devil's advocate | Medium | Medium | Have a fallback `Dockerfile` (multi-stage `eclipse-temurin:21`) ready to commit if `railway up` fails. Document `NIXPACKS_JDK_VERSION=21` as a fallback variable. |
| Postgres backups left disabled | Devil's advocate / Pre-mortem | High (until done) | Catastrophic (PRD guardrail violation) | **Day-one task**: enable Backups in Railway dashboard immediately after Postgres provisioning. Verify with `railway variable list` includes backup retention setting. Make this a deploy-plan acceptance check. |
| $5 floor exceeded silently via OOM-restart loop | Devil's advocate | Medium | Low-Medium | Set memory limit explicitly via `JAVA_OPTS=-XX:MaxRAMPercentage=75.0` and a Railway usage alert at 80% of $5 credit. |
| Spring Boot data growth crosses Postgres tier threshold | Pre-mortem | Medium (6+ months) | Medium | Set up a monthly cost review at month 3 and month 5 against `railway` usage report. Plan upgrade path to next Postgres tier in advance. |
| MCP server account-scoped token exposes other projects | Devil's advocate / Unknown unknowns | Low (1 project today) | Medium | Keep `RAILWAY_API_TOKEN` out of `.mcp.json` committed to repo — load from local env only. Audit token scope when wiring MCP. |
| PR Preview points at production Postgres | Unknown unknowns | Medium (if previews enabled without override) | High | Defer PR Previews until a dedicated preview database pattern is set, OR configure each preview environment with a sandbox `DATABASE_URL` before enabling. |
| App-sleeping breaks 1-second NFR | Unknown unknowns | High if enabled | High (NFR violation) | **Do not enable app-sleeping** for GarageOps. Keep one machine always-on. The owner's daily-use pattern doesn't benefit from sleep savings. |
| CLOUD Act exposure for PL tenant PII | Unknown unknowns | Low operational, real legal | Medium | Document in privacy posture. Not a blocker; revisit if tenant base grows beyond owner-only. |
| Railpack docs lag for Spring Boot 4 | Devil's advocate / Unknown unknowns | Medium | Low | Bookmark the Help Station thread + community Spring Boot guide; have Dockerfile fallback ready. |

## Getting Started

These commands are validated against the exact stack in `tech-stack.md` (Spring Boot 4.0.6, Java 21, Maven, `pom.xml` already present from `/10x-bootstrapper`).

1. **Install the Railway CLI on Windows** (per JDK-locations memory, this is a Windows machine):

   ```powershell
   npm install -g @railway/cli
   # or via scoop: scoop install railway
   railway --version  # verify
   ```

2. **Authenticate (browserless flow recommended for headless / agent use)**:

   ```powershell
   railway login --browserless
   # Follow the printed URL and paste the device code
   ```

3. **Initialize the project from the repo root**:

   ```powershell
   railway init
   # Pick: New Project → name "garageops"
   ```

4. **Provision managed Postgres co-located in EU (Amsterdam)**:

   ```powershell
   railway add --database postgres
   # Sets PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE, DATABASE_URL automatically
   ```

5. **Wire Spring Boot's datasource to Railway's `PG*` env vars.** *Important*: Railway exposes `DATABASE_URL` in `postgresql://user:pass@host:port/db` form, NOT JDBC form — using it as `SPRING_DATASOURCE_URL` directly will fail. Use the `PG*` variables instead. In `src/main/resources/application.properties`:

   ```properties
   spring.datasource.url=jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}
   spring.datasource.username=${PGUSER}
   spring.datasource.password=${PGPASSWORD}
   spring.datasource.driver-class-name=org.postgresql.Driver
   ```

   Add the PostgreSQL driver to `pom.xml`:

   ```xml
   <dependency>
     <groupId>org.postgresql</groupId>
     <artifactId>postgresql</artifactId>
     <scope>runtime</scope>
   </dependency>
   ```

6. **Configure JVM container-awareness for the 512MB tier** (the default JVM heuristics don't size correctly against Railway's per-second memory billing). Set a Railway env var:

   ```powershell
   railway variable set JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
   ```

7. **Add Spring Boot Actuator for health checks** (Railway pings the service to gauge readiness; the actuator endpoint avoids restart loops on slow JVM warmup). In `pom.xml`:

   ```xml
   <dependency>
     <groupId>org.springframework.boot</groupId>
     <artifactId>spring-boot-starter-actuator</artifactId>
   </dependency>
   ```

   In `application.properties`:

   ```properties
   management.endpoints.web.exposure.include=health
   management.endpoint.health.probes.enabled=true
   ```

   In Railway dashboard → Service → Settings → Healthcheck Path: `/actuator/health` with a 30-60s grace period.

8. **Deploy**:

   ```powershell
   $env:JAVA_HOME = "C:\Install\jdk-21.0.2\jdk-21.0.2"   # per memory: override default JDK 11
   .\mvnw.cmd package -DskipTests
   railway up
   ```

9. **CRITICAL day-one step (do not skip): enable Postgres Backups.**
   - Open Railway dashboard → Postgres service → Settings → **Backups → Enable daily backups**.
   - Without this step, the PRD's "No silent data loss" guardrail is violated. There is no CLI flag for this as of 2026-05-24 — it is a dashboard click. Treat it as a manual gate in the deploy plan.

10. **Tail logs and verify the service is up**:

    ```powershell
    railway logs --service garageops -n 100
    # In a browser, open the generated railway.app URL and confirm /actuator/health returns {"status":"UP"}
    ```

11. **(Optional) Wire the Railway MCP server for Claude**: install the official Claude Code plugin per Railway's docs (`docs.railway.com/ai/claude-code-plugin`). Use a personal access token, store it in your local env, NOT in any committed `.mcp.json`.

## Out of Scope

The following were not evaluated in this research:

- Docker image configuration (Railway's Railpack handles the Maven build; a fallback Dockerfile is mentioned as a contingency in the risk register, not designed here).
- CI/CD pipeline setup (`tech-stack.md` plans GitHub Actions + auto-deploy-on-merge — pipeline design happens in the Plan Mode deploy step or `/10x-implement`).
- Production-scale architecture: multi-region failover, HA replicas, DR runbook, SLA commitments. Single-tenant single-owner scale per the PRD does not justify multi-region in MVP; revisit when the tenant base or uptime expectations change.
- Spring Security configuration for FR-001/FR-002 email+password auth — this is implementation work, not platform research.
- Migration tooling choice (Flyway vs Liquibase) — relevant to the rollback caveat but is an implementation decision.
