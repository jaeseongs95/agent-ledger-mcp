CREATE TABLE project_version_item (
  project_id VARCHAR(128),
  version_id VARCHAR(36),
  item_kind VARCHAR(24),
  item_id VARCHAR(256)
);
CREATE INDEX ix_version_item_by_version ON project_version_item (project_id, version_id, item_kind, item_id);
CREATE INDEX ix_version_item_by_item ON project_version_item (project_id, item_kind, item_id);

INSERT INTO project_version_item (project_id, version_id, item_kind, item_id)
SELECT DISTINCT project_id, version_id, 'issue', issue_id FROM issue_project_version;
