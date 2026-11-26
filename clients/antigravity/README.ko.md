# Antigravity용 agent-ledger-mcp 플러그인

[English](README.md)

이 디렉터리는 Antigravity 플러그인입니다. Antigravity 대화가 호출하는 장부 쓰기 도구에 호스트 세션 작성자를 각인합니다. 세 클라이언트(Claude Code, Codex, Antigravity)는 같은 실행 jar인 `ledger-client-hook.jar`를 사용하며, 첫 번째 인자로 클라이언트를 구분합니다. 이 플러그인에는 MCP 연결 설정과 인증 정보가 들어 있지 않습니다.

## 동작

`plugin.json`과 `hooks.json`은 플러그인 루트에 있습니다. 플러그인 훅의 명령은 플러그인 루트에서 실행되므로, `hooks.json`은 도구를 호출하기 전에 `java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Xlog:disable -jar "./lib/ledger-client-hook.jar" antigravity`를 실행합니다. matcher는 `call_mcp_tool`과 쓰기 도구의 직접 MCP 이름(`mcp:...ledger_<op>`)을 고르고, 훅은 도구 이름을 다시 검사합니다. 훅은 쓰기 도구 13종(`issue_save`, `spec_save`, `task_save`, `criterion_save`, `transition`, `record_deliverable`, `record_case`, `link`, `correct`, `batch`, `review`, `review_correct`, `whoami`)과 첨부 도구 5종(`artifact_begin`, `artifact_chunk`, `artifact_finish`, `artifact_status`, `artifact_read`)만 처리하며, 서버 이름이 드러나는 이름에만 각인합니다.

- 직접 호출: `mcp:<server><:|/|_>[<server>_]ledger_<op>` 또는 `mcp:<server><:|/|_>[<server>_]artifact_<op>`
- `call_mcp_tool` 호출: `ServerName`이 서버 이름(`A-Za-z0-9._-` 1~100자)이고 `ToolName`이 `ledger_<op>`, `artifact_<op>`, `<ServerName>_ledger_<op>` 또는 `<ServerName>_artifact_<op>`인 경우

`mcp:ledger_issue_save`처럼 서버 이름이 없는 이름에는 각인하지 않습니다. 그 밖의 도구와 읽기 도구는 변경 없이 통과합니다.

쓰기 및 첨부 호출에는 예약 인자 `_hostAttestation`을 `gemini:<conversationId>` 값으로 추가합니다. 직접 호출에는 `overwrite`로 응답하며 Antigravity가 이를 도구의 인자에 병합합니다. `call_mcp_tool` 호출에는 교정한 `Arguments`만 돌려줍니다. `Arguments`는 객체이거나 JSON 객체를 담은 문자열이며 원래의 타입을 유지합니다. 다른 인자는 그대로 둡니다.

훅은 `allow`로만 응답하고 `auto_approve`나 `permissionOverrides`는 설정하지 않으므로 Antigravity의 권한 정책이 그대로 적용됩니다. 모델이 `_hostAttestation`을 직접 넣거나 `conversationId`가 올바르지 않으면 호출을 거부합니다. 잘못된 JSON도 종료 코드 0과 함께 명시적으로 거부합니다. Antigravity가 하위 에이전트에 부모의 `conversationId`를 제공하는지는 확인되지 않았습니다. 훅은 이를 추측하지 않으며, 하위 에이전트를 위한 별도의 식별·권한 계층도 두지 않습니다.

## 요구 사항

훅 명령은 `PATH`에서 `java`를 찾으므로, Antigravity를 실행하는 호스트의 `PATH`에 JDK 25 이상이 있어야 합니다. `java`가 없으면 명령이 실패하고, 더 낮은 JDK에서는 JVM이 `UnsupportedClassVersionError`로 jar를 거부합니다. 두 경우 모두 `_hostAttestation`이 추가되지 않으며 서버는 쓰기를 `HOST_ATTESTATION_REQUIRED`로 거절합니다. 따라서 훅이 실패해도 호스트가 각인하지 않은 쓰기가 저장되는 일은 없습니다. Antigravity는 오류로 종료한 훅 프로세스를 무시하므로, 서버의 이 거절이 유일한 방어선입니다.

## 설치

jar는 저장소에 커밋하지 않습니다. `./gradlew :ledger-client-hook:assemble`을 실행하면 `ledger-client-hook/build/client-plugins/antigravity/`에 플러그인 디렉터리가 만들어지며, 가벼운 클라이언트는 `lib/ledger-client-hook.jar`에, 상주 프로그램은 `lib/ledger-hook-resident.jar`에 들어 있으며 두 jar는 나란히 있어야 합니다. 이 디렉터리를 Antigravity의 플러그인 위치에 복사합니다.

프로젝트 훅으로 쓰려면 `<workspace>/.agents/hooks.json`의 이름 붙은 훅 그룹에 병합하고 jar의 경로를 절대 경로로 바꿉니다. 전역 파일은 `~/.gemini/config/hooks.json`입니다. 기존 설정을 덮어쓰거나 훅을 두 번 설치하지 않습니다.

## 세션 권한 부여

1. Antigravity의 MCP 클라이언트가 서버의 `ledger.mcp.token` Bearer 토큰으로 인증하도록 설정합니다.
2. 관리 콘솔(`/grants`) 또는 `ledger-cli grant`로 `gemini:<conversationId>`에 프로젝트 권한을 부여합니다.
3. 세션별 키 등록이나 세션별 Bearer 토큰 발급은 필요하지 않으며, 권한이 부여되는 즉시 쓰기 도구를 호출할 수 있습니다.

## 상주 프로그램

가벼운 클라이언트는 호스트의 입력을 루프백으로 같은 폴더의 상주 프로그램 `ledger-hook-resident.jar`에 보내고, 그 답을 그대로 출력합니다. 호스트와의 계약은 이전 훅과 같습니다. 표준 입력은 호스트 JSON, 표준 출력에는 답만(JVM 줄도 로그 줄도 없음), 종료 코드, 실패의 의미가 같습니다.

- 실행 중인 프로그램이 없으면 첫 호출이 프로그램을 띄웁니다(약 1초). 이후 호출은 약 0.1초 걸립니다. 프로그램은 호출이 끝난 뒤에도 남아 있다가, 15분 동안 호출이 없으면 스스로 끝납니다(`LEDGER_HOOK_RESIDENT_IDLE_TIMEOUT`, `PT30M` 같은 ISO-8601 기간). `java -jar lib/ledger-client-hook.jar stop`으로 끝낼 수도 있습니다.
- `127.0.0.1`에서만 연결을 받습니다. 모든 요청은 시작할 때마다 새로 만드는 비밀 값을 가져야 하며, 이 값은 상태 폴더 `~/.agent-ledger-mcp/resident/<id>/`의 주소 파일에만 있습니다. 폴더와 그 안의 파일은 소유자 전용입니다. 프로그램의 출력은 그 폴더의 `resident.log`에 남습니다.
- 프로그램은 `~/.agent-ledger-mcp/resident-runtime/<build>/`의 복사본에서 실행되므로, 실행 중인 프로그램 때문에 플러그인의 갱신이나 삭제가 막히지 않습니다. 판이 다른 클라이언트(갱신된 플러그인)는 실행 중인 프로그램을 교체합니다.
- 프로그램에 닿지 못하거나 띄우지 못하면 호출은 종료 코드 2와 표준 오류의 `ledger: the hook program is not available` 줄(뒤에 이유)로 실패하고, 서버는 쓰기를 `HOST_ATTESTATION_REQUIRED`로 거절합니다. `resident.log`를 읽고, 두 jar가 `lib`에 있는지, `java`가 JDK 25 이상인지 확인합니다.

## 한계

훅은 로컬에서 모델이 `_hostAttestation`을 위조하지 못하도록 막으며, 네트워크 경계는 `ledger.mcp.token`이 보호합니다. `ledger.mcp.token`은 신뢰하는 소유자 호스트에만 보관하십시오. `Arguments`의 실제 형태와 교정한 인자를 서버가 받는지는 네이티브 클라이언트에서 따로 확인해야 합니다.

## 참고

- [PreToolUse 입력·출력과 설정 경로](https://antigravity.google/docs/hooks/#pretooluse)
- [플러그인 배치, 명령 디렉터리, 실패 처리](https://antigravity.google/docs/build-plugins/#lifecycle-hooks)
- [메인 README의 클라이언트 훅](../../README.ko.md)
