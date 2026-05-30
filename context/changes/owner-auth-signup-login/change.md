---
change_id: owner-auth-signup-login
title: Owner auth signup login
status: implemented
created: 2026-05-28
updated: 2026-05-30
archived_at: null
---

## Notes

<!-- Free-form notes for this change: links, ad-hoc context, decisions that don't belong in research/frame/plan. -->

- **UI framework: Vaadin Flow 25.** This is the first user-visible slice, so it
  is also where Vaadin Flow first enters the codebase. Build the signup / login
  / logout screens as server-side Vaadin views (`@Route`), not Thymeleaf
  templates or an SPA. Wire `vaadin-spring-boot-starter` (Vaadin 25 BOM) into
  `pom.xml` here. Login integrates with the F-01 Spring Security session auth —
  prefer Vaadin's auth-friendly patterns (e.g. a `LoginView` / `LoginForm` with
  the Spring Security filter chain) over a hand-rolled MVC login. See
  `context/foundation/tech-stack.md` → "Frontend / UI: Vaadin Flow".
