-- V10: include location in deliverable uniqueness via ANSI non-null location_key so deliverables with the same
-- deliverable_ref at distinct locations (such as renamed or multi-repo commits) are distinct while NULL locations
-- remain deduplicated consistently across DBMSes.

ALTER TABLE deliverable ADD COLUMN location_key VARCHAR(300) DEFAULT '' NOT NULL;
UPDATE deliverable SET location_key = location WHERE location IS NOT NULL;
ALTER TABLE deliverable DROP CONSTRAINT uq_deliverable_target_ref;
ALTER TABLE deliverable ADD CONSTRAINT uq_deliverable_target_ref UNIQUE (target_entity, target_id, kind, deliverable_ref, location_key);
