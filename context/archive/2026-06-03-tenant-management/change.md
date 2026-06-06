---
change_id: tenant-management
title: Manage tenants with profile & contract history
status: archived
created: 2026-06-03
updated: 2026-06-06
archived_at: 2026-06-06T20:20:56Z
---

## Notes

Roadmap slice S-03 (see `context/foundation/roadmap.md`).

Outcome: Owner can add a tenant (name + contact info) and view a tenant profile listing their current and past contracts.

- PRD refs: FR-007, FR-008, FR-021
- Prerequisites: S-01 (owner-auth-signup-login, done), F-02 (jpa-persistence-foundation, done)
- Parallel with: S-02 (portfolio-locations-garages, done/archived)
- Note: the profile's contract list is empty until S-04 (rental-contracts), but the profile page is also the drill-through target (FR-018) and the surface for the late-payer flag (S-07), so it is built now. Archiving a tenant exercises FR-021 (archive-only, retain underlying records).
