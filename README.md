# GarageOps

A single-owner web app for managing a portfolio of rental garages. It replaces the
owner's Excel sheet with a tool that actively surfaces the decisions that matter today:
which contracts have **overdue payments** (cumulative balance, FR-013), which garages
are **currently vacant**, which contracts are **ending in the next 30 days**, and which
tenants show a **late-payer pattern**.

Garage rentals (roughly 50–300 PLN/month per unit in the Polish market) are too small to
justify generic property-management SaaS — GarageOps is deliberately scoped to one named
owner, one portfolio, no marketplace, no online payments. See
[`context/foundation/prd.md`](context/foundation/prd.md) for the full product spec.

## Domain

Locations → garages → tenants → rental contracts → payments, plus a landing dashboard
with the three action signals (overdue / vacant / ending soon). Deletion is
**archive-only** by design: archiving stamps `archived_at` and retains all underlying
contracts and payments — nothing is ever hard-deleted (PRD guardrail: no silent data
loss).

## Stack

- Java 21, Spring Boot 4.0.6 (`spring-boot-starter-webmvc`), Maven
- Vaadin Flow (server-side Java UI) + Spring Security (DB-backed single-owner login, BCrypt)
- PostgreSQL + Flyway migrations (`spring.jpa.open-in-view=false`, `ddl-auto=validate`)
- JUnit 5 test suite; risk-driven test plan in [`context/foundation/test-plan.md`](context/foundation/test-plan.md)
- Deployed on Railway (`railway.json`, Dockerfile)

## Build & run

JDK 21 must be on `JAVA_HOME`. Use the Maven wrapper (`mvnw.cmd` on Windows, `./mvnw`
elsewhere):

```
mvnw.cmd spring-boot:run       # dev server with devtools hot-reload
mvnw.cmd test                  # run the JUnit 5 suite
mvnw.cmd test -Dtest=ClassName#methodName   # single test
mvnw.cmd verify                # full build + tests (run before pushing)
mvnw.cmd package               # runnable jar into target/
```

## Repository layout

- `src/main/java/com/example/garageops/` — sources, packaged by feature
  (`locations`, `garages`, `tenants`, `contracts`, `payments`, `dashboard`, `account`, `security`)
- `src/test/java/com/example/garageops/` — tests, mirroring the main layout (classes end in `Tests`)
- `context/foundation/` — product source of truth: `prd.md`, `shape-notes.md`,
  `roadmap.md`, `tech-stack.md`, `infrastructure.md`, `test-plan.md`
- `context/changes/` — per-change working folders; `context/archive/` is read-only
- `AGENTS.md` — contributor guidelines (coding style, hard rules, commands)
