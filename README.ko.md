# Veridger

![품질 게이트](docs/badges/quality-gate.svg)
![보안 등급](docs/badges/security.svg)
![신뢰성 등급](docs/badges/reliability.svg)
![유지관리성 등급](docs/badges/maintainability.svg)
![커버리지](docs/badges/coverage.svg)
![중복 줄](docs/badges/duplicated-lines.svg)
![코드 줄 수](docs/badges/lines-of-code.svg)

[English](README.md)

Veridger는 AI 에이전트와 운영자가 함께 쓰는 작업 장부입니다. 에이전트는 MCP로 이슈, 명세, 작업, 출시 기준, 산출물을 기록합니다. 운영자는 관리자 화면에서 프로젝트와 세션 권한을 관리하고 변경 이력을 확인합니다.

## 시작하기

1. [최신 릴리스](https://github.com/jaeseongs95/veridger/releases/latest)를 내려받고 Java 25, PostgreSQL, JDBC 드라이버를 준비합니다. Windows에서는 WinSW도 필요합니다.
2. 운영체제에 맞는 설치기를 실행합니다. [Linux 설치](docs/guide.ko.md#linux-서버) · [Windows 설치](docs/guide.ko.md#windows-개인-pc-또는-서버)
3. 관리자 화면에서 프로젝트를 만들고 에이전트를 `/mcp`에 연결합니다. 클라이언트 훅을 설치한 뒤 해당 세션에 필요한 권한을 부여합니다. [클라이언트 설정](docs/guide.ko.md#클라이언트-훅)

## 문서

- [설치와 운영](docs/guide.ko.md#설치)
- [MCP 도구와 예제](docs/guide.ko.md#mcp-도구)
- [빌드와 테스트](docs/guide.ko.md#빌드와-시험)
- [릴리스 노트](docs/release-notes/)

## 라이선스

[AGPL-3.0-or-later](LICENSE) · [추가 조건](ADDITIONAL-TERMS.ko.md) · [서드파티 고지](THIRD_PARTY_NOTICES.md)
