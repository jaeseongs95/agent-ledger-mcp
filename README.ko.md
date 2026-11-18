# pg-ledger-mcp

[English](README.md)

AI 에이전트가 수행한 작업을 기록하는 장부이며, 하나의 MCP 엔드포인트로 제공됩니다. 결함, 작업, 수정과 그 검증을 누가 왜 수행했는지와 함께 기록합니다.
에이전트는 MCP 도구로 읽고 쓰며, 자신의 데이터베이스 로그인을 받지 않습니다.

이것은 **개인판**입니다. 한 사람이 본인의 컴퓨터나 본인의 서버에 설치해 실행하며, 설치 대상 전체를 신뢰합니다. 두 서비스(MCP 서버와 관리 웹)는
루프백 주소 `127.0.0.1`에서만 수신합니다. 다른 컴퓨터에서 접근하게 하는 일, 곧 역방향 프록시와 TLS 인증서는 운영자가 고른 도구로 하는 운영자의 몫이며,
설치기는 프록시 설정을 쓰지 않고 인증서를 발급하지 않습니다.

2.0.0 버전은 Java 25와 Spring Boot로 다시 작성한 것이며 자체 도구 계약을 가집니다(모든 쓰기 도구는 필수 `operationKey`를 받고, 이전 버전의 인자는 새 인자와 함께 받아들이지 않습니다).
클라이언트에 바뀐 내용은 [릴리스 노트](docs/release-notes/2.0.0.md)에 적습니다. 릴리스 노트의 형식은 [docs/RELEASE_NOTES_FORMAT.md](docs/RELEASE_NOTES_FORMAT.md)에 있습니다.

## 목차

- 개념
- MCP 도구
- 첨부
- 모듈
- 설정
- 설치
- ledger-cli
- 관리 웹
- 클라이언트 훅
- 1.x에서 업그레이드
- 빌드와 시험
- 구성

## 개념

- **작성자 신원은 주장이 아니라 증명으로 확인합니다.** 모든 쓰기와 모든 `ledger_whoami` 호출에는 예약 키 `_hostAttestation`이 붙으며, 이 값은 클라이언트의 호스트 훅이 채웁니다.
  모델은 이 키를 넣지 않아야 합니다. 작성자는 `<vendor>:<session-id>` 형식이며 예를 들어 `claude:1f2e3d4c` 또는 `codex:9a8b7c6d`입니다. 장부의 소유자는 `user:owner`입니다.
  작성자는 증명으로만 정해지며, 도구에는 `author_id` 인자가 없습니다.
- **쓰기에는 유효한 부여가 필요합니다.** 소유자는 `ledger-cli grant`(또는 관리 웹의 부여 화면)로 작성자에게 프로젝트의 범위를 부여합니다.
  범위는 `defect`, `task`, `fix`, `verify`입니다. 해당 부여가 없는 쓰기는 거절됩니다. 두 프로젝트의 행에 걸치는 쓰기(작업이나 조건을 다른 프로젝트로 옮기기, 연결,
  다른 프로젝트의 결함으로의 병합)는 두 프로젝트 모두에서 해당하는 부여를 요구합니다.
- **검증은 분리되어 있습니다.** `VERIFIED`, `MET`, `NOT_MET`은 검토자 도구 `ledger_review`와 `ledger_review_correct`로만 설정할 수 있으며, 이 도구들은 `verify` 범위를 요구합니다.
  결함은 증거 링크와 수정이 하나 이상 있어야 하고, 그 결함을 등록하지 않았고 사례를 기록하지 않았고 수정도 기록하지 않은 세션만 검증할 수 있습니다.
  (그 결함에 병합된 결함의 사례와 등록 세션도 그 결함의 것으로 칩니다.) 작업은 그것을 등록하지 않았고, 소유하지 않으며, 완료를 보고한 적이 없는 세션만 검증할 수 있고,
  조건은 그것을 등록하지 않은 세션만 `MET`이나 `NOT_MET`으로 정할 수 있습니다(그렇지 않으면 `SAME_SESSION`). 검증 결과와 재작업 요청은 결함을 등록한
  세션(`created_by`)이 아니라 수정을 기록한 세션(`fixed_by`)에게 보냅니다. CI 결과는 선택 메타데이터입니다. `VERIFIED` 결함에는 `ledger_record_fix`도 `ledger_save`의
  수정도 허용되지 않으며(`VERIFIED_DEFECT`), 검토자가 먼저 `ledger_review`로 다시 열어야 합니다.
- **이력은 추가만 가능합니다.** 쓰기마다 이력 행이 추가되며, 시각, 작성자, 채널, 연산, 상태 변화, 사유를 담습니다. 애플리케이션에는 이력 행을 수정하거나 삭제하는 경로가 없고,
  데이터베이스 모듈의 부여 템플릿(`migrate`가 앱 계정에 적용합니다)은 그 계정에 이력의 `UPDATE`와 `DELETE`를 주지 않습니다. 잘못된 기록은 `CORRECT` 행으로 표시합니다.
  기존 행을 바꾸는 연산은 바꾸기 전의 전체 행도 함께 저장합니다(`before_data`, `ledger_history`에서 `include_before`를 주면 반환됩니다). 한 가지 예외가 있습니다. 결함에서 `updated_at`과 판만 바꾸는 `ledger_record_fix`(소유자와 상태는 그대로)는
  결함의 이력 행을 쓰지 않으므로 결함의 `before_data`도 저장하지 않습니다. 그 수정의 이력은 수정 행이며, 기록 관리 열만 바뀌는데 이력 행을 쓰면 수정마다 빈 편집 행이 결함의 이력에 쌓이기 때문입니다.
- **재시도는 안전합니다.** 모든 쓰기 도구는 필수 인자 `operationKey`(1자 이상 128자 이하, 예를 들어 UUID)를 받습니다. 같은 키로 같은 요청을 반복하면 저장된 결과를 돌려주며 두 번 기록하지 않습니다.
  키는 작성자에게 속합니다(`artifact-begin`의 키와 같습니다). 다른 작성자는 같은 키로 자기 쓰기를 할 수 있습니다. 작성자의 키가 이미 다른 작업, 내용 또는 채널에 묶여 있으면 `OPERATION_CONFLICT`로 거절됩니다. 서버는 키를 대신 만들지 않으며, 키가 없거나 비어 있으면 `VALIDATION_FAILED`(경로 `operationKey`)로 거절됩니다.
  `ledger_batch`는 배치 전체에 키 하나를 받으며 배치는 하나의 요청입니다. 같은 키에 같은 연산이면 저장된 결과를 돌려주고, 같은 키에 다른 연산(추가, 삭제, 변경, 순서 바꿈)이거나 다른 도구이면 `OPERATION_CONFLICT`입니다. `artifact-chunk`와 `artifact-finish`는 `operationKey`를 받지 않습니다. 업로드는 `artifact-begin`의 `operationKey`로 시작하고,
  청크는 `uploadId`, `sequence`, `chunkSha256`으로, finish는 `uploadId`로 멱등입니다.
- **낙관적 잠금.** 수정과 전이는 `expected_version`을 가지며, 오래된 판은 `VERSION_CONFLICT`입니다.
- **프로젝트와 ID.** 결함은 프로젝트에 속하며 `<CODE>-D-0001`, `<CODE>-D-0002`와 같은 ID를 받습니다. ID 없이 만든 작업은 `<CODE>-T-0001`과 같은 ID를 받습니다.
  ID는 서버가 부여합니다. 작업이나 조건에 호출자가 정하는 ID는 공백이 없는 1자 이상 120자 이하이며 대소문자와 문자 종류(`:`, `/`, `#`, `.`, `_`, `-` 등)를 가리지 않습니다.
  이미 있는 ID와 대소문자만 다르면 `DUPLICATE_KEY`로 거부하며, ID는 언제나 정확히 비교합니다. 호출자가 정하는 결함 ID와 `<CODE>-T-0001` 형식의 작업 ID는 번호가 프로젝트의
  다음 번호를 넘지 않아야 합니다. 그보다 작은 빈 번호는 받아들이고, 넘는 번호는 다음 ID를 알려 주는 `ID_NUMBER_AHEAD`로 거절하므로 ID 하나가 서버의 번호 부여를 멈추게 할 수 없습니다(이 요청이 처리되는 동안 다른 요청이 그 번호를 가져가면 `ID_COUNTER_CHANGED`가 요청을 다시 보내라고 알립니다). 이런 ID는 서버가 적는 형식(1 이상의 번호를 4자리가 되게 0으로 채우고 그 밖의 채움은 없음)으로만 받으며,
  `<CODE>-D-00002`와 번호 0은 거절합니다(경로 `id`의 `VALIDATION_FAILED`).
- **결함 하나는 근본 원인 하나이고, 관찰된 사례는 여러 개입니다.** 결함에 대한 각 관찰(증상, 로그, 사용자 보고)은 그 아래의 사례입니다(`ledger_record_case`).
  병합된 결함(`MERGED`, `merged_into`)은 사례를 그대로 유지하며, `ledger_defect`는 병합 대상 결함 아래에 그 사례를 보여 줍니다. `MERGED` 결함에는 새 사례를 추가할 수 없습니다.
- **공통코드.** 결함 분류, 결함 심각도, 관계 종류는 세 그룹 `DEFECT_CATEGORY`, `DEFECT_SEVERITY`, `RELATION_KIND`이며 한국어와 영어 이름을 가집니다.
  활성 값은 도구 정의에 반영됩니다. 폐기한 코드는 기존 행에 남고 새로 지정할 때는 거절됩니다. 모든 결함에는 심각도가 필수입니다. 분류나 심각도를 저장하는 쓰기(`ledger_save`의 생성과 수정)만 공통코드를 검사하며, 수정 기록과 상태 전이는 검사하지 않으므로 폐기된 `UNRATED`를 아직 가진 결함도 수정 기록, 전이, 검증을 할 수 있고 등급은 수정으로 매깁니다. 관계 종류의 방향은 코드에 속합니다.
  `caused_by`(a의 원인은 b)와 `blocks`(a가 b를 차단)는 방향이 있어 호출자가 준 `a`, `b`의 순서를 유지하므로 (a, b)와 (b, a)는 서로 다른 관계이고,
  `same_site`, `same_family`, `same_fix_unit`, `regression_of`는 방향이 없어 어느 순서로 주어도 하나의 관계입니다. `code-set`이나 관리 콘솔로 추가하는 종류도 방향을 정하며
  (`ledger_codes`가 보여 주고, `ledger_link` 정의의 종류 목록에 표시됩니다), 이미 관계가 있는 종류의 방향은 바꿀 수 없습니다.
- **생성 출처는 바뀌지 않습니다.** 새 행에는 `created_by`가 자동으로 기록되며, 수정과 검토자 전이는 이를 유지합니다.
- **시각은 UTC입니다.** 시각은 UTC(`Z`가 붙은 ISO-8601)로 저장하고 전송하며, 서버는 시간대를 변환하지 않습니다. JVM과 데이터베이스 세션은 UTC로 실행되어야 하며,
  그렇지 않으면 서버가 시작되지 않습니다(`ledger.schema.require-utc`).
- **ANSI SQL.** 제품은 JDBC 드라이버를 동봉하지 않습니다. 사용자가 원하는 데이터베이스의 JDBC 드라이버를 직접 구해 `driver-register`로 등록합니다.
  SQL은 ANSI이며 JDBC 표준을 사용하고, DBMS 고유 기능은 사용하지 않습니다. 스키마 파일에는 트리거, 프로시저, 뷰, 시퀀스, 자동 증가 열, 불리언 열이 없고,
  데이터베이스가 맡던 규칙은 애플리케이션 서비스가 강제합니다. 제품마다 다른 곳은 목록의 행 수 제한, 긴 텍스트 열 타입의 선언, 데이터베이스의 인코딩을 확인하는 탐침 세 곳이며, `SqlBuilder`가 연결의 제품에 따라 셋 다 고릅니다.
  이 판에서 시험하고 보장하는 데이터베이스는 PostgreSQL입니다. 다른 제품도 동작할 수 있으나 시험하지 않았으며, 이 세 곳 밖에서는 이 판이 특정 제품에 대한 맞춤(콜레이션,
  세션 설정)을 하지 않습니다. 데이터베이스는 UTF-8을 사용하고 문자열을 대소문자를 구분하여 비교해야 합니다.
  `migrate`는 아무것도 바꾸기 전에 인코딩을 확인합니다. 세 글자(한글 두 자와 이모지 하나)의 텍스트를 방언의 질의로 보내 데이터베이스가 다른 길이나 다른 텍스트를 돌려주면 진행을 거부하며,
  데이터베이스의 문자 집합 목록은 읽지 않습니다.
  스키마마다 기본 문자 집합을, 서버와 연결의 문자 집합을 따로 두는 데이터베이스(MariaDB와
  MySQL이 그렇습니다)에서는 셋 모두 utf8mb4여야 합니다. 검사 대상 스키마인 JDBC URL의 데이터베이스의 기본 문자 집합, 변수
  character_set_server, 연결의 문자 집합입니다. 앞의 둘이 아니면 `migrate`는 아무것도 바꾸기 전에 진행을 거부합니다.

MCP를 직접 호출하는 쪽은 호스트로 인증되지 않습니다. bearer 토큰은 호출자를 들여보내고, 서명된 증명이 작성자를 증명합니다.

## MCP 도구

서버는 `/mcp`(기본 `127.0.0.1:8765`)에서 Streamable HTTP로 MCP를 제공하고 `/healthz`에 응답합니다. 호출자는 bearer 토큰 `ledger.mcp.token`을 제시합니다.
결과는 키를 최상위에 담습니다. 모든 도구 입력은 닫혀 있어, 알 수 없는 인자는 거절됩니다. 객체나 배열 인자는 그 JSON 값으로 보내야 하며(문자열로 담은 JSON 문서는 거절됩니다),
텍스트 `null`은 인자가 없다는 뜻이 아니라 하나의 텍스트입니다. `fields`의 키를 JSON `null`로 쓰면 주지 않은 것으로 봅니다(필드를 건드리지 않으며, 증명이 서명하는 다이제스트에서도 빠집니다). 선택 필드는 빈 텍스트로 지웁니다.

쓰기 도구(로그인, 증명, 유효한 부여가 필요합니다). `ledger_whoami`를 뺀 모든 쓰기 도구는 필수 인자 `operationKey`를 받습니다.

| 도구 | 채널 | 내용 |
|---|---|---|
| `ledger_whoami` | author | 이 작성자 형식이 올바른지, 어떤 부여를 가졌는지 확인합니다(읽기 전용) |
| `ledger_save` | author | 결함, 작업, 조건을 만들거나 수정합니다(`VERIFIED` 결함은 수정하지 않습니다) |
| `ledger_transition` | author | 상태를 바꿉니다(`OPEN`, `SOURCE_FIXED`, `MERGED`, `ACTIVE`, `REPORTED_DONE` 등) |
| `ledger_record_fix` | author | 수정 커밋과 선택적 CI 메타데이터를 기록합니다. 수정한 세션이 `fixed_by`가 되고 `OPEN` 결함은 `SOURCE_FIXED`가 됩니다. `VERIFIED` 결함은 검토자가 다시 열기 전에는 거절됩니다 |
| `ledger_record_case` | author | 결함 아래에 관찰된 사례를 추가합니다(`MERGED` 결함은 거절되며 병합 대상을 알려 줍니다) |
| `ledger_link` | author | 결함, 작업, 조건을 연결하거나 연결을 제거합니다. 관계 종류 `caused_by`와 `blocks`는 방향이 있습니다(`a`가 주어이며, 연결 해제는 연결한 순서대로 양 끝을 적습니다) |
| `ledger_correct` | author | 이력 행이 잘못되었음을 표시합니다 |
| `ledger_batch` | author | 위 연산을 한 트랜잭션에서 최대 200개까지 처리합니다. 모두 반영되거나 모두 반영되지 않습니다. 배치에 `operationKey` 하나를 주며 연산에는 키가 없습니다 |
| `ledger_review` | reviewer | `VERIFIED`, `MET`, `NOT_MET`과 그 재개를 처리합니다(다시 연 `VERIFIED` 결함은 다시 수정하고 편집할 수 있습니다). 증거 링크가 필수입니다 |
| `ledger_review_correct` | reviewer | 검증 행이 잘못되었음을 표시합니다 |

첨부 도구(로그인, 증명, 작성자의 유효한 부여 하나 이상이 필요합니다. 아래 첨부 절을 보십시오).

| 도구 | 내용 |
|---|---|
| `artifact-begin` | 기록이 가리킬 수 있는 파일의 업로드를 시작합니다. `operationKey`는 필수이며 호출을 되풀이할 수 있게 합니다. `purpose`는 선택이고 `evidence`만 지원합니다 |
| `artifact-chunk` | 다음 청크(최대 262144바이트, base64)를 SHA-256과 함께 보냅니다. 마지막으로 반영된 청크를 다시 보내면 현재 상태로 답합니다. `operationKey`를 받지 않습니다(유일한 예외입니다. 업로드는 `artifact-begin`의 `operationKey`로 시작했고, 청크는 `uploadId`, `sequence`, `chunkSha256`으로 멱등이며 같은 순서 번호에 다른 내용을 보내면 충돌입니다) |
| `artifact-finish` | 받은 바이트를 선언한 크기와 SHA-256에 대조하고 파일을 첨부로 보관합니다. 같은 업로드 ID로 다시 부르면 같은 첨부 ID를 돌려줍니다. `operationKey`를 받지 않습니다(`uploadId`로 멱등입니다) |
| `artifact-status` | 업로드나 첨부의 상태, 크기, SHA-256, 만료, 진행을 보여 줍니다(경로나 저장 키는 절대 보여 주지 않습니다) |
| `artifact-read` | 보관된 첨부의 한 페이지를 복호화하고 검증해서 읽습니다 |

읽기 도구(증명과 부여가 필요하지 않습니다. 1.x의 원시 SQL 도구를 대체하는 정형 조회입니다).

| 도구 | 내용 |
|---|---|
| `ledger_get` | 여러 ID(결함, 작업, 조건을 섞어도 됩니다)의 상태와 판을 한 번에 봅니다. 쓰기 뒤 확인과 `expected_version` 확보에 씁니다 |
| `ledger_defect` | 결함 하나와 그 수정, 작업, 조건, 사례(병합된 결함의 사례 포함), 관계, `fixed_by`, 최근 변경을 보여 줍니다 |
| `ledger_task` | 작업 하나와 목적, 성공 조건, 출처, 담당, 완료를 보고한 세션, 결함, 조건을 보여 줍니다 |
| `ledger_history` | 이력과 모든 쓰기의 영수증을 ID, ID 범위, 대상, 프로젝트, 연산, 작성자, 기간으로 조회합니다. `include_before`를 주면 `before_data`를 더합니다 |
| `ledger_search` | 키워드로 결함, 작업, 조건을 대소문자 구분 없이 검색합니다. `match`는 `ANY`(기본) 또는 `ALL`입니다 |
| `ledger_defects` | 프로젝트의 결함을 상태, 심각도, 분류, 주의 종류, 담당으로 거릅니다 |
| `ledger_tasks` | 프로젝트의 작업을 상태, 담당, 주의 종류로 거릅니다 |
| `ledger_conditions` | 프로젝트의 릴리스 조건을 상태와 릴리스로 거릅니다 |
| `ledger_attention` | 확인이 필요한 항목입니다: `OPEN_WITH_FIX`, `FIXED_WITHOUT_COMMIT`, `OPEN_NO_OWNER`, `TASK_UNKNOWN`, `DONE_NOT_VERIFIED` |
| `ledger_stats` | 한 프로젝트 또는 전체의 상태별, 심각도별 건수입니다. `bucket`을 `day`로 주면 UTC 일별 신규 건수를 더합니다 |
| `ledger_projects` | 프로젝트의 ID, 코드, 제목입니다 |
| `ledger_codes` | 공통코드입니다. 폐기한 코드는 `include_inactive`를 주어야 나옵니다 |

목록형 읽기 도구는 `max_rows`(1에서 1000, 기본 50)와 `cursor`를 받으며, 응답에 `truncated`와 `next_cursor`가 붙습니다.
1.x의 `execute_sql_reader`와 `search_objects_reader` 도구는 제거되었으므로 위의 읽기 도구를 사용합니다.

공통코드가 바뀌면 서버가 `notifications/tools/list_changed`를 보내므로, 연결된 클라이언트는 재시작 없이 도구 정의를 다시 읽습니다. 다른 프로세스(관리 웹, `ledger-cli`)가
바꾼 내용은 `ledger.mcp.code-poll-interval` 안에 감지합니다. 새 도구는 서버가 시작될 때 등록됩니다.

`ledger_record_case`는 `defect_id`, `observation`(1자에서 4000자), `reason`을 받고, 선택으로 `evidence`, `user_quote`, `observed_at`(`Z` 또는 오프셋이 붙은 ISO-8601)을 받습니다.
`ledger_batch`에서는 같은 인자를 연산 `{"op": "record_case", ...}`에 넣습니다.

```json
{"defect_id": "WEB-D-0007", "observation": "checkout returns 500 for a gift card",
 "evidence": "request 7f3a, api/checkout.ts:88", "user_quote": "I can't pay with my gift card",
 "observed_at": "2026-11-05T10:00:00Z", "reason": "second report of the same failure"}
```

## 첨부

검수 보고서(또는 그 밖의 근거)는 첨부로 보관합니다. 데이터베이스에 본문 텍스트로 두지 않고, 흔적 없이 고칠 수 있는 로컬 파일로도 두지 않습니다. 기록의 이력 행은 `ledger_transition`, `ledger_review`, `ledger_review_correct`, `ledger_correct`, `ledger_record_fix`, `ledger_record_case`와 `ledger_batch`의 같은 연산이 받는 선택 인자 `attachment_id`로 첨부를 가리킵니다. 기록은 첨부가 있고 기록을 쓰는 작성자가 올린 것일 때만 쓰입니다.

- **업로드.** 업로드는 위의 MCP 도구 다섯 개로만 합니다. `artifact-begin`(필수 `operationKey`), 청크마다 `artifact-chunk`(최대 262144바이트), `artifact-finish`, 확인용 `artifact-status`와 `artifact-read`입니다. PC의 파일을 모델 밖에서 보내는 클라이언트 프로그램은 아직 정하지 못한 항목이며, 도구가 유일한 길입니다.
- **저장.** 데이터베이스에는 메타데이터와 파일 이름만 둡니다(`upload_staging`, `attachment`, `attachment_file`. 본문 열도 외래 키도 없습니다). 파일은 `ledger.attachments.directory` 아래에 128비트 무작위 이름으로, 확장자와 원본 이름 없이 두며, 청크마다 한 세그먼트로 AES-256-GCM 암호화하고(저장 키와 위치를 함께 인증합니다) 끝나면 읽기 전용으로 바꿉니다. 첨부는 바꾸거나 지우지 않습니다. 데이터베이스의 응용 계정은 첨부에 `SELECT`와 `INSERT`만 가집니다.
- **키.** `setup`이 첫 256비트 키 파일을 `ledger.attachments.key-directory`에 만들고(서비스 계정만 읽습니다) `ledger.attachments.active-key-id`를 정합니다. 교체는 `ledger-cli attachments-key-create`, `active-key-id`를 새 id로 바꾸기, 재시작, 그다음 `ledger-cli attachments-rekey --from=<old id>`로 옛 키의 파일을 다시 쓰는 순서입니다. 옛 키 파일은 현재 파일이나 열린 업로드가 쓰는 동안 남깁니다. 필요한 키 파일이 없으면 시작 점검이 키가 아니라 키 id를 알립니다.
- **오류.** `ARTIFACT_CHUNK_CONFLICT`, `CHUNK_HASH_MISMATCH`, `ARTIFACT_BUSY`(잠시 뒤 다시 보냅니다), `ARTIFACT_INCOMPLETE`, `ARTIFACT_HASH_MISMATCH`(바이트는 남습니다), `ARTIFACT_CORRUPTED`, `ARTIFACT_LIMIT`, `ARTIFACT_UNSUPPORTED_PURPOSE`, `ARTIFACT_EXPIRED`, `ARTIFACT_NOT_FOUND`, `ARTIFACT_OPERATION_CONFLICT`, `ARTIFACT_STORAGE_FULL`(디스크가 너무 찼으니 소유자가 비워야 합니다), `ARTIFACT_STORAGE_IO_ERROR`, `ARTIFACT_STORAGE_UNSAFE`(저장 경로를 이 용도로 쓸 수 없습니다. 폴더 자리에 파일이 있거나, 파일 자리에 폴더가 있거나, 이 파일 시스템이나 이 계정에서는 소유자 전용으로 만들 수 없는 경우입니다), `ARTIFACT_KEY_MISSING`(쓸 수 있는 키가 없습니다. 키 파일이 없거나, 비었거나, 256비트가 아닙니다. 키 파일을 되살립니다). 이 도구들의 오류에는 모두 `details.retryable`이 실립니다. 같은 요청을 나중에 다시 보내 성공할 수 있을 때만 `true`입니다(`ARTIFACT_BUSY`, 그리고 `details.limit`이 `staging-budget`이나 `open-uploads`인 `ARTIFACT_LIMIT`). `ARTIFACT_INCOMPLETE`는 `false`입니다(빠진 청크를 보낸 뒤 finish를 다시 보냅니다).
- **점검.** `ledger-cli attachments-check`는 없거나 손상된 첨부(지우지 않습니다)와 어떤 이력 행도 가리키지 않는 첨부(보존합니다)를 알리고, 행이 없는 파일이나 rekey가 대체한 파일을 `orphan-grace`가 지난 뒤 `orphans/`로 옮깁니다. `orphans/`를 비우는 길은 `--purge-orphans`뿐이며 감사 기록에 남습니다. 관리 웹은 같은 결과를 읽기 전용으로 보여 주고, 기록 화면에 텍스트 첨부는 이스케이프해서 보여 주며 그 밖의 형식은 응용을 거치는 내려받기로 제공합니다.
- **백업.** 첨부 폴더, 키 폴더, 데이터베이스를 함께 백업하고 복원합니다. 복원 뒤 `ledger-cli attachments-check`를 실행하면 없거나 손상된 첨부가 없어야 합니다. 격리된 파일의 행이 다시 생기면 점검이 그 파일을 제자리로 돌려놓습니다.
- **설치.** 설치 프로그램이 첨부 폴더(서비스 계정 소유, 다른 계정에는 닫힘), 비밀 디렉터리 옆의 키 폴더(`attachment-keys`), 설정을 만듭니다. 1.x의 근거는 기록된 URI와 해시 그대로 남으며 이전은 아무것도 가져오지 않습니다.

## 모듈

빌드는 Gradle 다중 모듈 프로젝트입니다.

| 모듈 | 내용 |
|---|---|
| `ledger-domain` | 집합체, 값 타입, 상태 규칙, 오류 코드와 도구 이름 목록인 `ToolContract` |
| `ledger-attestation` | `_hostAttestation` 토큰, RFC 8785 정규형 JSON, Ed25519 키 형식입니다. 훅과 서버가 함께 씁니다(JDK만 사용) |
| `ledger-database` | Spring Data JDBC 저장소, 스키마 파일 `V{n}__*.sql`과 그 적용기, 등록된 JDBC 드라이버의 적재기 |
| `ledger-application` | 응용 서비스: 쓰기 관문, 영수증, 이력, 쓰기와 읽기 연산, 부여, 세션 키 |
| `ledger-mcp-server` | Spring Boot MCP 서버(`ledger-mcp-server.jar`) |
| `ledger-admin-web` | 소유자용 Spring Boot 관리 웹(`ledger-admin-web.jar`) |
| `ledger-cli` | 소유자용 명령줄 도구(`ledger-cli.jar`) |
| `ledger-seed` | 새 설치의 시드 번들(기본 결함 분류와 설치 기본값을 담은 H2 파일 하나)과 그 읽기 코드. `ledger-cli`만 사용합니다 |
| `ledger-client-hook` | 모든 클라이언트가 함께 쓰는 훅 jar(`ledger-client-hook.jar`) |
| `ledger-migrate-1x` | 1.x 설치를 2.0으로 옮기는 별도의 도구(`ledger-migrate-1x.jar`, 1.x에서 업그레이드 참조). `ledger-domain`, `ledger-database`, `ledger-application`을 사용하고, 명령줄 기반과 비밀 저장소를 위해 `ledger-cli`의 클래스(plain jar이며 그 모듈의 라이브러리는 가져오지 않습니다)도 사용하므로 비밀 저장소는 서비스의 것과 같습니다. 다른 모듈은 이 모듈을 사용하지 않습니다 |
| `ledger-system-test` | 시험 코드만 있는 모듈: 빌드한 jar를 그 컴퓨터의 PostgreSQL에 대해 실행하는, 패키지된 제품의 시스템 시험 |

## 설정

설정은 Spring Boot 속성입니다. 파일, 명령줄(`--name=value`), 비밀 저장소로 줄 수 있습니다. 비밀은 파일이나 환경 변수에 넣지 않습니다.
`ledger-cli secret-set <name>`은 숨김 입력으로 비밀을 받아 비밀 디렉터리 안에서 그 이름의 용도에 맞는 폴더의 파일 하나로 저장하며, 프로세스들은 이 폴더를 Spring 설정 트리로 읽습니다.
비밀은 그 속성 이름으로 저장하며, 제품의 비밀 이름이 아닌 이름은 거절합니다(`UNKNOWN_SECRET_NAME`). 저장된 비밀을 출력하는 명령은 없습니다.

비밀 디렉터리(`<settings folder>/secrets`)에는 용도마다 폴더가 하나씩 있고, 각 프로세스는 자기에게 필요한 폴더만 읽어 들입니다. 그래서 MCP 서버의 설정에는 관리 웹의 비밀이 들어오지 않고,
관리 웹에는 에이전트의 토큰이 그 이름으로 들어오지 않습니다.

| 비밀(파일 이름이 속성 이름) | 읽는 곳 | 비밀 디렉터리의 폴더 |
|---|---|---|
| `ledger.jdbc.password` | MCP 서버, 관리 웹, `ledger-cli`, `ledger-migrate-1x` | `common` |
| `ledger.admin.token`, `ledger.admin.agent-token`, `spring.security.oauth2.client.registration.<id>.client-secret`(OpenID Connect) | 관리 웹 | `admin` |
| `ledger.mcp.token` | MCP 서버, `ledger-cli mcp-check` | `mcp` |
| `ledger.import.source.password` | `ledger-migrate-1x` | `tool` |

첨부의 키 파일은 비밀 디렉터리에 두지 않고 `<settings folder>/attachment-keys`에 둡니다. 어떤 설정 트리 아래에도 없는 위치입니다(Spring Boot는 트리 아래의 모든 파일을 그 프로세스의 속성으로 만듭니다).

| 키 | 사용처 | 의미 |
|---|---|---|
| `ledger.jdbc.url` | 전체 | 장부 데이터베이스의 JDBC URL |
| `ledger.jdbc.username` | 전체 | 데이터베이스 계정 |
| `ledger.jdbc.password` | 전체 | 데이터베이스 비밀번호(비밀) |
| `ledger.jdbc.pool-size` | 전체 | 연결 풀 크기, 기본 10 |
| `ledger.jdbc.driver` | 전체 | `driver-register`가 기록한 등록된 드라이버(jar, 클래스, SHA-256)이며 시작할 때 읽습니다 |
| `ledger.schema.name` | 전체 | 표를 담는 스키마. 없으면 `migrate`가 만들고, 지정하지 않으면 계정의 기본 스키마입니다 |
| `ledger.schema.require-utc` | 서버, CLI | JVM이나 데이터베이스 세션이 UTC가 아니면 시작을 거부합니다. 기본 true |
| `ledger.attestation.window` | 서버 | 증명의 시각이 서버 시각과 벌어질 수 있는 한도, 기본 `PT5M` |
| `ledger.mcp.token` | MCP 서버, `mcp-check` | 에이전트의 bearer 토큰, 16자 이상(비밀) |
| `ledger.mcp.allowed-hosts` | MCP 서버 | 허용하는 `Host` 값, 기본 `127.0.0.1`과 `localhost` |
| `ledger.mcp.allowed-origins` | MCP 서버 | 허용하는 브라우저 출처, 기본 없음 |
| `ledger.mcp.max-request-size` | MCP 서버 | 요청 본문의 최대 크기, 기본 1MB |
| `ledger.mcp.code-poll-interval` | MCP 서버 | 다른 프로세스의 코드 변경을 확인하는 주기, 기본 5s |
| `ledger.admin.token` | 관리 웹 | 관리 토큰, 16자 이상(비밀): `/api` 아래 읽기 전용 JSON 엔드포인트의 bearer입니다. `ledger.admin.sign-in=token`이면 로그인 페이지의 비밀번호이기도 하며 변경은 결코 승인하지 않습니다 |
| `ledger.admin.agent-token` | 관리 웹 | 필수: 에이전트의 bearer 토큰으로 `ledger.mcp.token`과 같은 값이어야 하고 `ledger.admin.token`과는 달라야 합니다. 관리 웹은 이 토큰을 `AGENT_TOKEN_FORBIDDEN`으로 거절합니다(비밀) |
| `ledger.admin.allowed-hosts` | 관리 웹 | 허용하는 `Host` 값(DNS 리바인딩 방어), 기본 `127.0.0.1`과 `localhost` |
| `ledger.admin.driver-file` | 관리 웹 | 드라이버 화면이 기록하는 드라이버 등록 파일, 기본 `/etc/pg-ledger-mcp/jdbc-driver.yaml` |
| `ledger.admin.driver-directory` | 관리 웹 | 드라이버 jar를 가져올 수 있는 유일한 디렉터리, 기본 `/etc/pg-ledger-mcp/drivers` |
| `ledger.admin.sign-in` | 관리 웹 | 소유자의 로그인 방식으로 `token`, `oidc`, `passkey` 중 정확히 하나이며 로그인 페이지는 그것만 제공합니다. 없으면 새 설치는 `passkey`이고, `ledger.admin.oidc.*` 설정이 있는데 선택이 없는 설치는 시작할 때 거절합니다(추측하지 않습니다) |
| `ledger.admin.oidc.owner-email` | 관리 웹 | `ledger.admin.sign-in=oidc`일 때 소유자의 이메일 주소 하나이며 공급자가 확인한 주소여야 합니다. 공급자 자체는 `spring.security.oauth2.client.*`입니다 |
| `ledger.admin.oidc.issuer` | 관리 웹 | `ledger.admin.sign-in=oidc`일 때 그 주소가 유효한 발급자(`iss`): 주소는 그것을 확인한 발급자 안에서만 의미가 있습니다 |
| `ledger.admin.passkey.rp-id` | 관리 웹 | 패스키가 묶이는 호스트 이름. 기본값은 `ledger.admin.allowed-hosts`의 루프백이 아닌 호스트 하나이고, 없으면 `localhost`입니다 |
| `ledger.admin.passkey.origins` | 관리 웹 | 브라우저가 보이는 origin(스킴, 호스트, 포트): `http://localhost`를 뺀 모든 origin은 `https`입니다. 기본값은 `https://<rp-id>`이고 `localhost`는 `http://localhost:<server.port>`입니다 |
| `ledger.admin.lockout.window` | 관리 웹 | 자격 증명 확인(관리 토큰, 패스키 로그인, 일회용 코드)을 세는 기간, 기본 60s |
| `ledger.admin.lockout.global-max-failures` | 관리 웹 | 기간 안에 모든 호출자의 자격 증명 확인이 이 횟수를 넘으면 지연이 시작되는 한도, 기본 50 |
| `ledger.admin.lockout.global-delay` | 관리 웹 | 한도를 넘은 뒤 자격 증명을 비교하기 전에 기다리는 시간, 기본 1s. 늦출 뿐 잠그거나 거절하지 않으며, 맞는 자격 증명은 언제나 로그인됩니다 |
| `ledger.admin.mcp.health-url` | 관리 웹 | 개요 화면에 보여 줄 MCP 서버의 `/healthz` URL. 없으면 설정하지 않은 것입니다 |
| `ledger.admin.mcp.timeout` | 관리 웹 | 그 확인의 제한 시간, 기본 2s |
| `ledger.attachments.directory` | 서버, CLI | 첨부 파일을 두는 위치입니다(설치 프로그램이 `<data folder>/attachments`로 정합니다). 정하지 않으면 첨부 도구는 등록되지 않습니다 |
| `ledger.attachments.key-directory` | 서버, CLI | 키 파일을 두는 위치로 `directory` 밖이어야 합니다(안이면 시작이 실패합니다). 설치 프로그램이 비밀 디렉터리 안이 아니라 그 옆인 `<settings folder>/attachment-keys`로 정합니다 |
| `ledger.attachments.active-key-id` | 서버, CLI | 새 업로드를 쓰는 키입니다. `setup`이 `local.yaml`에 적습니다. 키 파일이 없으면 서버가 시작하지 않습니다 |
| `ledger.attachments.max-file-bytes` | 서버, CLI | 파일 하나의 최대 크기, 기본 64MB |
| `ledger.attachments.staging-budget-bytes` | 서버, CLI | 열린 업로드 전체가 선언할 수 있는 크기, 기본 512MB |
| `ledger.attachments.attachment-budget-bytes` | 서버, CLI | 보관 첨부 전체(와 열린 업로드)가 가질 수 있는 크기, 기본 10GB. 넘으면 새 `artifact-begin`을 거절하고 있는 첨부는 지우지 않습니다 |
| `ledger.attachments.max-open-per-owner` | 서버, CLI | 작성자 한 명이 열어 둘 수 있는 업로드 수, 기본 4 |
| `ledger.attachments.disk-margin-bytes` | 서버, CLI | 파일을 쓴 뒤에도 `directory`의 디스크에 남겨 두어야 하는 공간, 기본 64MB(가장 큰 파일 하나의 크기). `usable - size`가 이보다 작으면 begin이나 청크를 `ARTIFACT_STORAGE_FULL`로 거절하며, 소유자가 디스크를 비워야 합니다 |
| `ledger.attachments.upload-ttl` | 서버, CLI | 마지막 청크 뒤 업로드가 사는 시간, 기본 10m |
| `ledger.attachments.orphan-grace` | 서버, CLI | 행 없는 파일(또는 대체된 파일)을 `attachments-check`가 `orphans/`로 옮기기 전 유예, 기본 24h |
| `ledger.attachments.reaper-interval` | MCP 서버 | 만료된 업로드를 지우는 주기, 기본 1m |
| `ledger.secrets.home` | `ledger-cli` | 설정, 드라이버 등록, 드라이버 jar, 비밀 디렉터리를 담는 설치 디렉터리, 기본 `/etc/pg-ledger-mcp`(Windows 설치기는 자기 것을 지정합니다) |
| `ledger.secrets.directory` | `ledger-cli` | 비밀 디렉터리(폴더 `common`, `admin`, `mcp`, `tool`을 담으며 그 자체는 설정 트리가 아닙니다), 기본 `/etc/pg-ledger-mcp/secrets` |
| `ledger.secrets.instance` | `ledger-cli` | 기본 경로가 따르는 인스턴스 이름, 기본 `pg-ledger-mcp` |
| `ledger.cli.driver.directory` | `ledger-cli` | `driver-register`가 jar를 가져오는 디렉터리, 기본 `/etc/pg-ledger-mcp/drivers` |
| `ledger.cli.driver.file` | `ledger-cli` | `driver-register`가 기록하는 등록 파일, 기본 `/etc/pg-ledger-mcp/jdbc-driver.yaml` |

서버는 루프백에서만 수신합니다(MCP `127.0.0.1:8765`, 관리 웹 `127.0.0.1:8865`). 두 서버의 코드에 고정되어 있어 주소를 바꾸는 설정은 없습니다. 외부에
공개하려면 운영자가 고른 역방향 프록시(HTTPS를 종단함)를 같은 호스트에 두고, 그 프록시가 넘기는 호스트 이름 하나를 서비스의 허용 호스트에
적습니다(`--mcp-host`, `--admin-host`). 관리 웹은 루프백 주소에서 온 `X-Forwarded-For`와 `X-Forwarded-Proto`만 신뢰합니다.
서버는 드라이버 등록 파일과 비밀 디렉터리를 `spring.config.import`로 읽으며 설치기가 이를 서비스 정의에 넣습니다(설치 참조). `ledger-cli`는 기본으로 읽습니다.

## 설치

설치기 두 개가 세 가지 대상을 다룹니다. 개인 PC와 Windows 서버는 `deploy\install.cmd`를, Linux 서버는 `sudo deploy/install.sh`를 실행합니다.
대상은 옵션으로만 다르며, 어느 대상에서나 두 서버는 루프백 주소에서만 듣습니다(밖에서 접근하기 참조). macOS는 지원하지 않습니다.
개인 PC가 Linux 데스크톱이면 Linux 절차를 따릅니다. 빌드에는 JDK 25가 필요하고, 호스트에는 모든 계정이 실행할 수 있는 경로(`/home` 아래가 아닌 곳, 서비스 계정도 실행하기 때문입니다)에 Java 25 런타임이 필요합니다.

모든 대상에 공통인 것은 다음과 같습니다.

- **서비스 계정 하나.** MCP 서버와 관리 웹은 어떤 사람에게도 속하지 않는 운영체제 계정 하나로 실행합니다. Linux는 시스템 사용자 `pg-ledger-mcp`(로그인 없음,
  root 아님), Windows는 내장 계정 LocalService(비밀번호 없음)입니다. 목적은 장부의 비밀인 관리 토큰과 데이터베이스 비밀번호를 그 컴퓨터에서 일하는 사람의
  계정과 그 아래에서 도는 에이전트로부터 떼어 놓는 것이며, 비밀 디렉터리는 서비스 계정만 읽을 수 있습니다(Windows에서는 설치한 사용자와, 읽기만 하는 LocalService가 읽고 다른 누구도 읽지 못합니다). 비밀은 용도마다 폴더 하나에 두고 각 서비스는 자기 폴더와 공통 폴더만 읽어 들이므로, MCP 서버의 프로세스는 더 이상 관리 토큰이나 OpenID Connect 클라이언트 비밀을 설정에 가지지 않습니다(첨부의 키 파일도 어느 서비스의 속성도 아닙니다). 이것이 하지 못하는 일도 있습니다. 두 서비스는 같은 운영체제 사용자로 실행되므로 파일 권한이 한 서비스가 다른 서비스의 폴더를 읽는 것을 막지는 못합니다. 개인판은 설치 대상 전체를 신뢰하며, 이번 변경은 어떤 프로세스도 다른 용도의 비밀을 자기 환경에 싣지 않게 하는 것입니다. 한 설치를 인스턴스라고 부르며 서비스 이름은 그것을
  따릅니다. 인스턴스는 `--name=<name>`이 다른 이름을 주지 않으면 `pg-ledger-mcp`이고, 서비스는 `<name>`과 `<name>-admin`입니다(기본값은 `pg-ledger-mcp`와
  `pg-ledger-mcp-admin`). Linux 사용자, 디렉터리(`/opt/<name>`, `/etc/<name>`, Windows는 `C:\ProgramData\<name>`), WinSW id가 이름을 따르므로, 두 번째
  인스턴스나 기본 이름으로 아직 1.x가 도는 호스트 옆의 설치에는 자기 `--name=<name>`과 자기 포트만 있으면 됩니다.
- **데이터베이스 계정 둘.** 설치 전에 DBMS의 도구로 만들어 둡니다(계정 생성은 ANSI SQL이 아니며 제품에 속하지 않습니다). 앱 계정은 두 서버와 `ledger-cli`의
  소유자 명령이 함께 쓰며, `migrate`가 `ledger-database/src/main/resources/db-templates/dml-grants.sql`의 최소 권한을 부여합니다(DDL 없음, 이력에
  `UPDATE`와 `DELETE` 없음). DDL 계정은 `migrate`와 `setup`에만 쓰며, 비밀번호는 숨김 입력으로 받고 저장하지 않습니다. 데이터베이스의 세션 시간대는
  UTC여야 하고, 앱 계정이 스키마를 쓸 수 있도록 허용되어 있어야 합니다. 이는 DBMS의 단계이며 제품 SQL에 포함되지 않습니다. `setup`이 이를 확인하고 빠져 있으면
  알려 줍니다(첫 설정 참조).
- **jar와 드라이버.** `./gradlew assemble`로 jar를 빌드하고(jar만 만듭니다. `./gradlew build`는 시험까지 실행하며 시험에는 별도의 PostgreSQL이 필요합니다. 빌드와 시험 참조), jar를 한 디렉터리에 모읍니다. 호스트에 필요한 jar 세 개와, 1.x 설치를 옮기는 도구인
  `ledger-migrate-1x.jar`입니다(1.x에서 업그레이드 참조). 설치 프로그램은 호스트의 jar 세 개만 사용합니다. 이 도구는 서비스에 속하지 않으므로 설치 프로그램은 도구를 알지 못하고 복사하지도 않습니다.
  데이터베이스의 JDBC 드라이버는 직접 구하며 그 클래스를 지정합니다. 제품에 동봉되어 있지 않습니다.

```sh
mkdir dist
cp ledger-cli/build/libs/ledger-cli.jar ledger-mcp-server/build/libs/ledger-mcp-server.jar ledger-admin-web/build/libs/ledger-admin-web.jar ledger-migrate-1x/build/libs/ledger-migrate-1x.jar dist/
```

- **설치기가 하는 일.** 설치기는 얇습니다. Java가 할 수 없는 것만 직접 실행하고, 나머지는 모두 `ledger-cli`의 명령입니다. 호스트의 파일 작업은 Java가
  합니다. `render-deploy --check`가 모든 옵션을 먼저 검증하고, `prepare-host`가 폴더를 소유자·모드·ACL과 함께 만들고, jar와 드라이버 jar를 복사하고, 서비스 정의와
  설정을 씁니다(`application.yaml`과 서비스별 파일 하나씩이 한 디렉터리에 놓입니다. 운영자의 설정은 그곳의 `local.yaml`에 두며, 마지막에 읽고 교체하지 않습니다).
  이어서 설치기는 `ledger-cli`로 비밀을 저장합니다. 앱 계정의 데이터베이스 비밀번호(숨김 입력), `ledger.admin.token`과 `ledger.mcp.token`(생성하며 그 단계의 끝에
  한 번만 표시), 복사본 `ledger.admin.agent-token`입니다. 그리고 드라이버를 등록하고, DDL 계정으로 `setup`을 실행하고(스키마, 부여, 시드 코드. 첫 설정 참조), 두 서비스를 시작합니다. Java가 할 수 없어
  스크립트에 남는 것은 운영체제 사용자 생성(`useradd`), 그 사용자로 명령 시작(`runuser`), 서비스 관리자(`systemctl`, WinSW, `sc.exe`)입니다.
  Java 25 확인은 JVM이 합니다. jar는 Java 25용으로 컴파일되어 다른 런타임은 읽지 못합니다. 내려받거나 설치하는 것은
  없습니다. 더 새로운 jar로 다시 실행하면 업그레이드입니다. 파일과 서비스는 교체되고, 스키마와 권한은 다시 적용되며, 비밀·토큰·데이터는 유지됩니다. 중간에 멈춘 실행도 같은 방법으로 고칩니다. 같은 옵션으로 다시 실행하면 됩니다(비밀을 저장하는 명령은
  데이터베이스를 쓰지 않습니다).
  설정 디렉터리는 도구의 `ledger.secrets.home`이며, 서비스는 같은 출처를 `spring.config.import`로 읽습니다.

`deploy/install.sh`의 옵션은 `--name=value`로 줍니다. 스스로 읽지 않는 옵션은 `render-deploy`로 넘어갑니다.

| 옵션 | 뜻 |
|---|---|
| `--name=<name>` | 인스턴스 이름, 기본값 `pg-ledger-mcp`: 소문자, 숫자, 하이픈, 23자 이내, `-admin`으로 끝나지 않음. Java가 무엇이든 만들기 전에 검증합니다 |
| `--java=<path>` | Java 25 런타임. 기본값은 root의 `PATH`에 있는 `java`이며, 서비스 사용자도 실행하므로 모든 계정이 실행할 수 있는 경로(`/home` 아래가 아닌 곳)여야 하고, 스크립트가 아무것도 만들기 전에 이를 확인합니다 |
| `--jars=<dir>` | jar 세 개가 있는 디렉터리 |
| `--driver-jar=<file>` | JDBC 드라이버 jar |
| `--driver-class=<class>` | 드라이버 클래스. JDBC URL을 받아들여야 합니다 |
| `--owner-account=<account>` | DDL 계정(비밀번호를 물어봅니다) |
| `--app-account=<account>` | 앱 계정 |
| `--jdbc-url=<url>` | 장부 데이터베이스의 JDBC URL(비밀번호를 넣지 않습니다) |
| `--schema=<name>` | `ledger.schema.name`, 선택 |
| `--mcp-port=<n>` | MCP 서버의 루프백 포트, 기본값 8765 |
| `--admin-port=<n>` | 관리 웹의 루프백 포트, 기본값 8865 |
| `--mcp-host=<host>` | 선택 옵션입니다. 운영자의 역방향 프록시가 MCP 서버를 위해 `Host` 헤더로 넘기는 호스트 이름 하나(소문자, 포트 없음)이며, MCP 서버의 `Host` 검사가 `127.0.0.1`과 `localhost`(항상 허용)에 더해 허용합니다. 주지 않으면 서버는 루프백으로만 접근합니다 |
| `--admin-host=<host>` | 선택 옵션이며 관리 웹에 대해 같은 뜻이고, `--mcp-host`와 다른 이름이어야 합니다. 이 옵션이 있으면 설치기가 `admin.yaml`에 `ledger.admin.passkey.rp-id`(그 이름)와 `ledger.admin.passkey.origins`(`https://<name>`)도 써서, 원격 설치의 passkey가 직접 고치지 않아도 동작합니다(다른 구성은 `local.yaml`에 `rp-id`와 `origins`를 지정) |
| `--stdin` | 비밀번호를 터미널 대신 표준 입력에서 읽음. 먼저 앱 계정의 비밀번호(아직 저장되어 있지 않을 때만), 그다음 DDL 계정의 비밀번호. `docker login --password-stdin`과 같은 관례입니다 |

`render-deploy`와 `prepare-host`에는 설치기가 스스로 정하는 옵션이 다섯 더 있습니다. `--platform=<platform>`(`linux`가 기본값, 또는 `windows`),
`--home=<path>`(Windows: 설치 디렉터리), `--java=<path>`(Java 실행 파일), `--root=<directory>`(파일을 제 경로 대신 한 디렉터리 아래에 둠. 제자리에 두기 전에
살펴볼 때 씁니다), 그리고 `render-deploy`에만 있는 플래그 `--check`(모든 옵션을 검증하고 아무것도 바꾸지 않음. 설치기가 가장 먼저 실행하므로, 이름을 포함해
잘못된 값은 아무것도 없을 때 설치를 멈춥니다)입니다. `prepare-host`는 `--jars=<dir>`, `--driver-jar=<file>`, Windows에서는 `--winsw=<file>`도 받습니다.
`--root` 없이는 플랫폼이 명령을 실행하는 호스트의 플랫폼이어야 합니다. 다른 플랫폼의 경로는 이 호스트의 경로가 아니어서 작업 디렉터리 아래에 쓰이게
되므로, 명령은 아무것도 읽거나 쓰기 전에 `--platform`을 지목해 거절합니다.

### Linux 서버

root로 실행합니다. 사용자 `pg-ledger-mcp`가 없으면 만들고, `prepare-host`(Java)가 `/opt/<name>`(root, 0755), `/etc/<name>`(서비스 사용자, 0750), 그 아래
`drivers` 폴더(root, 0755)와 `secrets` 폴더 및 그 아래의 `common`, `admin`, `mcp`, `tool` 폴더, 키 폴더 `attachment-keys`(서비스 사용자, 0700)를 만들고, jar와 드라이버를 복사하고, 유닛을 `/etc/systemd/system`에 렌더하며, 유닛은 이어서 활성화합니다.
비밀은 서비스 사용자가 저장합니다. 설치 프로그램이 렌더하지 않은 파일을 렌더한 파일이 대체하면 그 파일은 `<file>.backup`으로 보관합니다. 1.x 설치를 옮기려면 1.x에서 업그레이드를 참조합니다.

```sh
sudo deploy/install.sh --jars=dist --driver-jar=<driver-jar> --driver-class=<driver-class> --owner-account=<ddl-account> --app-account=<app-account> --jdbc-url=<jdbc-url>
```

### 밖에서 접근하기

두 서버는 `127.0.0.1`에서만 수신하며, 이를 바꾸는 설정은 없습니다. 다른 컴퓨터에서 접근하게 하려면 운영자가 같은 호스트에 역방향 프록시와 TLS 인증서를
운영자가 고른 도구로 둡니다. 설치기는 프록시 설정을 쓰지 않고, 인증서를 발급하지 않으며, 갱신 장치를 설치하지 않습니다. 프록시는 `127.0.0.1:8765`(MCP 서버)와
`127.0.0.1:8865`(관리 웹)로 연결하고 클라이언트의 `Host` 헤더를 그대로 넘깁니다. 각 서비스에는 프록시가 그 서비스를 위해 넘기는 호스트 이름 하나를
`--mcp-host=<host>`와 `--admin-host=<host>`로 줍니다. 그 이름은 해당 서비스의 `Host` 검사에서 `127.0.0.1`과 `localhost`(항상 허용)에 더해 허용됩니다.
관리 웹의 이름이 있으면 설치기가 그에 맞는 passkey 설정도 씁니다(위의 옵션 참조). 관리 웹은 루프백 주소에서 온 `X-Forwarded-For`와 `X-Forwarded-Proto`만
신뢰하므로, 프록시는 이 두 헤더를 자신이 받은 연결의 주소와 스킴으로 덮어써야 합니다. 이는 로그인 실패 뒤의 잠금과 이어져 있습니다. 관리 웹은 시도를
클라이언트 주소별로 세므로, `X-Forwarded-For`를 넘기지 않는 프록시는 모든 클라이언트를 프록시의 루프백 주소 하나로 보이게 하고, 그러면 누군가의 실패한
시도가 소유자까지 함께 잠급니다. 오래 유지되는 MCP 연결(Streamable HTTP)에는 충분히 긴 프록시 타임아웃이
필요합니다. 인증서, 그 갱신, 방화벽도 운영자의 몫입니다.

### Windows: 개인 PC 또는 서버

관리자 권한 프롬프트에서 실행합니다. 스크립트의 인자는 아래 순서이며, 서비스는 Spring Boot 문서가 Windows 서비스로 안내하는 WinSW로 등록합니다. WinSW 2.x
실행 파일은 JDBC 드라이버처럼 직접 구하며, 설치기는 이를 동봉하거나 내려받지 않습니다.

| 인자 | 뜻 |
|---|---|
| `[--stdin]` | 선택이며 첫 번째 인자로만 줄 수 있음. 비밀번호를 터미널 대신 표준 입력에서 읽음(먼저 앱 계정의 비밀번호, 아직 저장되어 있지 않을 때만, 그다음 DDL 계정의 비밀번호). `install.sh --stdin`과 같으며 나머지 인자의 순서는 그대로임 |
| `<jars-dir>` | jar 세 개가 있는 디렉터리 |
| `<winsw-exe>` | WinSW 2.x 실행 파일 |
| `<driver-jar>` | JDBC 드라이버 jar |
| `<driver-class>` | 드라이버 클래스 |
| `<ddl-account>` | DDL 계정 |
| `<app-account>` | 앱 계정 |
| `"<jdbc-url>"` | JDBC URL, 따옴표 안에 |
| `"<name>"` | 선택, 따옴표 안에: 인스턴스 이름, 기본값 `pg-ledger-mcp` |
| `"<more options>"` | 선택, 이름 뒤에 따옴표 안에: `render-deploy`의 다른 옵션, 예를 들어 서버의 호스트 이름 |

```bat
deploy\install.cmd dist <winsw-exe> <driver-jar> <driver-class> <ddl-account> <app-account> "<jdbc-url>"
```

외부에서 접근하는 서버는 서비스마다 호스트 이름을 더하고, 직접 준비한 역방향 프록시를 두 루프백 포트 앞에 둡니다(밖에서 접근하기 참조).

```bat
deploy\install.cmd dist <winsw-exe> <driver-jar> <driver-class> <ddl-account> <app-account> "<jdbc-url>" "pg-ledger-mcp" "--mcp-host=<mcp-host> --admin-host=<admin-host>"
```

모든 것은 `C:\ProgramData\<name>`에 놓입니다. 두 서비스는 LocalService로 실행하고, 실패하면 다시 시작하며(WinSW 설정), 로그는 `logs` 폴더에 씁니다.
`prepare-host`는 폴더를 SYSTEM과 Administrators 외 모든 계정에게 닫고 같은 단계에서 LocalService에 필요한 접근을 줍니다(Java가 ACL로 하며 `icacls`는 쓰지 않습니다).
jar는 읽고 실행하고, 설정, 로그, 데이터는 바꿀 수 있습니다. 비밀은 설치한 사용자의 것입니다. 비밀 폴더, 그 아래의 용도별 폴더, 키 폴더 `attachment-keys`, 그 안에 만드는 비밀은 설치한 사용자(전체)와
LocalService(읽기만)의 ACL로 만들어지고 다른 누구에게도 접근이 없으므로, 서비스는 읽을 수 있고 에이전트가 실행되는 계정은 읽을 수 없습니다.
LocalService에 넘기는 것은 없으므로(설치한 사용자가 소유자로 남고, 소유권을 가져오는 권한도 쓰지 않습니다) `ledger-cli`의 소유자 명령은 설치한 사용자에게 계속 동작하고, 설치기를 다시
실행해도 모든 것이 그대로입니다. 같은 사용자로 다시 실행합니다(개인판은 사용자가 한 사람입니다). 소유자 작업은 관리 웹에서도 합니다(관리 웹 절 참고: 패스키 또는
OpenID Connect로 로그인하고, 첫 패스키는 `passkey-code`로 등록합니다). 개인 PC에서는 관리 웹을 `http://localhost:8865`로 엽니다(포트는 `--admin-port=<n>`입니다). 관리 웹은
`Secure` 세션 쿠키를 설정하는데, 주요 브라우저는 `localhost`를 보안 컨텍스트로 취급하므로 이를 받아들이고, 패스키도 `localhost`에서는 HTTPS 없이 동작합니다.

### 첫 설정

설치 스크립트는 `setup`을 호출하며, 업그레이드한 뒤나 직접 준비한 데이터베이스에서는 운영자가 직접 실행합니다. 새 장부는 처음에 담을 데이터가 필요하므로 `ledger-cli`는
시드 번들을 함께 가지고 있습니다. 시드 번들은 파일 `ledger-seed.mv.db`이며, 빌드가 `ledger-cli` 모듈의 텍스트 파일 `src/seed/defect-categories.tsv`와
`src/seed/install-defaults.properties`에서 Java 프로그램으로 만들어 `ledger-cli.jar`에 넣습니다. 이 파일은 저장소에 커밋하지 않습니다. 번들에는 기본 결함 분류 14개
(코드, 한국어·영어 이름과 설명)와 설치 기본값(인스턴스 이름과 두 포트)이 들어 있으며, 비밀값은 없습니다. 파일 형식이 H2 데이터베이스일 뿐이며 H2는 여기서 그 용도로만
쓰입니다. 장부는 언제나 운영자가 설치한 데이터베이스 시스템에 저장되고, H2 드라이버나 H2의 JDBC URL을 등록하려 하면 `driver-register`와 관리 웹의 드라이버 화면이
거절합니다.

`setup [--owner-account=<account>] [--app-account=<account>] [--stdin]`은 `migrate`의 옵션을 그대로 받고, 다음 네 가지를 이 순서로 합니다.

1. `migrate`와 똑같이 스키마를 마이그레이션하고 앱 계정의 부여를 적용합니다.
2. 앱 계정의 연결로, 그 계정이 스키마를 쓸 수 있는지 확인합니다. 스키마를 쓸 수 있는 권한은 데이터베이스 시스템의 문장이며 제품 SQL에 포함되지 않습니다. 따라서 그 권한이
   빠져 있으면 `setup`은 종료 코드 `3`으로 멈추고, 계정을 밝히고, 운영자가 데이터베이스 시스템의 도구로 그 권한을 줘야 한다고 알려 줍니다. 그 시점에는 그 밖의 것이 바뀌지
   않았으며, 그 단계를 한 뒤 `setup`을 다시 실행하면 멈춘 곳에서 이어집니다.
3. 장부에 아직 없는 시드 코드를 `code-set`과 관리 웹이 쓰는 서비스로 추가합니다. 코드 개정 번호와 소유자 감사 기록이 그대로 남습니다. 이미 있는 코드는 절대 바꾸지
   않습니다. 이름을 바꾸거나 퇴역시킨 코드는 운영자가 둔 그대로 남습니다.
4. 시드와 데이터베이스를 코드 개수와 SHA-256 다이제스트로 대조하고 보고를 출력합니다. `setup`을 다시 실행해도 아무것도 바뀌지 않고 같은 보고가 나옵니다. 시드와 다른 운영자의
   코드는 바뀐 것으로 세고 그대로 둡니다.

설치 기본값은 시드 번들 한 곳에 적습니다. `render-deploy`와 `prepare-host`는 옵션을 주지 않으면 이름과 포트를 거기서 가져오고, 설치 스크립트는
`install-default <name>`(`instance-name`, `mcp-port`, `admin-port`)으로 받으므로 어느 스크립트도 기본값의 사본을 갖지 않습니다.

### 설치 뒤

1. `ledger-cli mcp-check`로 MCP 서버를 확인합니다. 등록된 드라이버가 없으면 MCP 서버는 이유를 밝히고 멈추며, 관리 웹은 로그인 화면만 제공하는 설정 모드로 시작합니다.
   그 화면이 `ledger-cli driver-register`로 드라이버를 등록하라고 알려 줍니다(데이터베이스가 없으면 로그인에 쓸 패스키가 없으므로 관리 웹의 드라이버 화면은 콘솔 화면입니다).
2. 소유자 쪽 설정을 합니다. `project-add`, `code-set`을 실행합니다. 1.x에서 온 경우에는 프로젝트를 직접 만들지 말고 1.x에서 업그레이드를 따릅니다. Linux에서는
   `ledger-cli`를 서비스 사용자로 실행합니다. 예: `sudo -u <name> java -jar /opt/<name>/ledger-cli.jar --ledger.secrets.instance=<name> project-add ...`.
3. 클라이언트 훅을 설치하고(클라이언트 훅 참조), 세션마다 `key-register`로 키를 등록한 뒤 `grant`로 부여합니다.

`migrate`는 파일 `V{n}__*.sql`을 순서대로 적용하고, 시도마다 파일의 SHA-256과 함께 `schema_history` 표에 기록합니다. 표는 버전의 시도마다 한 행을
가지며 열은 `version`, `attempt`, `script`, `digest`, `applied_at`, `outcome`(`SUCCEEDED` 또는 `FAILED`)입니다. 시도 하나는 트랜잭션 하나입니다. 시도는
자기 행을 `FAILED`로 먼저 넣고 마지막에 그 한 행을 `SUCCEEDED`로 바꿉니다. 파일이 실패하면 시도를 되돌리고 행은 `FAILED`로 남으며, 다음 실행은
이전 행을 바꾸거나 지우지 않고 그 버전을 다음 시도로 다시 적용합니다. `migrate`를 동시에 두 번 실행해도 안전합니다. 두 번째는 데이터베이스에서 첫 번째를
기다린 뒤 첫 번째가 적용한 것을 건너뜁니다. 적용한 뒤에 바뀐 파일이 있으면 `migrate`는 아무것도 적용하기 전에 멈추고 모든 프로세스도 시작할 때 멈추며,
그 일로 행을 더하지 않습니다. 데이터베이스의 인코딩은 UTF-8이어야 하며 `migrate`는 아무것도 바꾸기 전에 방언의 탐침 질의로 이를 확인하고, 스키마 파일의 긴 텍스트 타입은 장부의 데이터베이스 방언이 정하며, `migrate`는 연결의 제품 이름에서 방언을 가져옵니다(그에 대한 설정은 없습니다).
각 선언은 대상 데이터베이스의 공식 문서에서 가져오며, 길이를 생략하면 기본 한도가 작아지는 데이터베이스의 선언에는 길이를 적습니다.
JDBC 드라이버의 타입 정보는 드라이버가 판단할 수 없는 형태로 보고할 수 있으므로 쓰지 않습니다.

장부는 Flyway를 쓰지 않습니다. 최신 Flyway는 DBMS마다 모듈이 있어야 하고 모듈이 없는 데이터베이스는 거부하지만, 장부는 사용자가 드라이버를 등록하는 어떤
데이터베이스에도 ANSI SQL을 요구하기 때문입니다. 또 서버가 시작할 때가 아니라 이 명시적인 명령으로 마이그레이션하는 까닭은 서버가 DDL 계정의 비밀번호를
갖지 않기 때문입니다. 서버는 시작할 때 스키마를 확인만 하고, 적용하지 않은 파일이 있으면 시작을 거부하며 `ledger-cli migrate`를 알려 줍니다. 그래서
서버가 침해되어도 스키마는 바뀌지 않습니다.

## ledger-cli

`java -jar ledger-cli.jar <command> [arguments]`. 옵션은 `--name=value` 형태로 씁니다. Spring 속성(`--ledger.jdbc.url=...`처럼 점이 있는 이름)도 같은 방식으로 전달합니다.
이 도구는 비밀 디렉터리를 소유한 사용자(Linux에서는 서비스 사용자)로 실행하며, 모든 소유자 작업은 그 사용자 이름으로 기록됩니다.

| 명령 | 구문 | 내용 |
|---|---|---|
| `secret-set` | `secret-set <name> [--stdin] [--if-absent]` | 숨김 입력으로 비밀을 저장합니다. `--stdin`이면 표준 입력의 첫 줄에서 받으며(인수나 환경 변수로는 받지 않습니다), `--if-absent`이면 이미 있는 비밀을 그대로 두고 아무것도 묻지 않습니다 |
| `driver-register` | `driver-register --jar=<path> --class=<driver-class>` | JDBC 드라이버를 등록합니다. 데이터베이스가 필요하지 않습니다 |
| `token-generate` | `token-generate <name> [--print] [--copy-to=<name>]` | 무작위 토큰(`SecureRandom` 32바이트)을 비밀로 저장합니다. 이미 있는 비밀은 그대로 두며, `--print`는 방금 만든 토큰을 한 번만 보여 줍니다. `--copy-to`는 같은 토큰을 두 번째 이름으로도 한 번 저장합니다 |
| `migrate` | `migrate [--owner-account=<account>] [--app-account=<account>] [--stdin]` | 아직 적용하지 않은 스키마 파일을 적용하며(`--owner-account`를 주면 DDL 계정으로 실행하고 그 비밀번호는 입력받거나 `--stdin`이면 표준 입력에서 읽으며 저장하지 않습니다), 이어서 `dml-grants.sql`의 권한을 앱 계정에 부여합니다 |
| `setup` | `setup [--owner-account=<account>] [--app-account=<account>] [--stdin]` | 첫 설정입니다. `migrate`처럼 마이그레이션하고, 앱 계정이 스키마를 쓸 수 있는지 확인하며(쓸 수 없으면 종료 코드 `3`과 빠진 단계를 알림), 없는 시드 코드를 추가하고(있는 코드는 바꾸지 않음), 시드와 데이터베이스를 개수와 다이제스트로 대조합니다. 다시 실행해도 아무것도 바뀌지 않습니다 |
| `key-register` | `key-register <author> <public-key-or-block> <fingerprint> [--replace=<current-fingerprint>]` | 세션이 출력한 공개 키를 작성자에 묶습니다. 유효한 키는 `--replace`가 있어야 교체합니다 |
| `key-revoke` | `key-revoke <author> --fingerprint=<fingerprint>` | 작성자의 유효한 키를 닫습니다 |
| `passkey-code` | `passkey-code [--remove-existing]` | 관리 웹에 소유자의 패스키를 등록하는 일회용 코드를 발급합니다(한 번만 보여 주고, 15분간 유효하며, 해시로만 저장합니다. 새 코드는 쓰지 않은 이전 코드를 끝냅니다). `--remove-existing`은 패스키를 모두 잃은 소유자를 위해 유효한 패스키를 먼저 모두 닫습니다 |
| `project-add` | `project-add <id> <code> <title>` | 프로젝트를 추가합니다 |
| `code-set` | `code-set <group> <code> <name-ko> [<description-ko>] [--name-en=<text>] [--description-en=<text>] [--sort=<n>] [--inactive] [--direction=<direction>]` | 공통코드를 추가하거나 수정합니다. `--direction`(`GIVEN` 또는 `CANONICAL`)은 `RELATION_KIND` 그룹용입니다(`ledger_link` 도구 참고) |
| `codes` | `codes [<group>]` | 공통코드를 나열합니다 |
| `grant` | `grant <author> <project> <scope>... [--note=<text>] [--expires=<ISO-8601 with Z or an offset>]` | 프로젝트의 범위를 부여합니다. 작성자에게 먼저 유효한 세션 키가 있어야 합니다 |
| `revoke` | `revoke <author> [<project> [<scope>]]` | 유효한 부여를 회수합니다 |
| `grants` | `grants [<author>]` | 유효한 부여를 나열합니다 |
| `attachments-check` | `attachments-check [--purge-orphans]` | 첨부를 그 파일 및 키와 맞춰 봅니다. 어느 행도 가리키지 않는 파일과 `attachments-rekey`가 대체한 파일은 지우지 않고 `orphans/`로 옮기며, `orphans/`에 있던 파일의 행이 다시 생겼으면 되돌립니다. 보고서는 건수와, 없어졌거나 손상되었거나 어느 이력 행도 가리키지 않는 첨부의 ID(저장 키나 경로는 출력하지 않습니다)를 보여 주고, 없어졌거나 손상된 첨부가 있으면 종료 코드는 `1`입니다. `--purge-orphans`는 먼저 `orphans/`를 비우고 그 수를 감사 기록에 남깁니다 |
| `attachments-rekey` | `attachments-rekey --from=<keyId>` | 키 `<keyId>`로 쓴 첨부 파일을 활성 키 `ledger.attachments.active-key-id`(다른 키여야 합니다)로 다시 씁니다. 옛 파일은 `ledger.attachments.orphan-grace`가 지난 뒤 `attachments-check`가 `orphans/`로 옮깁니다 |
| `attachments-key-create` | `attachments-key-create` | 키 디렉터리에 새 256비트 키 파일을 만들고(서비스 계정만 읽을 수 있습니다. Windows에서는 설치한 사용자와, 읽기만 하는 LocalService) 키 ID를 출력합니다. 키 자체는 출력하지 않으며, 새 업로드를 그 키로 쓰려면 `ledger.attachments.active-key-id`를 그 값으로 설정합니다 |
| `mcp-check` | `mcp-check [--url=<url>] [--ledger.mcp.token=<token>]` | 실행 중인 MCP 서버에 연결해 도구를 확인합니다 |
| `render-deploy` | `render-deploy --java=<path> --jdbc-url=<url> --app-account=<account> [--name=<name>] [--platform=<platform>] [--home=<path>] [--schema=<name>] [--mcp-port=<n>] [--admin-port=<n>] [--mcp-host=<host>] [--admin-host=<host>] [--root=<directory>] [--check]` | 서비스 정의(systemd 유닛, Windows에서는 WinSW 설정)와 설정을 씁니다. 설치기가 호출합니다 |
| `prepare-host` | `prepare-host --jars=<dir> --driver-jar=<file> [--winsw=<file>] --java=<path> --jdbc-url=<url> --app-account=<account> [--name=<name>] [--platform=<platform>] [--home=<path>] [--schema=<name>] [--mcp-port=<n>] [--admin-port=<n>] [--mcp-host=<host>] [--admin-host=<host>] [--root=<directory>]` | 호스트의 파일을 준비합니다(root로, Windows에서는 관리자 권한으로 실행). 폴더를 소유자·모드 또는 ACL(Windows에서는 LocalService의 접근과 설치한 사용자의 비밀 폴더)과 함께 만들고, jar와 드라이버(Windows에서는 WinSW도)를 복사하고, `render-deploy`가 쓰는 것을 씁니다. 설치기가 호출합니다 |
| `install-default` | `install-default <name>` | 시드 번들에서 설치 기본값 하나(`instance-name`, `mcp-port`, `admin-port`)를 출력합니다. 설치기가 기본값을 이것으로 받습니다 |

## 관리 웹

관리 웹(`ledger-admin-web.jar`)은 소유자를 위한 서버 렌더링 콘솔이며 기본 주소는 `127.0.0.1:8865`입니다. 설치기가 만든 서비스 계정으로 실행하며 root로는 실행하지 않습니다.

- **로그인, 셋 중 하나**(`ledger.admin.sign-in`). `passkey`(새 설치의 기본값)이면 소유자는 패스키로 로그인합니다. `token`(개인판: 본인 컴퓨터나 서버의 한 사람)이면 애플리케이션의 로그인 페이지에 관리 토큰 `ledger.admin.token`을 입력합니다. `oidc`이면 OpenID Connect로 로그인하며 소유자는
  **이메일 주소 하나**입니다(`ledger.admin.oidc.owner-email`, `ledger.admin.oidc.issuer`의 공급자가 확인한 주소). 다른 주소나 공급자가 확인하지 않은 주소는 거절합니다.
  셋 중 하나만 제공하며, `oidc`나 `passkey`이면 토큰 입력란이 없고 보낸 토큰은 로그인으로 이어지지 않습니다. 관리 토큰 `ledger.admin.token`은 읽기 전용 JSON 엔드포인트의 bearer로도 남습니다. 어떤 로그인이든 변경은 패스키가 승인하므로, 토큰으로 연 세션도 `passkey-code`의 일회용 코드로 첫 패스키를 등록합니다. 에이전트의 토큰은 거절되므로
  MCP 토큰을 가진 에이전트가 스스로 부여를 줄 수 없습니다. 자격 증명 확인(패스키, 일회용 코드, 토큰)은 기간 안에 한도보다 많이 들어오면 늦춰집니다(`ledger.admin.lockout.*`).
  아무것도 잠그지 않습니다. 틀린 자격 증명으로 누구나 걸 수 있는 잠금은 소유자도 잠그기 때문입니다. 클라이언트별 제한도 없습니다. 공개는 같은 호스트의
  운영자 역방향 프록시를 거치고, 그 프록시가 넘기는 주소는 요청의 기록일 뿐 신원이 아닙니다(프록시에 따라 모든 사용자가 한 주소로 들어옵니다). 그래서 주소별 잠금은 공격자와 함께 소유자를 잠그며,
  한 사람뿐인 개인판에는 구분할 상대가 없습니다. `token-generate`가 만든 토큰(`SecureRandom` 256비트)과 일회용 코드(100비트)는 추측할 수 없으므로 지연이면 충분합니다.
- **어떻게 로그인했든 모든 변경은 패스키로 승인합니다.** 콘솔에서 하는 모든 변경(부여, 세션 키, 공통코드, 프로젝트, 드라이버, 패스키 닫기)은 사용자 확인을 요구하는 패스키로
  승인합니다. 화면이 서버에 바로 그 변경과 세션으로 만든 challenge를 요청하고, 인증기가 그것에 서명하며, 쓰기는 그 서명과 함께 갑니다. 서명은 그 변경 하나에, 한 번, 2분 동안만
  유효하며 다른 변경이나 다른 세션의 서명은 거절합니다. 읽기에는 승인이 필요 없습니다. 서버의 `ledger-cli` 명령은 소유자의 셸이므로 승인이 필요 없습니다.
- **첫 패스키, 추가 패스키, 초기화.** 서버에서 서비스 계정으로 `ledger-cli passkey-code`를 실행하면 일회용 코드를 출력합니다(한 번만 보여 주고, 15분간 유효하며, 해시로만 저장합니다).
  로그인 화면의 "패스키 등록"에 그 코드를 입력하면 등록 전용 세션(15분, 패스키 등록 외에는 아무것도 할 수 없음)이 열리고, 거기서 "등록"으로 패스키를 만듭니다.
  `passkey-code --remove-existing`은 패스키를 모두 잃은 소유자를 위해 유효한 패스키를 먼저 모두 닫으며, 플래그가 없으면 패스키를 건드리지 않습니다. 코드 발급과 초기화는 모두
  소유자 감사 기록에 남습니다. 패스키는 관리 웹의 호스트 이름(`ledger.admin.passkey.rp-id`)에 묶이고, `localhost`를 빼면 HTTPS가 필요하며, 호스트나 origin이 다르면
  `PASSKEY_HOST_MISMATCH`로 거절합니다.
- **구성.** 인증 절차는 Spring Security의 WebAuthn 지원(`spring-security-webauthn`과 WebAuthn4J, 둘 다 Apache-2.0)이 하고, 패스키는 장부 데이터베이스에 저장합니다(V7: base64url 텍스트,
  BLOB 없음, 자격 증명은 바꾸거나 삭제하지 않고 닫는 것도 별도의 행입니다).
- **브라우저 보안.** Spring Security가 CSRF 토큰, 기본으로 `Secure`인 `SameSite=Strict` 세션 쿠키, `Host` 허용 목록, 콘텐츠 보안 정책을 제공합니다.
- **화면.** 개요(주의 항목 건수와 MCP 서버 상태), 프로젝트, 결함, 작업, 조건, 이력(읽기 전용), 공통코드, 세션 키가 있는 부여, JDBC 드라이버 화면입니다.
  시각은 UTC로 보내며 브라우저의 지역 시각으로 표시합니다.
- **부여.** 소유자가 세션이 출력한 공개 키를 붙여 넣고, 화면에 보이는 지문을 세션이 보여 준 지문과 대조한 뒤에야 키가 등록되고 부여가 발급됩니다.
  유효한 키는 바로 그 키를 명시적으로 확인해야 교체됩니다.

## 클라이언트 훅

실행 jar 하나 `ledger-client-hook.jar`가 모든 클라이언트를 처리하며, 첫 인자로 클라이언트를 고릅니다:
`java -jar ledger-client-hook.jar claude`, `codex`, `antigravity`. 플러그인 디렉터리는 `clients/claude`, `clients/codex`, `clients/antigravity`에 있습니다(각 README 참조).
훅에는 클라이언트 호스트의 `PATH`에 Java 25 런타임이 필요하며, 훅이 없거나 실패하면 쓰기는 `HOST_ATTESTATION_REQUIRED`로 거절됩니다.

훅은 쓰기 도구 10종에만 서명하며, 도구 이름은 `mcp__<server>__ledger_<op>` 또는 `mcp__<server>__<server>_ledger_<op>`입니다
(Codex Apps는 `mcp__codex_apps__app_<app>_ledger_<op>`). `_hostAttestation`의 값은
`pgla2.<author>.<tool>.<operation key>.<arguments digest>.<issued at>.<signature>`이며, 작성자, 도구, 호출의 `operationKey`(`ledger_whoami`와 첨부 도구는 빈 값), 인자의 RFC 8785 정규형(`_hostAttestation` 제외, 배치는 모든 연산과 그 내용, 순서, 배치의 키)의
SHA-256, 시각에 대한 Ed25519 서명입니다. 훅은 모델이 보낸 인자에서 `operationKey`를 읽을 뿐 키를 만들어 넣지 않으며, 키가 없는 호출은 서버가 거절합니다. 가로챈 토큰으로 다른 내용을 보낼 수 없습니다.

- 훅은 MCP 서버 이름과 작성자마다 Ed25519 키 하나를 소유자 전용 폴더에 보관합니다. 기본은 `~/.pg-ledger-mcp/session-keys`이며, `LEDGER_HOOK_KEY_DIR`에 절대 경로를 주어 바꿀 수 있습니다.
  한 서버용 서명은 다른 서버에서 검증되지 않습니다. Codex Apps의 서버 이름은 `codex_apps.<app>`입니다.
- `java -jar ledger-client-hook.jar session-key <server> <author>`는 서버, 작성자, 공개 키, 지문을 출력합니다.
  소유자는 지문을 대조한 뒤 그 서버에서 `ledger-cli key-register` 또는 부여 화면으로 키를 등록합니다.
- 서버는 부여를 보기 전에 서명, 도구, 작업 키, 인자, 시각(`ledger.attestation.window`)을 검증합니다. 키가 없는 경우와 서명이 틀린 경우는 같은 거절인 `ATTESTATION_SIGNATURE_INVALID`이고,
  내용이 다르면 `ATTESTATION_BINDING_MISMATCH`입니다.
- 한계: 같은 운영체제 사용자의 다른 프로세스는 키 파일을 읽을 수 있으므로 한 사용자 안의 격리에는 별도 계정이 필요합니다. 훅은 폴더와 키 파일을 소유자 전용으로 만들고, 이미 있는 것은 누가 접근할 수 있든 누가 소유하든 그대로 읽습니다(링크는 따라갑니다). 개인판은 PC 전체를 신뢰합니다. 키 파일 자리의 디렉터리와 빈 키 파일은 거절합니다.

## 1.x에서 업그레이드

1.x는 PostgreSQL 함수 위에서 동작하던 DBHub와 Node 구현이었고, 2.0.0은 그 전부를 대체합니다. 기존 데이터는 제자리에서 변환하지 않고 옮깁니다.
1.x 스키마는 그대로 남으므로 되돌릴 때 쓸 수 있습니다. 계약 변경은 [릴리스 노트](docs/release-notes/2.0.0.md)를 참조합니다.

1.x 호출자는 도구 호출을 다음과 같이 바꾸어야 하며, 2.0은 옛 형식을 새 형식과 함께 받아들이지 않습니다.

- `request_id`와 `batch_request_id`는 모든 쓰기 도구의 필수 인자 `operationKey`(1자 이상 128자 이하)로 바뀝니다. 옛 키는 알 수 없는 인자로 거절되고, `operationKey`가 없는 호출도 거절되며,
  서버는 키를 대신 만들지 않습니다. `ledger_batch` 안의 연산에는 자체 키가 없습니다.
- `author_id` 인자는 없어졌습니다. 작성자는 호스트 증명입니다.
- 객체나 배열 인자(`fields`, `ops`)는 문자열에 담은 JSON 문서가 아니라 JSON 객체나 배열로 보내야 합니다.
- 텍스트 `null`은 인자가 없다는 뜻이 아니라 하나의 텍스트입니다. 값이 없는 인자는 생략합니다.

2.0 자체는 1.x를 전혀 알지 못합니다. 서비스, 설치 프로그램, `ledger-cli`는 1.x의 설정, 파일, 표를 읽지 않습니다. 이전은 별도의 도구인
`ledger-migrate-1x` 모듈이 맡습니다. 빌드가 `ledger-migrate-1x/build/libs/ledger-migrate-1x.jar`로 만들며, 배포 폴더 `dist`에 다른 jar와 함께 둡니다(설치의 `cp` 단계).
설치 프로그램은 이 도구를 알지 못하고 복사하지도 않습니다. 도구는 서비스에 속하지 않기 때문입니다. 도구는 설치에 쓴 Java 25 런타임으로
`java -jar dist/ledger-migrate-1x.jar <command> [options]`처럼 실행합니다. 비밀 저장소를 쓰는 명령은 서비스 사용자로 실행하므로, `dist` 폴더는 서비스 사용자가 읽을 수 있는 곳(`/home` 아래가 아닌 곳)에 둡니다.
도구는 서비스와 같은 방식으로 설치의 설정을 읽습니다(설정 디렉터리 `ledger.secrets.home`의 `application.yaml`, `local.yaml`, 드라이버 등록, 비밀 디렉터리).
이름이 다른 인스턴스에는 `--ledger.secrets.instance=<name>`을 줍니다. 장부 데이터베이스가 필요한 명령에는 등록된 JDBC 드라이버가 필요하며,
`stop-1x`와 `import-secrets-1x`에는 필요하지 않습니다.

| 이전 명령 | 문법 | 하는 일 |
|---|---|---|
| `stop-1x` | `stop-1x [--name=<instance>] [--platform=<platform>] [--unit-dir=<dir>] [--home=<dir>] [--env=<file> [--mcp-host=<host>] [--admin-host=<host>]]` | 첫 단계입니다. 1.x의 서비스 둘 `<name>`과 `<name>-admin`을 멈추고 비활성화하며, 각 정의를 옆에 백업(`<file>.1x-backup`)으로 보관합니다. `--env`를 주면 먼저 1.x의 공개 호스트 이름을 검사합니다 |
| `import-secrets-1x` | `import-secrets-1x --env=<file> [--replace]` 또는 `import-secrets-1x --stdin [--replace]` | 설치 프로그램을 실행한 뒤 1.x의 비밀을 2.0의 비밀 저장소로 옮깁니다. `--replace`를 주면 설치 프로그램이 생성한 토큰을 대체합니다 |
| `import-1x` | `import-1x [--verify-only] [--owner-account=<account>] [--stdin]` | 1.x 장부를 하나의 트랜잭션으로 새 스키마에 복사하고 모든 표를 행 수와 SHA-256으로 대조합니다. `--verify-only`는 대조만 다시 합니다 |
| `retire-1x` | `retire-1x [--confirm=<sha256> --backup-tested]` | 2.0을 받아들인 뒤, 1.x 설치기가 데이터베이스에 만든 것을 제거합니다. 옵션 없이 실행하면 목록만 보여 주고 아무것도 바꾸지 않습니다 |

업그레이드의 순서는 백업, `stop-1x`, 데이터베이스 계정 준비, 설치 프로그램으로 2.0 설치, `import-secrets-1x`, 서비스 재시작, `import-1x`, 확인이며, 2.0을 받아들인 뒤에야 `retire-1x`를 실행합니다.
1.x 서비스는 멈출 때까지 1.x 데이터베이스를 쓰며, 2.0 설치 프로그램은 1.x의 어떤 것도 멈추지 않습니다. 이 순서대로 진행합니다.

1. **백업.** DBMS의 덤프 도구로 장부 데이터베이스를 덤프하고, 1.x 설정 디렉터리 `/etc/pg-ledger-mcp`(파일 `/etc/pg-ledger-mcp/env`에 1.x의
   토큰과 비밀번호가 있습니다)와 `/etc/systemd/system`의 1.x 유닛 파일을 복사해 둡니다.
2. **`stop-1x`로 1.x 서비스를 중지합니다.** 1.x 호스트에서 root로 가장 먼저 실행합니다. 1.x 환경 파일과 호스트 옵션(4단계에서 2.0 설치 프로그램에 줄 것과 같은 옵션)을 주면,
   아무것도 멈추기 전에 1.x의 공개 호스트 이름을 검사합니다.

   ```sh
   sudo java -jar dist/ledger-migrate-1x.jar stop-1x --env=/etc/pg-ledger-mcp/env --mcp-host=<mcp-host> --admin-host=<admin-host>
   ```

   - **바꾸는 것.** 1.x의 서비스 둘, `<name>`(MCP 서버)과 `<name>-admin`(관리 웹이며 1.0.0에는 없었습니다)을 멈추고 비활성화합니다. `<name>`은 `--name=<instance>`이며 기본값은
     `pg-ledger-mcp`입니다. 각 정의는 옆에 백업으로 보관합니다. 유닛 파일 `<unit-dir>/<service>.service`를
     `<service>.service.1x-backup`으로 옮기며, 디렉터리는 `--unit-dir=<dir>`이고 기본값은 `/etc/systemd/system`입니다. 1.x에는 Linux(systemd) 설치만 있었으므로
     Windows에서 멈출 것은 없습니다. `--platform=windows`는 `NO_1X_SERVICE_WINDOWS`로 거절하며, 그곳의 같은 이름 서비스는 2.0 또는 시스템의 것이므로 도구는 절대 건드리지 않습니다.
     1.x 데이터베이스와 1.x 환경 파일은 건드리지 않습니다.
   - **읽는 것.** 두 서비스의 정의와, `--env=<file>`을 주면 1.x 환경 파일이 허용하는 호스트 이름입니다.
   - **프로세스를 시작하는 이유.** Java에는 호스트의 서비스 관리자를 다루는 API가 없으므로, 명령은 고정된 인자(`stop`, `disable`, `daemon-reload`)와
     검증한 이름으로 `systemctl`을 코드의 한 곳에서 실행합니다. 이름이 `<name>` 또는 `<name>-admin`이고 정의가 1.x의 것인 서비스만 건드립니다.
     1.x의 유닛에는 `/etc/<name>/env`를 가리키는 `EnvironmentFile=` 줄이 있습니다.
     첫 변경 전에 모든 서비스를 확인하며, 그렇지 않으면 `NOT_1X_SERVICE`로 멈추고 아무것도 바꾸지 않은 상태로 남습니다.
   - **1.x의 공개 호스트 이름.** 1.x의 클라이언트는 1.x 설정 `DBHUB_ALLOWED_HOSTS`(1.1.0의 관리 웹은 `PGLEDGER_ADMIN_ALLOWED_HOSTS`)가 허용하는
     호스트 이름, 예를 들어 리버스 프록시의 이름으로 1.x 서버에 접속합니다. 2.0의 `Host` 검사는 `127.0.0.1`, `localhost`와 `--mcp-host`의 이름 하나, `--admin-host`의 이름 하나만
     허용하므로(설치 프로그램은 서비스마다 호스트 이름 하나를 받습니다), 클라이언트가 아직 쓰는 이름이 빠지면 이전 직후 그 요청이 HTTP 403으로 실패합니다. 그래서 `--env=<file>`을 주면 명령은 아무것도 멈추기 전에, 파일이 허용하는
     공개 호스트 이름 가운데 옵션이 담지 않은 이름을 2.0이 더는 받지 않는 이름으로 알립니다. 호스트 이름과 옵션을 알리며, 이를 이유로 거절하지는 않고 계속합니다.
     프록시가 넘기는 이름 하나를 옵션에 줍니다(`--mcp-host=<name>`, 설치 프로그램과 같은 형식의 호스트 이름 하나: 소문자, 포트 없음, 목록 없음. `--mcp-host`는 `--admin-host` 없이 줄 수 있습니다).
     같은 옵션을 설치 프로그램에도 줍니다. 루프백 이름은 옵션이 필요 없고, 파일의 그 밖의 내용은 출력하지 않습니다.
     `--env` 없이 두 호스트 옵션만 주면 거절합니다.
   - **다시 실행.** 중간에 멈췄다면 다시 실행합니다. 이미 멈춘 서비스는 그렇다고 알리고, 정의와 다른 내용의 백업 파일은 절대 대체하지 않으며(`BACKUP_DIFFERS`), 서비스 관리자 호출이 실패하면
     (`SERVICE_COMMAND_FAILED`) 이미 한 일은 그대로 두고 다음 실행이 이어서 처리합니다. 이 시점부터 1.x의 클라이언트는 2.0 서버가 실행될 때까지 거절됩니다. 1.x를 새 설치와 나란히 두려면
     `stop-1x`를 실행하지 말고 새 설치에 자체 `--name=<name>`과 포트를 줍니다.
3. **데이터베이스 계정 두 개 생성.** 새 설치의 앱 계정과 DDL 계정을 DBMS 도구로 만듭니다(설치 참조). 1.x의 계정은 그대로 둡니다. 새 스키마를 1.x 장부와 같은
   데이터베이스에 둘 때는 `ledger.schema.name`을 1.x 스키마 `ledger`가 아닌 스키마로 지정합니다.
4. **2.0 설치.** 호스트에 맞는 설치 프로그램으로 설치합니다(설치 참조). 설치 프로그램은 1.x를 알지 못하며, `stop-1x`에 준 호스트 이름을 그대로 줍니다.

   ```sh
   sudo deploy/install.sh --jars=dist --driver-jar=<driver-jar> --driver-class=<driver-class> --owner-account=<ddl-account> --app-account=<app-account> --jdbc-url=<jdbc-url> --mcp-host=<mcp-host> --admin-host=<admin-host>
   ```

   설치 프로그램은 자체 토큰을 생성하고(각각 한 번 보여 줍니다) 그 토큰으로 두 서비스를 시작합니다.
5. **`import-secrets-1x`로 1.x의 비밀을 옮깁니다.** 비밀 저장소는 설치 프로그램이 만드는 서비스 계정의 것이므로 설치 프로그램을 실행한 뒤에 실행합니다.
   1.x 파일은 root가 소유하고 비밀 저장소는 서비스 사용자가 소유하므로, root가 파일을 열어 서비스 사용자로 실행하는 도구에 넘겨 줍니다.

   ```sh
   sudo sh -c 'runuser -u pg-ledger-mcp -- java -jar dist/ledger-migrate-1x.jar import-secrets-1x --replace --stdin < /etc/pg-ledger-mcp/env'
   ```

   - **읽는 것.** 1.x 환경 파일이며, 위처럼 `--stdin`으로 주거나 서비스 사용자로 파일을 읽을 수 있는 운영자는 `--env=<file>`로 줍니다. 둘 중 정확히 하나를 줍니다.
     에이전트 토큰만 읽고, 무엇이든 쓰기 전에 그 값을 확인합니다(16자 이상). 데이터베이스는 쓰지 않습니다.
   - **바꾸는 것.** 설치 프로그램이 이미 자체 토큰을 생성했으므로, `--replace`가 없으면 이미 있는 비밀은 그대로 둡니다. `--replace`를 주면 에이전트용 두 비밀이 1.x의 값을 갖습니다.
     1.x의 `DBHUB_AUTH_TOKEN`은 `ledger.mcp.token`과 `ledger.admin.agent-token`이 됩니다.
     1.x의 클라이언트가 그대로 동작합니다. 비밀의 이름만 출력하고 값은 출력하지 않습니다.
   - **관리 토큰은 가져오지 않습니다.** 1.x의 `PGLEDGER_ADMIN_TOKEN`은 옮기지 않으며(1.x 환경에 있으면 명령이 그 사실을 알립니다), 2.0의 관리 토큰은 설치 프로그램이 `token-generate`로 만든
     `ledger.admin.token`입니다(`SecureRandom` 256비트, 한 번만 표시). 사람이 정한 토큰은 더 약할 수 있고, 2.0의 자격 증명 확인 제한은 추측할 수 없는 토큰을 전제로 한 지연이기 때문입니다.
     이 토큰은 읽기 전용 엔드포인트의 bearer로 남습니다(브라우저는 `ledger.admin.sign-in=token`일 때만 로그인시킵니다).
   - 이름이 다른 인스턴스에서는 서비스 사용자가 그 이름이며(`runuser -u <name>`), 도구에는 `--ledger.secrets.instance=<name>`을 줍니다.

   서비스는 시작할 때 비밀을 읽으므로 2.0의 두 서비스를 재시작합니다.

   ```sh
   sudo systemctl restart pg-ledger-mcp pg-ledger-mcp-admin
   ```

6. **`import-1x`로 장부를 가져옵니다.** 서비스 사용자로 1.x 데이터베이스의 읽기 전용 계정 비밀번호를 저장한 다음, DDL 계정으로 가져오기를 실행합니다.
   1.x 원본의 설정은 아래 표에 있습니다.

   ```sh
   sudo runuser -u pg-ledger-mcp -- java -jar dist/ledger-cli.jar secret-set ledger.import.source.password
   sudo runuser -u pg-ledger-mcp -- java -jar dist/ledger-migrate-1x.jar import-1x --owner-account=<ddl-account> --ledger.import.source.url=<jdbc-url> --ledger.import.source.username=<account>
   ```

   가져오기는 장부 전체를 쓰는데 두 앱 계정은 이를 할 수 없으므로, `--owner-account=<account>`가 DDL 계정으로 실행합니다. 그 비밀번호는 숨김 입력으로 받으며 저장하지 않습니다
   (`--stdin`은 대신 표준 입력의 첫 줄에서 받으며 `--owner-account`가 필요합니다). 1.x 스키마는 읽기 전용으로 읽고 새 스키마는 하나의 트랜잭션으로 씁니다. 아래 가져오기가 하는 일을 참조합니다.
7. **세션 키를 등록합니다.** 1.x의 부여는 세션 키 없이 복사됩니다. 모든 작성자는 `ledger-cli key-register`로 세션 키를 등록해야 하며, 그 뒤에 부여가 동작합니다.
8. **확인.** `ledger-cli mcp-check --url=<its public URL>`와 `import-1x --verify-only`(1.x 원본의 설정이 필요하며 아무것도 바꾸지 않습니다)를 실행하고, `ledger-cli passkey-code`를 실행해 관리 웹에서 첫 패스키를 등록합니다(설치 프로그램이 만든 관리 토큰은
   `ledger.admin.sign-in=token`일 때만 브라우저를 로그인시키며, 기본값은 `passkey`입니다). 클라이언트: `execute_sql_reader`와 `search_objects_reader` 도구는 제거되었으며 읽기 도구가 대체합니다. 쓰기 도구의 결과는 `rows[0].result` 감싸기 없이 키를 그대로 유지합니다.
   서버를 전환하기 전에 모든 클라이언트 호스트에 훅 jar(Java 25 런타임)를 설치합니다. 확인을 마치면, 가져오기를 위해 1.x 데이터베이스에 만든 읽기 전용 계정은 삭제해도 됩니다.
9. **되돌리기.** 확인이 실패하면 2.0 서비스를 멈추고, 각 `<unit>.1x-backup`을 2.0 유닛 자리에 원래 이름으로 되돌립니다(백업이 남도록 복사합니다). `sudo systemctl daemon-reload`를 실행한 뒤
   `sudo systemctl enable --now <name>`과 `<name>-admin`에 대한 같은 명령으로 1.x 서비스를 이전 설정으로 시작합니다. `stop-1x`가 이 절차를 출력합니다. 1.x 데이터베이스는 읽기만 했으므로 1.x는 그대로 이어집니다.
10. **2.0을 받아들인 뒤 `retire-1x`로 1.x 데이터베이스 객체를 제거합니다.** 그때까지는 보관한 유닛(`<unit>.1x-backup`)과 1.x 데이터베이스가 되돌아갈 길이며, 어느 것도 그 전에 삭제하지 않습니다.
    1.x 설치기는 데이터베이스에 함수, 트리거, 뷰, 표, 스키마 하나와 데이터베이스 역할 네 개를 만들었고, `import-1x`는 그것을 읽기만 합니다. 1.x 데이터베이스를 `pg_dump`로 백업하고
    그 1.x 백업의 복원을 시험합니다. 다음 단계를 마치면 그 백업이 되돌아갈 유일한 길입니다. 1.x 서비스를 멈추고(`stop-1x`), 1.x 객체를 소유한 계정(1.x 설치기의 소유자 역할 또는 그 권한을 가진 역할)의
    비밀번호를 `secret-set ledger.import.source.password`로 저장하고 `ledger.import.source.username`을 그 계정으로 지정한 뒤 옵션 없이 `retire-1x`를 실행합니다.

    ```sh
    sudo runuser -u pg-ledger-mcp -- java -jar dist/ledger-migrate-1x.jar retire-1x --ledger.import.source.username=<owner-account>
    ```

    이 명령은 `import-1x --verify-only`가 일치하는지, 다른 세션이 1.x 역할로 실행 중이거나 1.x 스키마에 잠금을 잡고 있지 않은지, 계정이 객체를 소유하는지 확인하고,
    모든 객체를 제거 순서대로 보여 주며 그 목록의 SHA-256을 출력할 뿐 아무것도 바꾸지 않습니다. 목록을 읽은 뒤 `retire-1x --confirm=<sha256> --backup-tested`를 실행합니다.

    ```sh
    sudo runuser -u pg-ledger-mcp -- java -jar dist/ledger-migrate-1x.jar retire-1x --ledger.import.source.username=<owner-account> --confirm=<sha256> --backup-tested
    ```

    명령은 백업을 확인할 수 없으므로 `--backup-tested`는 1.x 백업을 만들었고 그 복원을 시험했다는 사용자의 확인입니다. 명령은 트리거, 뷰, 표, 함수, 시퀀스, 스키마를 하나의 트랜잭션으로
    제거한 다음 `ledger.import.source.roles`의 역할을 제거합니다. 실행 중인 계정이나 2.0 설정의 계정이거나, 다른 구성원이 있거나, 다른 곳의 객체를 소유한 역할은 남기고
    `SKIPPED`로 표시합니다. 장부는 이 작업을 스스로 실행하지 않습니다.

### 가져오기가 하는 일

- 1.x 스키마를 읽기 전용으로 읽어 모든 행을 한 트랜잭션으로 복사하고, 하나라도 실패하면 되돌립니다. 이어서 모든 표의 행 수와 SHA-256 다이제스트를 대조합니다
  (`--verify-only`는 대조만 다시 합니다). 가져오기는 `migrate`와 `setup`이 만든 스키마, 곧 설치 프로그램을 마친 상태에서 한 번 실행합니다(설치 프로그램이 `setup`을 실행하며, 그 시드 코드와 감사 행은 받아들이고,
  1.x에도 있는 시드 코드는 1.x의 값을 따릅니다). 원장 데이터 표, 세션 키, 이전 가져오기 중 하나라도 이미 있으면 거부하며, 옛 데이터 중 새 스키마가 담을 수 없는 값은 모두 나열하고 값을 지어내지 않습니다.
- 1.x의 ID는 그대로 유지합니다. 작업이나 조건의 ID는 공백과 제어 문자가 없는 1자 이상 120자 이하이면 대소문자와 문자 종류(`:`, `/`, `#`, `.`, `_`, `-` ...)를 가리지 않으며,
  결함 ID는 기존 형식(`<CODE>-D-0001`)을 그대로 씁니다. 같은 종류에서 대소문자만 다른 ID는 새 스키마에서 하나의 ID이므로(조회는 ID를 정확히 비교하고, ID를 대문자로 바꾼 키는 어떤 DBMS에서도 유일합니다)
  가져오기는 그런 쌍을 거부하며 나열합니다.
- 1.x의 빈 문자열(`purpose`, `success_condition`, `behavior`, `root_cause`, `condition_text`는 `''`를 허용했습니다)은 NULL로 저장하고 도구는 1.x처럼 빈 문자열을 돌려줍니다.
  조건의 `code`(200자)와 `ci_ref`(2000자)는 이전 2.0 빌드보다 길어졌고, 작업의 `origin_ref`는 긴 텍스트(쓸 때 20000자까지)여서 1.x의 모든 값이 들어갑니다.
- 1.x는 모든 관계를 작은 ID가 앞에 오도록 저장했고 호출자가 어느 결함을 먼저 적었는지 어디에도 남기지 않았습니다. 그래서 가져오기는 방향이 있는 종류(`caused_by`, `blocks`)의
  관계를 저장된 그대로 복사하고 짐작하지 않으며, 그 관계를 나열합니다(건수와 ID, 감사 행과 `--verify-only`에도 나옵니다). 하나씩 확인하여 방향이 거꾸로인 관계는 제거한 뒤
  `ledger_link`로 올바른 결함을 앞에 두고 다시 연결합니다.
- 1.x의 부여는 세션 키 없이 복사합니다(7단계). 1.x의 요청 영수증은 가져오지 않습니다. 2.0의 모든 쓰기는 `operationKey`를 요구하고 옛 `request_id`는 알 수 없는 인자로 거절하므로, 1.x에서 응답이 유실된 요청이 2.0에서 두 번째 쓰기를 일으킬 수 없습니다.
  1.x 이력 행의 `request_id`는 `operation_key`로 보존됩니다.
  1.x에서 온 이력 행은 중립적인 이름 `IMPORT`, `IMPORTED_NOTE`, `IMPORTED_CHANGE`, `IMPORTED_LOGIN`, `IMPORTED_SHARED`, `IMPORTED_UNKNOWN`을 쓰며, 1.x의 `legacy_ref` 필드는 읽기 도구에서 `source_ref`입니다.

### 1.x 원본의 설정

`ledger.import.source.*` 행은 `import-1x`와 `retire-1x` 명령에 속하며 2.0의 다른 부분에는 속하지 않습니다. 설정 디렉터리의 `local.yaml`에 두거나 명령줄에 `--name=value`로 줍니다.
비밀번호는 비밀이므로 파일에 두지 않습니다.

| 키 | 사용처 | 의미 |
|---|---|---|
| `ledger.import.source.url` | `import-1x`, `retire-1x` | 1.x 장부가 있는 데이터베이스의 JDBC URL |
| `ledger.import.source.username` | `import-1x`, `retire-1x` | 그 데이터베이스의 읽기 전용 계정(`retire-1x`는 1.x 객체를 소유한 계정이 필요합니다) |
| `ledger.import.source.password` | `import-1x`, `retire-1x` | 그 비밀번호(비밀) |
| `ledger.import.source.schema` | `import-1x`, `retire-1x` | 1.x 스키마, 기본 `ledger` |
| `ledger.import.source.roles` | `retire-1x` | `retire-1x`가 스키마 다음에 제거하는 1.x 설치기의 데이터베이스 역할, 기본 `ledger_owner`, `ledger_author`, `ledger_reviewer`, `ledger_reader` |

**업그레이드 안내: 관리 웹 로그인.** 관리 웹에 OpenID Connect로 로그인했다면 그렇게 하겠다고 밝히십시오. `ledger.admin.sign-in=oidc`를 설정하고(`ledger.admin.oidc.*` 설정이 있는데
`ledger.admin.sign-in`이 없는 설치는 그 키를 알리는 메시지와 함께 시작할 때 거절됩니다), 소유자의 이메일 주소 하나인 `ledger.admin.oidc.owner-email`과 그 주소가 유효한
발급자 `ledger.admin.oidc.issuer`를 주십시오. 어떤 방식으로 로그인하든
`passkey-code`로 패스키를 등록하십시오. 모든 변경을 그 패스키가 승인합니다.

## 빌드와 시험

JDK 25와 Gradle 래퍼만 있으면 되며, 래퍼가 Gradle을 직접 내려받습니다.

```sh
./gradlew build
```

빌드는 경고를 오류로 보고 컴파일하며, 모든 모듈의 시험을 그 컴퓨터에 설치된 PostgreSQL에서 실행하고(아래 시험 데이터베이스 참조, 내장 데이터베이스는 없으며 빌드가 데이터베이스를
띄우지도 않습니다), JDBC 드라이버, JPA, 마이그레이션 도구, Lombok이
런타임 클래스패스에 없는지 확인합니다. `ledger-cli`의 문서 시험(DOC-001부터 DOC-029까지, [docs/testing/TEST_CASES.md](docs/testing/TEST_CASES.md))은 이 README, 영문 README,
릴리스 노트를 코드와 대조합니다.

`ledger-system-test`의 시스템 시험(SYS-001부터 SYS-012까지)에도 같은 로컬 PostgreSQL과 빌드된 jar가 필요합니다. 이 시험은 설치 명령, `ledger-cli.jar`,
`ledger-mcp-server.jar`, 훅 jar를 각각 별도의 Java 프로세스로 시험 데이터베이스에 대해 실행하므로, 빌드가 시험 전에 jar를 먼저 만듭니다.
`./gradlew build`에 포함되며, `./gradlew :ledger-system-test:test`로 단독 실행할 수 있습니다.

### 시험 데이터베이스

모듈 시험에는 이미 설치되어 실행 중인 PostgreSQL 18 이상이 필요합니다. 기본값으로 `127.0.0.1:55432`의 `ledger_test` 데이터베이스에 그 데이터베이스의 소유자 계정으로 접속하며,
두 번째 로그인인 최소 권한 응용 계정은 계정 권한을 다루는 시험이 사용합니다. 각 시험은 자기만의 스키마를 만들어 쓰고 끝나면 삭제하므로 그 데이터베이스 밖에는 아무것도 남지 않으며,
시험 JVM 하나는 연결을 최대 10개까지만 유지합니다. 데이터베이스에 접속할 수 없는 시험은 접속 오류로 실패하며, 건너뛰지도 않고 다른 데이터베이스로 대체하지도 않습니다.

접속 정보는 명령줄 또는 `gradle.properties`의 Gradle 속성으로 지정합니다. 아래 값이 기본값이며, 두 비밀번호 파일 속성만은 기본값이 루트 `build.gradle.kts`에 적힌 시험 컴퓨터의 파일입니다.

```sh
./gradlew test \
  -Pledger.test.jdbc-url=jdbc:postgresql://127.0.0.1:55432/ledger_test \
  -Pledger.test.jdbc-user=ledger_test \
  -Pledger.test.app-user=ledger_test_app \
  -Pledger.test.jdbc-password-file=<owner-password-file> \
  -Pledger.test.app-password-file=<app-password-file>
```

비밀번호는 속성 값으로 주지 않습니다. 두 비밀번호 파일 속성은 첫 줄이 비밀번호인 파일의 경로이며 시험 JVM만 그 파일을 읽습니다. 속성을 지정하지 않으면 드라이버에 비밀번호를 주지 않으므로
PostgreSQL JDBC 드라이버가 사용자의 비밀번호 파일(Windows는 `pgpass.conf`, Linux는 `.pgpass`)을 읽습니다. 비밀번호는 코드, 설정, 로그, 커밋 메시지에 적지 않습니다.

### 자원이 작은 기계에서 시험 JVM을 제한하는 선택 속성

시험 JVM은 기본적으로 제한하지 않습니다. 자원이 작은 기계에서는 다음 Gradle 선택 속성으로 동시에 실행하는 시험 JVM의 수, JVM별 최대 힙 크기,
JVM이 인식하는 프로세서 수를 제한할 수 있습니다. 속성은 명령줄로만 넘기며 저장소에는 설정하지 않으므로, 이 저장소를 쓰는 다른 사용자에게 영향이 없습니다.
숫자가 아니거나 1보다 작은 값을 주면 빌드가 설정 단계에서 실패합니다.

```sh
./gradlew test -Pledger.test.forks=1 -Pledger.test.maxHeapSize=1g -Pledger.test.activeProcessorCount=1
```

### 빌드 속도

`gradle.properties`는 Gradle 구성 캐시, 빌드 캐시, 병렬 실행을 켭니다(`org.gradle.configuration-cache`, `org.gradle.caching`,
`org.gradle.parallel`). Gradle 데몬과 파일 감시는 기본값을 그대로 씁니다. 아무것도 바꾸지 않고 `./gradlew build`를 두 번째로 실행하면
구성을 다시 쓰고, 입력이 바뀐 태스크만 실행합니다. 시험 데이터베이스를 쓰는 모듈(`ledger-database`, `ledger-application`, `ledger-mcp-server`,
`ledger-admin-web`, `ledger-cli`, `ledger-system-test`)의 시험은 예외입니다. 데이터베이스의 상태는 Gradle이 볼 수 없는 입력이므로 이 시험은
매번 실행하며, 빌드 캐시에서 가져오지도 않고 캐시에 저장하지도 않습니다. 병렬 실행에서도 이 시험은 하나씩 차례로 실행합니다. 시험 데이터베이스 역할에는 연결 수 제한이 있어,
시험 JVM 여러 개가 동시에 연결하면 그 제한을 넘기 때문입니다. 다른 모듈의 시험은 캐시에서 가져올 수 있습니다. 기계의 제한(워커 수,
시험 JVM, 힙, 프로세서)은 저장소 설정이 아니므로, 위 속성과 함께 예를 들어 `--max-workers=1`처럼 명령 인자로 줍니다.

## 구성

- `ledger-domain`, `ledger-attestation`, `ledger-database`, `ledger-application`, `ledger-mcp-server`, `ledger-admin-web`,
  `ledger-cli`, `ledger-client-hook`, `ledger-migrate-1x`: 위의 모듈이며 각각 `src/main`과 `src/test`를 가집니다. `ledger-system-test`는 `src/test`만 가집니다.
- `clients/claude`, `clients/codex`, `clients/antigravity`: 클라이언트별 플러그인의 매니페스트와 README이며, 빌드가 훅 jar와 함께
  `ledger-client-hook/build/client-plugins`에 모읍니다.
- `docs/`: 시험 사례 목록, 릴리스 노트 형식, 릴리스 노트.
- `gradle/`, `build.gradle.kts`, `settings.gradle.kts`: 빌드.
- `deploy/`: 설치기 두 개, `install.sh`(Linux)와 `install.cmd`(Windows)입니다.
- 1.x 구현(DBHub, Node, PostgreSQL 함수)은 2.x의 일부가 아니며 더 이상 저장소에 없습니다. 소스는 태그 `v1.1.0`에서 볼 수 있습니다.

## 라이선스

pg-ledger-mcp는 2.0 버전부터 GNU Affero General Public License v3.0 또는 (선택에 따라) 그 이후 판(`AGPL-3.0-or-later`)에 따라 배포합니다. 자세한 내용은 [LICENSE](LICENSE)를 참조하십시오.

Copyright (C) 2026 Jaeseong Song

`AGPL-3.0-or-later`는 2.0.0과 그 이후에 공개하는 모든 릴리스에 적용하며, 이후에 공개하는 1.x 계열의 유지보수 릴리스도 포함합니다. MIT License는 이미 그 라이선스로 공개한 사본, 곧 v1.0.0과 v1.1.0에만 계속 적용합니다.

라이선스 제13조에 따라, 수정한 버전을 네트워크 서비스로 운영하는 자는 그 서비스의 이용자에게 해당 소스 코드를 제공해야 합니다.
관리 웹은 각 페이지 하단에 소스 코드 링크를 보여 줍니다. 그 주소는 설정 `ledger.source.url`이며 기본값은 프로젝트의 공개 저장소입니다. 수정한 버전을 운영하는 자는
`ledger.source.url`을 그 수정한 버전의 해당 소스 코드를 제공하는 곳으로 지정해야 합니다. 이 설정은 `http` 또는 `https` 주소를 받으며, MCP 서버의 응답에는 포함되지 않습니다.
