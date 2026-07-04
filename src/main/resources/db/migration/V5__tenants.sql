-- V5: tenant records (S-03) — the renting parties an owner manages.
-- Archivable per FR-021: archived_at nullable, retained never deleted.
-- Column names/types/nullability must match the Tenant entity (ddl-auto=validate):
--   Instant -> TIMESTAMPTZ (a plain TIMESTAMP fails validate), Long -> BIGINT.
-- name is required (NotBlank); contact_info is optional free-text (FR-007).
-- Audit columns carry no DB DEFAULT — the JPA @PrePersist/@PreUpdate callbacks own them.
-- V1–V4 are immutable; this is a new forward migration.
CREATE TABLE tenants (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         TEXT NOT NULL,
    contact_info TEXT,
    archived_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);
