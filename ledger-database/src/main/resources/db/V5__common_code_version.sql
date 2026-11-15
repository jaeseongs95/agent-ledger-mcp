-- V5: the version of a common code (review finding TX-04). Setting a code read the row and then wrote it without a condition, so two
-- owner actions on the same code that read the same row let the later write silently overwrite the earlier one. The row now
-- carries a version, which the update checks and raises (UPDATE ... WHERE version = <the version that was read>): an update that
-- affects no row means another action changed the code first, and the service answers with a version conflict instead of writing.
-- The codes that exist (the seed and the ones of an upgraded installation) start at 0.

ALTER TABLE common_code ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
