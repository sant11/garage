---
change_id: rental-contracts
title: Create/end rental contracts & garage rental history
status: archived
created: 2026-06-06
updated: 2026-06-11
archived_at: 2026-06-11T20:24:43Z
---

## Notes

Roadmap slice S-04 (see `context/foundation/roadmap.md`).

Outcome: Owner can create a contract linking one tenant to one garage (start date, required end date, monthly rent, payment day-of-month), end a contract early on the actual move-out date, and view a garage's full rental history.

- PRD refs: FR-009, FR-010, FR-011, FR-021
- Prerequisites: S-02 (portfolio-locations-garages, done), S-03 (tenant-management, done)
- Parallel with: —
- Note: completes the garage "rented" status (FR-005, deferred from S-02) and populates the tenant profile's contract section (the seam left in S-03). The contract's required end-date and payment-day fields are exactly the inputs the overdue rule (S-05) and the dashboard (S-06) consume, so this slice's shape gates both. Ending a contract retains its records; archiving a parent retains contracts (FR-021).

### Key decisions (from /10x-plan questioning)

1. **Ended vs archived are distinct.** Ending sets an `ended_on` date (≤ planned end); the contract stays a normal, queryable record. `archived_at` is reserved for the FR-021 parent-archive cascade.
2. **No overlapping active contracts per garage** — the service rejects a create whose date window overlaps an existing non-ended contract on that garage.
3. **Payment day-of-month constrained 1–28**; `grace_days` deferred to S-05 (the overdue slice that consumes it).
4. **No term-editing** — contracts are create + end-early only (matches the FR set).
5. **Create from the garage** (garage fixed, pick tenant, rent pre-filled); history lives on a new `garages/:id` detail view.
6. **"Rented" status derived at query time** (batch query), no stored field.
7. **Tests:** mocked-service unit tests + extend `SecurityGatingTests`; real-DB retention/integration deferred to test-plan §3 Phase 2.
