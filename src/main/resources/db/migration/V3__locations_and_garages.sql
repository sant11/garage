-- V3: first domain tables (S-02) — locations and the garages under them.
-- Both are archivable portfolio records (FR-021): archived_at nullable, retained never deleted.
-- Column names/types/nullability must match the Location/Garage entities (ddl-auto=validate):
--   Instant -> TIMESTAMPTZ (a plain TIMESTAMP fails validate), BigDecimal -> NUMERIC, Long -> BIGINT.
-- Audit columns carry no DB DEFAULT — the JPA @PrePersist/@PreUpdate callbacks own them.
-- V1/V2 are immutable; this is a new forward migration.
CREATE TABLE locations (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT NOT NULL,
    archived_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);
CREATE TABLE garages (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    location_id    BIGINT NOT NULL REFERENCES locations(id),
    label          TEXT NOT NULL,
    monthly_rent   NUMERIC(10,2) NOT NULL,
    problem_reason TEXT,
    archived_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);
