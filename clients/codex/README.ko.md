# Codex용 pg-ledger-mcp 플러그인

[English](README.md)

이 디렉터리는 Codex 플러그인입니다. Codex 세션이 호출하는 장부 쓰기 도구의 작성자를 증명합니다. 세 클라이언트(Claude Code, Codex, Antigravity)는 같은 실행 jar인 `ledger-client-hook.jar`를 사용하며, 첫 번째 인자로 클라이언트를 구분합니다. 이 플러그인에는 MCP 연결 설정과 인증 정보가 들어 있지 않습니다.

## 동작

`plugin.json`의 `extensions.com.openai.hooks`가 `hooks/hooks.json`을 가리킵니다. 이 파일의 `PreToolUse` 훅은 장부 쓰기 도구를 호출하기 전에 `java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Xlog:disable -jar "${PLUGIN_ROOT}/lib/ledger-client-hook.jar" codex`를 실행합니다(Windows에서는 `cmd.exe /d /c java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Xlog:disable -jar "%PLUGIN_ROOT%/lib/ledger-client-hook.jar" codex`). 훅은 쓰기 도구 13종(`issue_save`, `spec_save`, `task_save`, `criterion_save`, `transition`, `record_deliverable`, `record_case`, `link`, `correct`, `batch`, `review`, `review_correct`, `whoami`)과 첨부 도구 5종(`artifact_begin`, `artifact_chunk`, `artifact_finish`, `artifact_status`, `artifact_read`. `whoami`와 같이 증명에 `operationKey`가 없습니다)만 처리하며, 도구 이름이 다음 형식 중 하나일 때 서명합니다. `<tool>`은 `ledger_<op>` 또는 `artifact_<op>`입니다.

- `mcp__<server>__<tool>`
- `mcp__<server>__<server>_<tool>`: 접두어는 서버 이름과 정확히 같아야 합니다
- `mcp__codex_apps__app_<app>_<tool>`: Codex Apps의 네임스페이스입니다

읽기 도구, 알 수 없는 작업, 그 밖의 형태의 이름은 변경 없이 통과합니다.

쓰기 호출에는 예약 인자 `_hostAttestation`을 추가합니다. 값은 작성자 `codex:<session_id>`의 Ed25519 세션 키로, 호출 대상 MCP 서버에 대해 만든 서명입니다. 서명은 도구, 호출의 `operationKey`, 모든 인자의 정규형(RFC 8785)에 대한 SHA-256 다이제스트, 시각을 포함합니다. 훅은 호출의 `operationKey`를 인자에서 읽을 뿐 만들어 넣지 않으며, 없는 호출은 서버가 거절합니다. 다른 인자는 그대로 둡니다. 훅은 `allow`와 `updatedInput`으로 응답하고, 거부할 때는 `deny`로 응답합니다. 모델이 `_hostAttestation`을 직접 넣거나 호스트의 `session_id`가 올바르지 않으면 호출을 거부합니다. 잘못된 JSON도 종료 코드 0과 함께 명시적으로 거부하는데, 실패한 훅 프로세스는 믿을 만한 차단 수단이 아니기 때문입니다. 훅은 그 밖의 권한 결정을 내리지 않습니다.

Codex Apps의 모든 앱은 가상 서버 `codex_apps`를 공유하므로, 서명의 대상(audience)을 앱으로 삼고 서버 식별자를 `codex_apps.<app>`으로 정합니다. `mcp__codex_apps__app_ledger_ledger_issue_save`의 식별자는 `codex_apps.ledger`이며, 소유자는 키를 등록할 때 이 이름을 씁니다: `java -jar lib/ledger-client-hook.jar session-key codex_apps.ledger <author>`. `app_evil_ledger_issue_save` 같은 비슷한 이름의 앱은 다른 식별자와 다른 키를 쓰므로, 그 서명은 `codex_apps.ledger`의 키로 검증되지 않습니다.

Codex는 하위 에이전트의 훅에 부모 세션의 `session_id`를 제공합니다. 훅은 다른 식별자를 찾지 않으며, 하위 에이전트를 위한 별도의 계층도 두지 않습니다.

## 요구 사항

훅 명령은 `PATH`에서 `java`를 찾으므로, Codex를 실행하는 호스트의 `PATH`에 JDK 25 이상이 있어야 합니다. `java`가 없으면 명령이 실패하고, 더 낮은 JDK에서는 JVM이 `UnsupportedClassVersionError`로 jar를 거부합니다. 두 경우 모두 `_hostAttestation`이 추가되지 않으며 서버는 쓰기를 `HOST_ATTESTATION_REQUIRED`로 거절합니다. 따라서 훅이 실패해도 작성자가 증명되지 않은 쓰기가 저장되는 일은 없습니다.

## 설치

jar는 저장소에 커밋하지 않습니다. `./gradlew :ledger-client-hook:assemble`을 실행하면 `ledger-client-hook/build/client-plugins/codex/`에 플러그인 디렉터리가 만들어지며, 가벼운 클라이언트는 `lib/ledger-client-hook.jar`에, 상주 프로그램은 `lib/ledger-hook-resident.jar`에 들어 있으며 두 jar는 나란히 있어야 합니다. 이 디렉터리를 Codex의 플러그인 위치에 복사합니다. Codex가 플러그인을 불러온 뒤에는 `/hooks`에서 훅의 정확한 정의를 검토하고 신뢰합니다.

프로젝트 훅으로 쓰려면 같은 항목을 `<repo>/.codex/hooks.json`에 병합하고 jar의 경로를 절대 경로로 바꿉니다. 기존 훅을 덮어쓰거나 훅을 두 번 설치하지 않습니다.

## 세션 키 등록

1. 어떤 작성자가 어떤 서버에 처음 쓰기를 호출하면 그 작성자와 서버의 키 쌍이 만들어집니다. 키는 `~/.pg-ledger-mcp/session-keys`에 보관하며, `LEDGER_HOOK_KEY_DIR`에 절대 경로를 주어 바꿀 수 있습니다. 훅은 새 키 폴더와 새 키 파일을 소유자 전용으로 만들고(Windows에서는 소유자만 허용하는 ACL), 이미 있는 폴더와 키 파일은 누가 접근할 수 있든 누가 소유하든 그대로 읽습니다(링크는 따라갑니다). 개인판은 PC 전체를 신뢰합니다. 키 파일 자리의 디렉터리와 빈 키 파일은 거절합니다.
2. `java -jar lib/ledger-client-hook.jar session-key <server> <author>`는 서버, 작성자, 공개 키, 지문을 출력합니다. `<server>`는 Codex가 도구 이름에 보여 주는 MCP 서버의 이름이며, 예를 들어 `mcp__ledger__ledger_issue_save`의 서버는 `ledger`이고 Codex Apps에서는 `codex_apps.<app>`입니다.
3. 소유자는 지문을 세션이 보여 준 지문과 대조한 뒤, `ledger-cli`의 `key-register`로 또는 관리 웹의 권한 부여 화면에서 공개 키를 등록합니다. 그 뒤에야 작성자의 권한 부여가 동작합니다.

키는 서버 하나와 작성자 하나에 속합니다. 시험 서버나 이름이 비슷한 서버를 위해 만든 서명은 운영 서버에서 검증되지 않으므로 서버마다 따로 등록합니다. 서버는 권한 부여를 보기 전에 서명, 도구, `operationKey`, 인자, 시각을 검증합니다.

## 상주 프로그램

가벼운 클라이언트는 직접 서명하지 않습니다. 호스트의 입력을 루프백으로 같은 폴더의 상주 프로그램 `ledger-hook-resident.jar`에 보내고, 그 답을 그대로 출력합니다. 호스트와의 계약은 이전 훅과 같습니다. 표준 입력은 호스트 JSON, 표준 출력에는 답만(JVM 줄도 로그 줄도 없음), 종료 코드, 실패의 의미가 같습니다.

- 실행 중인 프로그램이 없으면 첫 호출이 프로그램을 띄웁니다(약 1초). 이후 호출은 약 0.1초 걸립니다. 프로그램은 호출이 끝난 뒤에도 남아 있다가, 15분 동안 호출이 없으면 스스로 끝납니다(`LEDGER_HOOK_RESIDENT_IDLE_TIMEOUT`, `PT30M` 같은 ISO-8601 기간). `java -jar lib/ledger-client-hook.jar stop`으로 끝낼 수도 있습니다.
- `127.0.0.1`에서만 연결을 받습니다. 모든 요청은 시작할 때마다 새로 만드는 비밀 값을 가져야 하며, 이 값은 상태 폴더 `~/.pg-ledger-mcp/resident/<id>/`의 주소 파일에만 있습니다. 폴더와 그 안의 파일은 소유자 전용입니다. 프로그램의 출력은 그 폴더의 `resident.log`에 남습니다.
- 프로그램은 `~/.pg-ledger-mcp/resident-runtime/<build>/`의 복사본에서 실행되므로, 실행 중인 프로그램 때문에 플러그인의 갱신이나 삭제가 막히지 않습니다. 판이 다른 클라이언트(갱신된 플러그인)는 실행 중인 프로그램을 교체합니다.
- 프로그램에 닿지 못하거나 띄우지 못하면 호출은 종료 코드 2와 표준 오류의 `ledger: the hook program is not available` 줄(뒤에 이유)로 실패하고, 서버는 쓰기를 `HOST_ATTESTATION_REQUIRED`로 거절합니다. `resident.log`를 읽고, 두 jar가 `lib`에 있는지, `java`가 JDK 25 이상인지 확인합니다.

## 한계

같은 운영체제 사용자의 다른 프로세스는 키 파일을 읽을 수 있으므로, 한 사용자 안의 격리에는 별도 계정이 필요합니다. 훅은 허용과 거부 모두 종료 코드 0으로 응답합니다. 훅 프로세스가 실패하거나 호출 경로가 훅을 우회하면 훅이 호출을 막을 수 없으며, 그 경우의 방어선은 서버의 `HOST_ATTESTATION_REQUIRED` 거절입니다. 실제 호스트의 전달, Windows에서의 실행, 운영 적용 전 보안 검토에는 별도의 근거가 필요합니다.

## 참고

- [훅 검색 경로, 신뢰, 입력과 출력](https://learn.chatgpt.com/docs/hooks)
- [플러그인 매니페스트와 훅 경로](https://developers.openai.com/plugins/build/plugins)
- [메인 README의 클라이언트 훅](../../README.ko.md)
