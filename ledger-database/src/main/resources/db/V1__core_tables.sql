-- V1: the core tables. ANSI SQL only: no trigger, procedure, view, sequence or identity column, no boolean
-- column, no DEFAULT on a time column. Every id and counter is assigned by the application. Times are UTC
-- TIMESTAMP values written by the application clock. ${long_text} is the only per-DBMS part (see SqlBuilder.longTextType).
-- No column is named with a word that SQL:2003 reserves (so no quoting is needed on any DBMS): the time of a history
-- or audit row is occurred_at (not AT) and the scope of a grant is grant_scope (not SCOPE).

CREATE TABLE project (
  id VARCHAR(64) NOT NULL,
  code VARCHAR(32) NOT NULL,
  title VARCHAR(200) NOT NULL,
  next_issue_no BIGINT DEFAULT 0 NOT NULL,
  next_spec_no BIGINT DEFAULT 0 NOT NULL,
  next_task_no BIGINT DEFAULT 0 NOT NULL,
  version BIGINT NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_project PRIMARY KEY (id),
  CONSTRAINT uq_project_code UNIQUE (code)
);

CREATE TABLE common_code (
  code_group VARCHAR(20) NOT NULL,
  code VARCHAR(32) NOT NULL,
  name_ko VARCHAR(200) NOT NULL,
  name_en VARCHAR(200) NOT NULL,
  description_ko VARCHAR(500),
  description_en VARCHAR(500),
  sort_order INTEGER NOT NULL,
  state VARCHAR(10) NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_common_code PRIMARY KEY (code_group, code),
  CONSTRAINT ck_common_code_group CHECK (code_group IN ('ISSUE_CATEGORY', 'ISSUE_SEVERITY', 'DELIVERABLE_KIND',
    'RELATION_KIND')),
  CONSTRAINT ck_common_code_state CHECK (state IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE code_revision (
  id INTEGER NOT NULL,
  revision BIGINT NOT NULL,
  CONSTRAINT pk_code_revision PRIMARY KEY (id),
  CONSTRAINT ck_code_revision_id CHECK (id = 1)
);

CREATE TABLE id_counter (
  name VARCHAR(30) NOT NULL,
  issued_no BIGINT NOT NULL,
  CONSTRAINT pk_id_counter PRIMARY KEY (name),
  CONSTRAINT ck_id_counter_name CHECK (name IN ('HISTORY', 'DELIVERABLE', 'ISSUE_CASE', 'SESSION_GRANT', 'SESSION_KEY',
    'SESSION_TOKEN', 'OWNER_AUDIT'))
);

-- The id of a task, spec, and milestone criterion is free text (any case, 1 to 120 characters). id_key is that id in
-- upper case, filled by the application, and is UNIQUE, so that two ids that differ only in case are one id on every
-- DBMS whatever its collation is. Nullable long-text columns hold NULL for empty strings (never '').
CREATE TABLE task (
  id VARCHAR(120) NOT NULL,
  id_key VARCHAR(360) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  title VARCHAR(300) NOT NULL,
  purpose ${long_text},
  success_condition ${long_text},
  state VARCHAR(20) NOT NULL,
  owner_actor_id VARCHAR(200),
  origin_ref ${long_text},
  origin_quote VARCHAR(2000),
  version BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_task PRIMARY KEY (id),
  CONSTRAINT uq_task_id_key UNIQUE (id_key),
  CONSTRAINT fk_task_project FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT ck_task_state CHECK (state IN ('UNKNOWN', 'PLANNED', 'ACTIVE', 'BLOCKED', 'COMPLETED', 'VERIFIED',
    'CANCELLED'))
);
CREATE INDEX ix_task_project ON task (project_id, created_at, id);

CREATE TABLE spec (
  id VARCHAR(120) NOT NULL,
  id_key VARCHAR(360) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  parent_id VARCHAR(120),
  title VARCHAR(300) NOT NULL,
  summary ${long_text},
  acceptance_criteria ${long_text},
  status VARCHAR(20) NOT NULL,
  priority VARCHAR(32),
  owner_actor_id VARCHAR(200),
  origin_ref ${long_text},
  origin_quote VARCHAR(2000),
  version BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_spec PRIMARY KEY (id),
  CONSTRAINT uq_spec_id_key UNIQUE (id_key),
  CONSTRAINT fk_spec_project FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT fk_spec_parent FOREIGN KEY (parent_id) REFERENCES spec (id),
  CONSTRAINT ck_spec_parent_self CHECK (parent_id IS NULL OR parent_id <> id),
  CONSTRAINT ck_spec_status CHECK (status IN ('DRAFT', 'PLANNED', 'ACTIVE', 'BLOCKED', 'COMPLETED', 'VERIFIED',
    'CANCELLED'))
);
CREATE INDEX ix_spec_project ON spec (project_id, created_at, id);
CREATE INDEX ix_spec_status ON spec (project_id, status);
CREATE INDEX ix_spec_parent ON spec (parent_id);

CREATE TABLE issue (
  id VARCHAR(120) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  title VARCHAR(300) NOT NULL,
  category VARCHAR(32) NOT NULL,
  severity VARCHAR(32) NOT NULL,
  status VARCHAR(20) NOT NULL,
  merged_into VARCHAR(120),
  symptom ${long_text},
  root_cause ${long_text},
  owner_actor_id VARCHAR(200),
  case_seq BIGINT DEFAULT 0 NOT NULL,
  version BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_issue PRIMARY KEY (id),
  CONSTRAINT fk_issue_project FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT fk_issue_merged_into FOREIGN KEY (merged_into) REFERENCES issue (id),
  CONSTRAINT ck_issue_status CHECK (status IN ('OPEN', 'RESOLVED', 'VERIFIED', 'NOT_AN_ISSUE', 'DISPUTED',
    'MERGED')),
  CONSTRAINT ck_issue_merged CHECK ((status = 'MERGED' AND merged_into IS NOT NULL)
    OR (status <> 'MERGED' AND merged_into IS NULL)),
  CONSTRAINT ck_issue_merged_self CHECK (merged_into IS NULL OR merged_into <> id)
);
CREATE INDEX ix_issue_project ON issue (project_id, created_at, id);
CREATE INDEX ix_issue_status ON issue (project_id, status);
CREATE INDEX ix_issue_merged_into ON issue (merged_into);

CREATE TABLE deliverable (
  id BIGINT NOT NULL,
  target_entity VARCHAR(10) NOT NULL,
  target_id VARCHAR(120) NOT NULL,
  kind VARCHAR(32) NOT NULL,
  deliverable_ref VARCHAR(300) NOT NULL,
  location VARCHAR(300),
  summary VARCHAR(1000) NOT NULL,
  check_ref VARCHAR(2000),
  check_result VARCHAR(10),
  checked_at TIMESTAMP,
  attachment_id VARCHAR(36),
  recorded_by VARCHAR(200) NOT NULL,
  recorded_at TIMESTAMP NOT NULL,
  origin VARCHAR(10) NOT NULL,
  CONSTRAINT pk_deliverable PRIMARY KEY (id),
  CONSTRAINT uq_deliverable_target_ref UNIQUE (target_entity, target_id, kind, deliverable_ref),
  CONSTRAINT ck_deliverable_target CHECK (target_entity IN ('ISSUE', 'SPEC', 'TASK')),
  CONSTRAINT ck_deliverable_result CHECK (check_result IS NULL OR check_result IN ('PASSED', 'FAILED', 'CANCELLED')),
  CONSTRAINT ck_deliverable_check CHECK (check_result IS NULL OR check_ref IS NOT NULL),
  CONSTRAINT ck_deliverable_origin CHECK (origin IN ('NATIVE', 'IMPORTED'))
);
CREATE INDEX ix_deliverable_target ON deliverable (target_entity, target_id, id);

CREATE TABLE issue_case (
  id BIGINT NOT NULL,
  issue_id VARCHAR(120) NOT NULL,
  observation ${long_text} NOT NULL,
  evidence VARCHAR(2000),
  source_quote VARCHAR(2000),
  observed_at TIMESTAMP,
  recorded_by VARCHAR(200) NOT NULL,
  recorded_at TIMESTAMP NOT NULL,
  CONSTRAINT pk_issue_case PRIMARY KEY (id),
  CONSTRAINT fk_issue_case_issue FOREIGN KEY (issue_id) REFERENCES issue (id)
);
CREATE INDEX ix_issue_case_issue ON issue_case (issue_id, id);

CREATE TABLE issue_task (
  issue_id VARCHAR(120) NOT NULL,
  task_id VARCHAR(120) NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_issue_task PRIMARY KEY (issue_id, task_id),
  CONSTRAINT fk_issue_task_issue FOREIGN KEY (issue_id) REFERENCES issue (id),
  CONSTRAINT fk_issue_task_task FOREIGN KEY (task_id) REFERENCES task (id)
);

CREATE TABLE issue_spec (
  issue_id VARCHAR(120) NOT NULL,
  spec_id VARCHAR(120) NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_issue_spec PRIMARY KEY (issue_id, spec_id),
  CONSTRAINT fk_issue_spec_issue FOREIGN KEY (issue_id) REFERENCES issue (id),
  CONSTRAINT fk_issue_spec_spec FOREIGN KEY (spec_id) REFERENCES spec (id)
);

CREATE TABLE spec_task (
  spec_id VARCHAR(120) NOT NULL,
  task_id VARCHAR(120) NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_spec_task PRIMARY KEY (spec_id, task_id),
  CONSTRAINT fk_spec_task_spec FOREIGN KEY (spec_id) REFERENCES spec (id),
  CONSTRAINT fk_spec_task_task FOREIGN KEY (task_id) REFERENCES task (id)
);

CREATE TABLE issue_relation (
  a VARCHAR(120) NOT NULL,
  b VARCHAR(120) NOT NULL,
  kind VARCHAR(32) NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_issue_relation PRIMARY KEY (a, b, kind),
  CONSTRAINT fk_issue_relation_a FOREIGN KEY (a) REFERENCES issue (id),
  CONSTRAINT fk_issue_relation_b FOREIGN KEY (b) REFERENCES issue (id),
  CONSTRAINT ck_issue_relation_order CHECK (a < b)
);

CREATE TABLE milestone_criterion (
  id VARCHAR(120) NOT NULL,
  id_key VARCHAR(360) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  milestone_name VARCHAR(100) NOT NULL,
  goal_label VARCHAR(200),
  code VARCHAR(200) NOT NULL,
  criterion_text ${long_text},
  state VARCHAR(20) NOT NULL,
  version BIGINT NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_milestone_criterion PRIMARY KEY (id),
  CONSTRAINT uq_criterion_id_key UNIQUE (id_key),
  CONSTRAINT fk_criterion_project FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT uq_criterion_code UNIQUE (project_id, milestone_name, code),
  CONSTRAINT ck_criterion_state CHECK (state IN ('UNKNOWN', 'PLANNED', 'ACTIVE', 'COMPLETED', 'MET', 'NOT_MET'))
);

CREATE TABLE criterion_link (
  link_key VARCHAR(400) NOT NULL,
  criterion_id VARCHAR(120) NOT NULL,
  task_id VARCHAR(120),
  issue_id VARCHAR(120),
  spec_id VARCHAR(120),
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_criterion_link PRIMARY KEY (link_key),
  CONSTRAINT fk_criterion_link_crit FOREIGN KEY (criterion_id) REFERENCES milestone_criterion (id),
  CONSTRAINT fk_criterion_link_task FOREIGN KEY (task_id) REFERENCES task (id),
  CONSTRAINT fk_criterion_link_issue FOREIGN KEY (issue_id) REFERENCES issue (id),
  CONSTRAINT fk_criterion_link_spec FOREIGN KEY (spec_id) REFERENCES spec (id),
  CONSTRAINT ck_criterion_link_target CHECK (
    (task_id IS NOT NULL AND issue_id IS NULL AND spec_id IS NULL)
    OR (task_id IS NULL AND issue_id IS NOT NULL AND spec_id IS NULL)
    OR (task_id IS NULL AND issue_id IS NULL AND spec_id IS NOT NULL))
);

CREATE TABLE session_grant (
  id BIGINT NOT NULL,
  author_id VARCHAR(200) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  grant_scope VARCHAR(16) NOT NULL,
  granted_by VARCHAR(200) NOT NULL,
  granted_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP,
  revoked_at TIMESTAMP,
  last_used_at TIMESTAMP,
  note VARCHAR(500),
  active_key VARCHAR(300) NOT NULL,
  CONSTRAINT pk_session_grant PRIMARY KEY (id),
  CONSTRAINT fk_session_grant_project FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT uq_session_grant_active UNIQUE (active_key),
  CONSTRAINT ck_session_grant_scope CHECK (grant_scope IN ('ISSUE', 'SPEC', 'TASK', 'DELIVERABLE', 'VERIFY')),
  CONSTRAINT ck_session_grant_expiry CHECK (expires_at IS NULL OR expires_at > granted_at),
  CONSTRAINT ck_session_grant_revoked CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);
CREATE INDEX ix_session_grant_author ON session_grant (author_id, project_id, grant_scope);

-- The public key an author signs its writes with (Ed25519, X.509 DER in base64). Registered by the owner only
-- (admin web, CLI), never through a tool. A key is replaced or revoked by closing its row: revoked_at is set and both
-- key columns become revoked:<id>; rows are never deleted.
CREATE TABLE session_key (
  id BIGINT NOT NULL,
  author_id VARCHAR(200) NOT NULL,
  public_key VARCHAR(128) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  registered_by VARCHAR(200) NOT NULL,
  registered_at TIMESTAMP NOT NULL,
  revoked_at TIMESTAMP,
  note VARCHAR(500),
  active_key VARCHAR(300) NOT NULL,
  fingerprint_key VARCHAR(100) NOT NULL,
  CONSTRAINT pk_session_key PRIMARY KEY (id),
  CONSTRAINT uq_session_key_active UNIQUE (active_key),
  CONSTRAINT uq_session_key_fingerprint UNIQUE (fingerprint_key),
  CONSTRAINT ck_session_key_revoked CHECK (revoked_at IS NULL OR revoked_at >= registered_at)
);
CREATE INDEX ix_session_key_author ON session_key (author_id, id);

CREATE TABLE owner_audit (
  id BIGINT NOT NULL,
  occurred_at TIMESTAMP NOT NULL,
  actor VARCHAR(300) NOT NULL,
  operation VARCHAR(20) NOT NULL,
  subject VARCHAR(300) NOT NULL,
  detail VARCHAR(1000),
  CONSTRAINT pk_owner_audit PRIMARY KEY (id),
  CONSTRAINT ck_owner_audit_operation CHECK (operation IN ('GRANT_ISSUED', 'GRANT_REVOKED', 'KEY_REGISTERED',
    'KEY_REPLACED', 'KEY_REVOKED', 'CODE_SET', 'PROJECT_ADDED', 'DRIVER_REGISTERED', 'IMPORT'))
);
CREATE INDEX ix_owner_audit_subject ON owner_audit (subject, id);
