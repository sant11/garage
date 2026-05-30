-- V2: owner identity table backing DB-backed authentication (S-01 Phase 3).
-- Single-owner account, provisioned idempotently on startup from OWNER_* env vars when empty.
-- Column names/types/nullability must match the OwnerAccount entity (ddl-auto=validate).
-- Not archivable (FR-021 is for portfolio records, not the login identity) — no
-- archived_at/created_at/updated_at columns. V1 is immutable; this is a new versioned migration.
CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    email         TEXT NOT NULL UNIQUE
);
