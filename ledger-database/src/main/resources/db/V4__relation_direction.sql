-- V4: the direction of a relation kind (defect PG_LEDGER_MCP-D-0016). A relation between two defects was always stored with
-- its two ends in canonical order (a sorts before b), so a directed kind (caused_by: a is caused by b, blocks: a blocks b)
-- lost which defect is the subject. The direction is now a property of the code of the relation kind: GIVEN keeps the ends
-- in the order the caller gave them, CANONICAL keeps the canonical order, so that (a, b) and (b, a) stay one relation. A kind
-- that is added later declares its direction too. The column is there for every code group (the value of the groups without
-- relations is CANONICAL, which the second check enforces), and a code that is not set stays CANONICAL.
-- The canonical order check of defect_relation goes, because a directed relation may have a greater than b, and the two ends of
-- a relation are still two different defects.

ALTER TABLE common_code ADD COLUMN direction VARCHAR(10) DEFAULT 'CANONICAL' NOT NULL;
ALTER TABLE common_code ADD CONSTRAINT ck_common_code_direction CHECK (direction IN ('GIVEN', 'CANONICAL'));
ALTER TABLE common_code ADD CONSTRAINT ck_common_code_direction_group CHECK (direction = 'CANONICAL' OR code_group = 'RELATION_KIND');

UPDATE common_code SET direction = 'GIVEN' WHERE code_group = 'RELATION_KIND' AND code IN ('caused_by', 'blocks');

ALTER TABLE defect_relation DROP CONSTRAINT ck_defect_relation_order;
ALTER TABLE defect_relation ADD CONSTRAINT ck_defect_relation_distinct CHECK (a <> b);
