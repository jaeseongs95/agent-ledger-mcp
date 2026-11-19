-- V9: enforce same-project spec parentage via ANSI composite UNIQUE + FK, and add a single-row ANSI gate table
-- to serialize concurrent upload_staging begin quota checks and idempotent operation-key lookup.

ALTER TABLE spec ADD CONSTRAINT uq_spec_id_project UNIQUE (id, project_id);
ALTER TABLE spec DROP CONSTRAINT fk_spec_parent;
ALTER TABLE spec ADD CONSTRAINT fk_spec_parent FOREIGN KEY (parent_id, project_id) REFERENCES spec (id, project_id);

CREATE TABLE upload_quota_gate (
  id INTEGER NOT NULL,
  revision BIGINT NOT NULL,
  CONSTRAINT pk_upload_quota_gate PRIMARY KEY (id),
  CONSTRAINT ck_upload_quota_gate_id CHECK (id = 1)
);
INSERT INTO upload_quota_gate (id, revision) VALUES (1, 0);
