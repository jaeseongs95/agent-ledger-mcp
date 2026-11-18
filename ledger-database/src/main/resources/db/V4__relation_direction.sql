-- V4: the direction of a relation kind. GIVEN keeps the ends in the order the caller gave them;
-- CANONICAL keeps canonical order so (a, b) and (b, a) stay one relation.

ALTER TABLE common_code ADD COLUMN direction VARCHAR(10) DEFAULT 'CANONICAL' NOT NULL;
ALTER TABLE common_code ADD CONSTRAINT ck_common_code_direction CHECK (direction IN ('GIVEN', 'CANONICAL'));
ALTER TABLE common_code ADD CONSTRAINT ck_common_code_direction_group CHECK (direction = 'CANONICAL' OR code_group = 'RELATION_KIND');

UPDATE common_code SET direction = 'GIVEN' WHERE code_group = 'RELATION_KIND' AND code IN ('caused_by', 'blocks');

ALTER TABLE issue_relation DROP CONSTRAINT ck_issue_relation_order;
ALTER TABLE issue_relation ADD CONSTRAINT ck_issue_relation_distinct CHECK (a <> b);
