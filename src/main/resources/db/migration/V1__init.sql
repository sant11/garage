-- V1: smoke-test migration. Proves Flyway connected, migrated, recorded history.
-- No domain entities yet (Phase B scope: deploy wiring only).
CREATE TABLE deploy_smoke_test (
    id          BIGSERIAL PRIMARY KEY,
    deployed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    note        TEXT NOT NULL
);

INSERT INTO deploy_smoke_test (note) VALUES ('Phase B initial deploy');
