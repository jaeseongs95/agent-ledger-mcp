# Veridger 작업 지침

## 브랜치와 개발

- Gitflow를 사용한다. `main`과 `develop`을 상시 운영하고, 작업은 목적에 맞는 브랜치에서 진행한다. 작업이 통합되면 해당 작업 브랜치를 로컬과 원격에서 정리한다.
- `develop`에 변경을 통합하면 GitHub Actions의 Ubuntu·Windows 테스트 결과를 확인한다. 개발 SonarQube 분석은 로컬에서 기존 `veridger` 프로젝트로 실행한다.
- 전체 코드 커버리지는 90% 이상, 미해결 보안·신뢰성·유지관리성 이슈는 각 0건을 목표로 한다. SonarQube 지표를 확인할 때는 분석 커밋과 전체 코드·신규 코드 범위를 함께 기록한다.
- 개발 분석은 분석할 커밋을 체크아웃하고 로컬 PostgreSQL 테스트 설정과 `SONAR_HOST_URL` 환경 변수를 준비한 뒤 실행한다. 새 JaCoCo 보고서를 만든 다음 분석한다. 로컬 SonarQube 인증은 `SONAR_TOKEN` 환경 변수 또는 저장소 밖 토큰 파일을 지정하는 Gradle 속성 `sonar.token-file`을 사용한다.

  ```powershell
  .\gradlew.bat --no-daemon clean check jacocoAggregateReport
  if ($LASTEXITCODE -ne 0) { throw '테스트 또는 JaCoCo 보고서 생성 실패' }
  .\gradlew.bat --no-daemon sonar '-Dsonar.qualitygate.wait=true'
  ```

## 릴리스

1. 이번 릴리스에 포함할 변경을 모으고 `release/vX.Y.Z`에서 버전 번호, 배포·설치 설정, 릴리스 노트를 확정한다.
2. 그 커밋의 Ubuntu·Windows CI와 SonarQube Quality Gate를 확인한다. 릴리스 CI 분석은 `veridger-release` 프로젝트와 GitHub Actions 시크릿 `SONAR_RELEASE_TOKEN`, `SONAR_HOST_URL`을 사용한다.
3. 릴리스 후보의 내용이 바뀌면 바뀐 커밋을 다시 검증한다. 배지 자동 커밋은 직전 분석 커밋의 자식이며 `docs/badges/` 내 공식 SVG와 최초 발행 시 README 이미지 참조만 변경했는지 확인한다.
4. 통과한 후보를 PR로 `main`에 병합한다. 병합 결과가 검증한 후보와 배지 관련 변경 외에 동일한지 확인하고 그 소스에서 빌드한 산출물을 태그와 함께 공개한다. 다른 변경이 섞이면 해당 소스를 검증한 뒤 공개한다. 릴리스 후 `main`의 변경을 `develop`에 통합한다.

## README 품질 배지

- SonarQube 분석을 통과한 최신 릴리스 후보에만 공식 SVG를 갱신한다. 최초 갱신 시 두 README의 상대 경로 참조와 SVG를 한 커밋에 넣는다. `main`에는 릴리스 후보의 배지가 PR로 들어가며 `develop`은 이후 릴리스 병합으로 이 배지를 받는다.
- 서버 접속 정보나 토큰을 저장소나 로그에 남기지 않는다. 릴리스 분석에는 프로젝트 분석 토큰을 사용하고, 배지 단계에서만 `veridger-release`의 Browse 권한이 있는 User Token을 GitHub Actions 시크릿 `SONAR_RELEASE_BADGE_READ_TOKEN`으로 사용한다.
- GitHub 브랜치 룰셋을 유지한다. 배지 커밋은 GitHub Actions 토큰으로 서명되고 분석한 커밋이 원격 브랜치의 최신 커밋일 때만 반영한다.
