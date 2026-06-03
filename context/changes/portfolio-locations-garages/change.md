---
change_id: portfolio-locations-garages
title: Manage locations & garages with per-garage status
status: implemented
created: 2026-05-31
updated: 2026-06-03
archived_at: null
---

## Notes

Roadmap slice S-02 (see `context/foundation/roadmap.md`).

Outcome: Owner can add, rename, and archive locations; add garages (label + default monthly rent) to a location; and view all garages grouped by location with each garage's status (free / problem; "rented" activates once contracts exist in S-04).

- PRD refs: FR-003, FR-004, FR-005, FR-006, FR-021
- Prerequisites: S-01 (owner-auth-signup-login, done), F-02 (jpa-persistence-foundation, done)
- Parallel with: S-03 (tenant-management)
- Note: garage "rented" status is derived from active contracts (S-04) — this slice ships free/problem only. Archiving a location/garage must retain underlying records (FR-021).
