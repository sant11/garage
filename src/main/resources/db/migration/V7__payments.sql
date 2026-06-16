-- V7: rent payments (S-05) — one recorded payment tied to a contract (FR-012/FR-014), plus the
-- per-contract grace_days deferred from S-04 (V6 header).
-- Archivable per FR-021: archived_at nullable, retained never deleted; archiving a contract
-- cascade-archives its payments (service layer), never deletes them.
-- Column names/types/nullability must match the Payment entity (ddl-auto=validate):
--   Instant -> TIMESTAMPTZ (a plain TIMESTAMP fails validate), BigDecimal -> NUMERIC,
--   Long -> BIGINT, LocalDate -> DATE, String -> TEXT.
-- amount precision matches monthly_rent (NUMERIC(10,2)). Period membership is by date alone — no
-- stored period column; the overdue rule sums amount over the dates in the period it resolves.
-- Audit columns carry no DB DEFAULT — the JPA @PrePersist/@PreUpdate callbacks own them.
-- grace_days carries DEFAULT 5 so existing contract rows backfill automatically; the entity also
-- initialises it to 5, so the default holds at both levels.
-- V1–V6 are immutable; this is a new forward migration.
CREATE TABLE payments (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contract_id BIGINT NOT NULL REFERENCES contracts(id),
    amount      NUMERIC(10,2) NOT NULL,
    date        DATE NOT NULL,
    note        TEXT,
    archived_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

-- The overdue scan filters and sums payments by contract; index the FK as the contracts FKs are.
CREATE INDEX idx_payments_contract_id ON payments (contract_id);

-- grace_days deferred from S-04 (V6); per-contract historical fact, default 5 backfills existing rows.
ALTER TABLE contracts ADD COLUMN grace_days INTEGER NOT NULL DEFAULT 5;
