# Review follow-ups — payments-overdue

Queued from `/10x-impl-review` triage of `reviews/impl-review.md` (2026-06-22).

## F2 — Wire per-contract grace_days through construction/edit path

- **From**: Impl review F2 (Plan Adherence, WARNING). Decision: accepted default-only as a documented deferral.
- **What**: `graceDays` is currently an entity/DB default (5) only — not a constructor parameter, not in `ContractService.create`, not in the new-contract dialog. Every contract is locked to grace 5.
- **Why deferred**: Phase 4 shipped no grace field; boundary tests assume grace 5; wiring it now widens scope beyond the committed phases and would touch the constructor, `ContractService.create` signature, the new-contract dialog, and `ContractServiceTests`.
- **To deliver FR-013 (per-contract grace)**: add `graceDays` as a constructor parameter on `Contract`, thread it through `ContractService.create` (default-prefilled to 5), surface it in the new-contract dialog with validation, and extend `ContractServiceTests`.
- **Locations**: `contracts/Contract.java:84-92`, `contracts/ContractService.java:59`.
