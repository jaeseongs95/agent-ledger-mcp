-- V3: counters, the code revision and the universal domain-neutral common codes of a fresh ledger.
-- ISSUE_CATEGORY starts empty (categories belong to the project/team and are seeded or added with code-set).

INSERT INTO id_counter (name, issued_no) VALUES ('HISTORY', 0);
INSERT INTO id_counter (name, issued_no) VALUES ('DELIVERABLE', 0);
INSERT INTO id_counter (name, issued_no) VALUES ('ISSUE_CASE', 0);
INSERT INTO id_counter (name, issued_no) VALUES ('SESSION_GRANT', 0);
INSERT INTO id_counter (name, issued_no) VALUES ('SESSION_KEY', 0);
INSERT INTO id_counter (name, issued_no) VALUES ('SESSION_TOKEN', 0);
INSERT INTO id_counter (name, issued_no) VALUES ('OWNER_AUDIT', 0);

INSERT INTO code_revision (id, revision) VALUES (1, 0);

INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('ISSUE_SEVERITY', 'P1', '치명', 'Critical', '작업 진행 차단 또는 거짓 완료 판정', 'Blocks work progress or causes a false completion check', 1, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('ISSUE_SEVERITY', 'P2', '중대', 'Major', '우회 가능한 잘못된 결과 또는 작업 손실', 'Wrong outcome or lost work with a workaround', 2, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('ISSUE_SEVERITY', 'P3', '경미', 'Minor', '품질 저하, 비용 증가 또는 표현 문제', 'Minor quality, cost or phrasing issue', 3, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('ISSUE_SEVERITY', 'UNRATED', '미분류', 'Unrated', '외부 가져온 행 전용, 다음 변경에 P1/P2/P3 지정 필요', 'Imported rows only; assign P1, P2 or P3 on the next write', 9, 'INACTIVE', 'user:owner');

INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('RELATION_KIND', 'same_site', '동일 위치', 'Same location', '결과물이나 작업 대상의 같은 지점', 'Same location in the artifact or deliverable', 1, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('RELATION_KIND', 'caused_by', '원인 관계', 'Caused by', 'a의 원인은 b', 'a is caused by b', 2, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('RELATION_KIND', 'blocks', '차단', 'Blocks', 'a가 b를 차단', 'a blocks b', 3, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('RELATION_KIND', 'same_family', '동일 계열', 'Same family', '근본 원인이 다른 유사 이슈', 'Similar issues with different root causes', 4, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('RELATION_KIND', 'same_work_unit', '동일 작업 단위', 'Same work unit', '하나의 산출물 변경에서 함께 해결', 'Resolved together in one deliverable unit', 5, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('RELATION_KIND', 'regression_of', '재발', 'Recurrence of', 'b는 검증까지 끝난 a가 다시 나타난 것(나중 번호가 재발)', 'b is a recurrence of a after a was verified (the later id is the recurrence)', 6, 'ACTIVE', 'user:owner');

INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('DELIVERABLE_KIND', 'code_change', '코드 변경', 'Code change', '소스 코드 수정 커밋 또는 패치', 'Source modification commit or patch', 1, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('DELIVERABLE_KIND', 'document', '문서', 'Document', '원고, 기획서, 보고서, 매뉴얼 등 문서 판본', 'Manuscript, specification, report or manual revision', 2, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('DELIVERABLE_KIND', 'media', '미디어', 'Media', '영상, 음향, 이미지, 렌더링 등 시청각 산출물', 'Video, audio, image or rendered media output', 3, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('DELIVERABLE_KIND', 'design', '설계·시안', 'Design', '도면, 콘티, 시안, 구조 설계 산출물', 'Blueprint, storyboard, mockup or design output', 4, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('DELIVERABLE_KIND', 'dataset', '데이터셋', 'Dataset', '정제 데이터, 실험 표본, 측정 기록', 'Curated dataset, experiment sample or measurement log', 5, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('DELIVERABLE_KIND', 'specification', '명세 판본', 'Specification', '요구사항, 규격, 기준서 개정', 'Requirement, standard or acceptance criteria revision', 6, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('DELIVERABLE_KIND', 'artifact', '패키지·완제품', 'Artifact', '최종 패키징, 배포본 또는 검증 가능한 완제품', 'Packaged release or verifiable finished artifact', 7, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('DELIVERABLE_KIND', 'other', '기타', 'Other', '위 분류에 속하지 않는 일반 산출물', 'General deliverable not covered above', 9, 'ACTIVE', 'user:owner');
