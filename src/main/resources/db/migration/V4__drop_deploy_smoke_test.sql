-- V4: drop the obsolete deploy_smoke_test table now that V3 ships the first real domain tables.
-- V1__init.sql stays immutable; the drop is a new forward migration. Production retains the table
-- until this runs on the next deploy, which is harmless (ddl-auto=validate ignores extra tables and
-- nothing reads it once DeploySmokeRecord is removed).
DROP TABLE IF EXISTS deploy_smoke_test;
