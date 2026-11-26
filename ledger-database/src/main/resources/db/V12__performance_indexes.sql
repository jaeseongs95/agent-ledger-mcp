-- Additive ANSI SQL secondary indexes for Veridger v4.2.0 performance improvements.
-- Every identifier is lowercase, <= 30 characters, unquoted, and indexes plain columns only (ANSI-005, ANSI-007, ANSI-010).

CREATE INDEX ix_criterion_link_crit ON criterion_link (criterion_id);
CREATE INDEX ix_criterion_link_issue ON criterion_link (issue_id, criterion_id);
CREATE INDEX ix_criterion_link_spec ON criterion_link (spec_id, criterion_id);
CREATE INDEX ix_criterion_link_task ON criterion_link (task_id, criterion_id);

CREATE INDEX ix_issue_task_task ON issue_task (task_id, issue_id);
CREATE INDEX ix_issue_spec_spec ON issue_spec (spec_id, issue_id);
CREATE INDEX ix_spec_task_task ON spec_task (task_id, spec_id);
CREATE INDEX ix_issue_relation_b ON issue_relation (b, a, kind);

CREATE INDEX ix_history_entity_occ ON history (entity, entity_id, occurred_at, id);
CREATE INDEX ix_task_state ON task (project_id, state);
CREATE INDEX ix_criterion_state ON milestone_criterion (project_id, state);
CREATE INDEX ix_criterion_page ON milestone_criterion (project_id, id);
CREATE INDEX ix_session_grant_live ON session_grant (revoked_at, author_id, project_id);
CREATE INDEX ix_deliverable_attach ON deliverable (attachment_id);
