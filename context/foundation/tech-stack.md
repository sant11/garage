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
conversation: Spring Security ceremony for the CRUD auth requirement, and the
Thymeleaf vs separate-SPA frontend choice — both deferred to bootstrapper /
implementation time, neither blocking stack selection.
