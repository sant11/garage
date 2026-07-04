---
change_id: jpa-persistence-foundation
title: JPA persistence + archive-only foundation
status: archived
created: 2026-05-27
updated: 2026-05-28
triaged: 2026-05-27
archived_at: 2026-05-28T20:52:29Z
---

## Notes

Seeded from `context/foundation/roadmap.md` — roadmap item **F-02: JPA persistence & archive-only foundation** (Change ID `jpa-persistence-foundation`).

- **Outcome:** Persistence layer on JPA (replacing the scaffolded `spring-boot-starter-jdbc`); base entity + repository conventions and the archive-only soft-delete pattern established; Flyway continues to own schema migrations.
- **PRD refs:** FR-021 (archive-only deletion semantics), NFR (no silent data loss guardrail).
- **Unlocks:** S-02, S-03, S-04 (all entity-backed slices) — establishes the FR-021 archive-only convention once so each slice inherits it.
- **Prerequisites:** — (parallel with F-01).
- **Scope guardrail (from roadmap Risk):** swap the JDBC scaffold (and its `V1__init.sql` smoke table) to JPA *once*, as a thin foundation. Entities themselves land in their slices, **not** here. Avoid the "build the whole schema" anti-pattern.
