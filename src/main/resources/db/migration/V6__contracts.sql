-- V6: rental contracts (S-04) — the agreement linking one tenant to one garage.
-- Archivable per FR-021: archived_at nullable, retained never deleted; ending a contract sets
-- ended_on (a normal queryable row), distinct from the parent-archive cascade that stamps archived_at.
-- Column names/types/nullability must match the Contract entity (ddl-auto=validate):
--   Instant -> TIMESTAMPTZ (a plain TIMESTAMP fails validate), BigDecimal -> NUMERIC,
--   Long -> BIGINT, LocalDate -> DATE, int -> INTEGER.
-- monthly_rent precision matches garages (NUMERIC(10,2)). payment_day_of_month bounded 1–28 in the
-- entity (@Min/@Max); grace_days is deliberately deferred to S-05.
-- Audit columns carry no DB DEFAULT — the JPA @PrePersist/@PreUpdate callbacks own them.
-- V1–V5 are immutable; this is a new forward migration.
CREATE TABLE contracts (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id            BIGINT NOT NULL REFERENCES tenants(id),
    garage_id            BIGINT NOT NULL REFERENCES garages(id),
    start_date           DATE NOT NULL,
    planned_end_date     DATE NOT NULL,
    monthly_rent         NUMERIC(10,2) NOT NULL,
    payment_day_of_month INTEGER NOT NULL,
    ended_on             DATE,
    archived_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL
);
