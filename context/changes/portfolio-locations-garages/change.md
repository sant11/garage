---
change_id: portfolio-locations-garages
title: Manage locations & garages with per-garage status
status: impl_reviewed
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

## Epilogue

### @ManyToOne LAZY flip needs fetch-joins on view/grouping paths

- **Context**: `Garage.java:32` — `@ManyToOne(optional = false)` to `Location`, with `open-in-view=false`.
- **Problem**: The mapping works today only because `@ManyToOne` defaults to EAGER, so `garage.getLocation()` is initialized at fetch time and stays usable on the detached entity (the portfolio view's batch grouping traverses `getLocation().getId()`). If a later slice flips this association to `LAZY`, every off-session traversal (view render, service-side grouping) throws `LazyInitializationException`, since the OSIV session is closed.
- **Rule**: Always declare `@ManyToOne(fetch = FetchType.LAZY)` explicitly on JPA associations. Before merging LAZY associations, ensure every repository query that fetches the owning entity includes an explicit fetch-join (JPQL `JOIN FETCH`, `@EntityGraph`, or DTO projection) for any association field that will be traversed outside the transactional session (view rendering, service-layer grouping/mapping, etc.). This is required because `spring.jpa.open-in-view=false` — detached entities cannot lazily load associations after the session closes.
- **Applies to**: All JPA entities in `com.example.garageops.*` packages under `open-in-view=false`.
