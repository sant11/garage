---
bootstrapped_at: 2026-05-20T22:52:00Z
starter_id: spring
starter_name: Spring Boot
project_name: garageops
language_family: java
package_manager: maven
cwd_strategy: subdir-then-move
bootstrapper_confidence: verified
phase_3_status: ok
audit_command: "null"
---

## Hand-off

Verbatim from `context/foundation/tech-stack.md`:

```yaml
starter_id: spring
package_manager: maven
project_name: garageops
hints:
  language_family: java
  team_size: solo
  deployment_target: fly
  ci_provider: github-actions
  ci_default_flow: auto-deploy-on-merge
  bootstrapper_confidence: verified
  path_taken: standard
  quality_override: false
  self_check_answers: null
  has_auth: true
  has_payments: false
  has_realtime: false
  has_ai: false
  has_background_jobs: false
```

> Solo developer shipping a single-owner garage portfolio dashboard in 3 weeks
> after-hours. Standard path — Spring Boot is the recommended default for
> `(web-app, java)` and clears all four agent-friendly gates (typed,
> convention-based, popular in training data, well documented) with
> `bootstrapper_confidence: verified`. Auth is in scope (email + password per
> FR-001/002); payments, realtime, AI, and background jobs are explicit
> non-goals in the PRD, so the lean web+devtools template is sufficient. Fly.io
> picked from the card's `deployment_defaults`; GitHub Actions with
> auto-deploy-on-merge fits a solo MVP where the only user is the owner and
> there is no public-facing exposure. Known friction acknowledged in
> conversation: Spring Security ceremony for the CRUD auth requirement, and the
> Thymeleaf vs separate-SPA frontend choice — both deferred to bootstrapper /
> implementation time, neither blocking stack selection.

## Pre-scaffold verification

| Signal       | Value                                             | Severity | Notes                                                                                                       |
| ------------ | ------------------------------------------------- | -------- | ----------------------------------------------------------------------------------------------------------- |
| npm package  | not run                                           | n/a      | spring's `cmd_template` invokes `curl` against `start.spring.io` — not an `npm create` CLI                  |
| GitHub repo  | not run                                           | n/a      | `card.docs_url` is `https://docs.spring.io/spring-boot/`, not a `github.com/<owner>/<repo>` URL              |
| Registry tag | `last_updated: 2026-04-18` (~1 month before run) | fresh    | best available signal — `starters.spring.last_updated` from `references/starter-registry.yaml`               |

No `stale` finding. Proceeded without warning.

## Scaffold log

**Resolved invocation**: `curl -sS https://start.spring.io/starter.tgz -d dependencies=web,devtools -d type=maven-project -d javaVersion=21 -d groupId=com.example -d artifactId=garageops -o starter.tgz && tar -xzf starter.tgz && rm starter.tgz`
**Strategy**: subdir-then-move
**Substitution note**: literal `{name}=.bootstrap-scaffold` substitution would have yielded an invalid Maven `artifactId`. Spring's `{name}` placeholder is the `artifactId` (Maven project metadata) — not the directory name; the Spring Initializr tarball extracts flat into cwd. The strategy's intent (temp-dir + conflict matrix) was preserved by `mkdir .bootstrap-scaffold && cd .bootstrap-scaffold` around the curl/tar; `{name}` was substituted with the hand-off's `project_name` (`garageops`) so the generated `pom.xml` carries a valid artifact id.
**Exit code**: 0
**Files moved**: 10
- `.gitattributes`
- `.gitignore`
- `.mvn/wrapper/maven-wrapper.properties`
- `HELP.md`
- `mvnw`
- `mvnw.cmd`
- `pom.xml`
- `src/main/java/com/example/garageops/GarageopsApplication.java`
- `src/main/resources/application.properties`
- `src/test/java/com/example/garageops/GarageopsApplicationTests.java`

Plus empty directories created: `.mvn/wrapper/`, `src/main/resources/static/`, `src/main/resources/templates/`.

**Conflicts (.scaffold siblings)**: none — no scaffold path collided with a pre-existing cwd file. `CLAUDE.md` and `idea-notes.md` (pre-existing root-level Markdown) are untouched; the scaffold did not ship those names. `HELP.md` is new and moved silently.
**.gitignore handling**: moved silently — cwd had no pre-existing `.gitignore`.
**.bootstrap-scaffold cleanup**: deleted — required `cmd /c rd /s /q` after `rm -rf` failed with "Device or resource busy" (a Windows + Git Bash handle quirk on a freshly-emptied directory; non-blocking).

## Post-scaffold audit

**Tool**: skipped — no built-in audit tool for `java` (`audit_commands.java: null` in `references/bootstrapper-config.yaml`)
**Recommended external tool**: run `mvn dependency:tree` plus the OWASP Dependency-Check Maven plugin (`org.owasp:dependency-check-maven`) for a CVE scan against the `pom.xml` dependency closure. GitHub Dependabot on the repo is the lower-effort alternative once the project is pushed.

## Hints recorded but not acted on

| Hint                       | Value                       |
| -------------------------- | --------------------------- |
| bootstrapper_confidence    | verified                    |
| quality_override           | false                       |
| path_taken                 | standard                    |
| self_check_answers         | null                        |
| team_size                  | solo                        |
| deployment_target          | fly                         |
| ci_provider                | github-actions              |
| ci_default_flow            | auto-deploy-on-merge        |
| has_auth                   | true                        |
| has_payments               | false                       |
| has_realtime               | false                       |
| has_ai                     | false                       |
| has_background_jobs        | false                       |

## Next steps

Next: a future skill will set up agent context (CLAUDE.md, AGENTS.md). For now, your project is scaffolded and verified — happy hacking.

Useful manual steps in the meantime:
- `git init` is already done — the repo was initialised before bootstrap. Stage the new Spring Boot files (`pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `src/`, `HELP.md`, `.gitattributes`, `.gitignore`) when you're ready.
- Verify the Spring Boot scaffold builds: `./mvnw verify` (requires Java 21).
- Address the hand-off's deferred decisions: choose a frontend approach (Thymeleaf vs htmx vs separate SPA per FR §126) and a persistence path (PostgreSQL via Spring Data JPA + Flyway is the natural fit for the FR-007 → FR-013 data model).
- Run the recommended Java vulnerability scan (`org.owasp:dependency-check-maven` plugin or Dependabot once pushed) — `audit_commands.java` was `null`, so the post-scaffold audit step was skipped.
- Add Spring Security for the FR-001 / FR-002 email+password requirement; the `web+devtools` template did not include it.
