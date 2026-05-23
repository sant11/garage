# Repository Guidelines

GarageOps is a single-owner Spring Boot 4.0.6 web app on Java 21, Maven-built. The repo is a fresh scaffold — only the application skeleton and a smoke test exist; the domain (locations, garages, tenants, contracts, payments, dashboard) is specified in `@context/foundation/prd.md`.

## Hard rules

- **Never write to `context/archive/`.** Archived changes are immutable; open a new change with `/10x-new` instead. See `@CLAUDE.md`.
- Spring Boot 4.x uses `spring-boot-starter-webmvc` and `spring-boot-starter-webmvc-test`, **not** `-starter-web` / `-starter-test`. Match the existing `@pom.xml` when adding dependencies.
- The empty `<name>`, `<description>`, `<licenses>`, `<developers>`, `<scm>` blocks in `@pom.xml` are intentional parent overrides — see `@HELP.md`. Do not "fill them in".
- Single-tenant by design (PRD Non-Goals). Never add multi-tenant scoping to entities or queries.
- Deletion is archive-only (FR-021). Do not expose hard-delete UI; archiving must retain underlying contracts and payments.
- `@.gitattributes` pins `mvnw` to LF and `*.cmd` to CRLF. Preserve those line endings when editing the wrappers.

## Project structure

- `src/main/java/com/example/garageops/` — Java sources; root package `com.example.garageops`. New code goes under `com.example.garageops.<feature>`.
- `src/main/resources/application.properties` — Spring config.
- `src/test/java/com/example/garageops/` — tests; mirror the main package layout.
- `context/foundation/` — `prd.md`, `tech-stack.md`, `shape-notes.md` (product source of truth).
- `context/changes/` — per-change working folders. `context/archive/` is read-only (see hard rules).
- `.claude/skills/` — repo-local skills consumed by Claude Code.

## Build, test, dev

Use the Maven wrapper (`mvnw.cmd` on Windows, `./mvnw` elsewhere). `java.version=21` in `@pom.xml` — JDK 21 must be on `JAVA_HOME`.

- `mvnw.cmd spring-boot:run` — start dev server with devtools hot-reload.
- `mvnw.cmd test` — run the JUnit 5 / `@SpringBootTest` suite.
- `mvnw.cmd package` — build the runnable jar into `target/`.
- `mvnw.cmd verify` — full build + tests before pushing.

## Coding style & naming

- Java 21. Source files are tab-indented (see `@src/main/java/com/example/garageops/GarageopsApplication.java` and `@pom.xml`).
- "Use constructor injection only. No @Autowired on fields or setters." (checkable: grep for field-@Autowired.)
- "Annotate web entry points with @RestController, services with @Service, config with @Configuration — never plain @Component for these roles." (checkable: grep for @Component on classes in controller/service/config packages.)
- "Package by feature under com.example.garageops.<feature> (e.g. garageops.contracts, garageops.payments), not by layer (controllers/, services/)." (checkable: any top-level controllers/services package fails review.) — (assumed), confirm against your plan.

## Testing

- JUnit 5 via `spring-boot-starter-webmvc-test`. Test classes end in `Tests` (pattern: `@src/test/java/com/example/garageops/GarageopsApplicationTests.java`).
- Run a single test: `mvnw.cmd test -Dtest=ClassName#methodName`.

## Commits & PRs

- Remote: `github.com/sant11/garage`. Default branch: `main`.
- Commit history is short ("init", "app generated", ...) — no Conventional Commits prefix is enforced yet; write short imperative subjects.
- No CI workflows are wired. `@context/foundation/tech-stack.md` plans GitHub Actions + auto-deploy-on-merge to Fly.io; consult it before adding workflows.
