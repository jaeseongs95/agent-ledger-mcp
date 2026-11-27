-- Keep the project code as the stable entity ID prefix and initialize existing display names from it.
ALTER TABLE project ADD COLUMN display_name VARCHAR(200);
UPDATE project SET display_name = code;

ALTER TABLE owner_audit DROP CONSTRAINT ck_owner_audit_operation;
ALTER TABLE owner_audit ADD CONSTRAINT ck_owner_audit_operation CHECK (operation IN ('GRANT_ISSUED', 'GRANT_REVOKED',
  'KEY_REGISTERED', 'KEY_REPLACED', 'KEY_REVOKED', 'TOKEN_ISSUED', 'TOKEN_REVOKED', 'CODE_SET', 'PROJECT_ADDED',
  'PROJECT_RENAMED', 'DRIVER_REGISTERED', 'IMPORT', 'PASSKEY_CODE_ISSUED', 'PASSKEY_REGISTERED', 'PASSKEY_REVOKED',
  'PASSKEY_RESET', 'ATTACHMENT_REKEY', 'ORPHANS_PURGED'));

CREATE INDEX ix_issue_project_updated ON issue (project_id, updated_at, id);
CREATE INDEX ix_spec_project_updated ON spec (project_id, updated_at, id);
CREATE INDEX ix_task_project_updated ON task (project_id, updated_at, id);
CREATE INDEX ix_history_occurred ON history (occurred_at, id);
