CREATE TABLE project_version (
  project_id VARCHAR(128),
  id VARCHAR(36),
  name VARCHAR(120),
  description VARCHAR(1000)
);
CREATE INDEX ix_project_version_name ON project_version (project_id, name);
CREATE INDEX ix_project_version_id ON project_version (project_id, id);

CREATE TABLE issue_project_version (
  project_id VARCHAR(128),
  issue_id VARCHAR(128),
  version_id VARCHAR(36)
);
CREATE INDEX ix_issue_project_version_issue ON issue_project_version (project_id, issue_id);
CREATE INDEX ix_issue_version_project_ver ON issue_project_version (project_id, version_id, issue_id);
