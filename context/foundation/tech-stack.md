---
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
---

## Why this stack

Solo developer shipping a single-owner garage portfolio dashboard in 3 weeks
after-hours. Standard path — Spring Boot is the recommended default for
`(web-app, java)` and clears all four agent-friendly gates (typed,
convention-based, popular in training data, well documented) with
`bootstrapper_confidence: verified`. Auth is in scope (email + password per
FR-001/002); payments, realtime, AI, and background jobs are explicit
non-goals in the PRD, so the lean web+devtools template is sufficient. Fly.io
picked from the card's `deployment_defaults`; GitHub Actions with
auto-deploy-on-merge fits a solo MVP where the only user is the owner and
there is no public-facing exposure. Known friction acknowledged in
conversation: Spring Security ceremony for the CRUD auth requirement.

## Frontend / UI: Vaadin Flow

The previously-deferred "Thymeleaf vs separate-SPA" frontend question is now
resolved: the UI layer is **Vaadin Flow 25** (latest stable line, aligned with
Spring Boot 4 / Java 21 — Vaadin 24 targets Spring Boot 3). Vaadin Flow is the
main framework alongside Spring: views are written in Java as server-side
components, so there is no separate SPA build, no JavaScript/TypeScript view
layer, and no REST/JSON contract between a front end and back end for the
owner-facing screens. This fits the solo-developer / single-owner shape — one
language (Java) end to end, type-safe UI, and tight integration with Spring
Security session auth (FR-001/002) and the Spring-managed services behind each
slice.

Implications for the build:
- Add the `vaadin-spring-boot-starter` (Vaadin 25 BOM) to `pom.xml` alongside
  the existing Spring Boot 4 starters; no `spring-boot-starter-thymeleaf`.
- Routing is Vaadin's `@Route` / `RouterLink`, not Spring MVC view controllers.
  Spring Security gates routes; Vaadin views render the gated surfaces.
- The Vaadin frontend build (npm/Vite, run by the Vaadin Maven plugin in
  production mode) becomes part of `mvnw package` / the Docker image — account
  for it in CI build time and the Fly.io / Railway image.
