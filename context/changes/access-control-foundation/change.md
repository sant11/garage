---
change_id: access-control-foundation
roadmap_id: F-01
title: Wire Spring Security + gate all routes to login
status: implementing
created: 2026-05-26
updated: 2026-05-27
prd_refs: [Access Control, NFR-privacy, FR-001, FR-002]
prerequisites: []
parallel_with: [jpa-persistence-foundation]
---

# Change: access-control-foundation (F-01)

Foundation slice. Put Spring Security on the classpath and gate every route to the
authenticated owner: unauthenticated visitors are redirected to login; `/actuator/health`,
the login page, and static assets stay public. Password hashing (BCrypt) and a
session/form-login mechanism are in place, backed by a config-driven **in-memory** owner
placeholder (the DB-backed user store lands in S-01, which depends on this change).

This change is infrastructure + gating only. It does NOT build the signup flow, the
DB-backed `UserDetailsService`, logout UX, or a styled login page — those belong to S-01
(`owner-auth-signup-login`).

See `plan-brief.md` (start here) and `plan.md` (full contract).
