# Veridger

![Quality Gate](docs/badges/quality-gate.svg)
![Security Rating](docs/badges/security.svg)
![Reliability Rating](docs/badges/reliability.svg)
![Maintainability Rating](docs/badges/maintainability.svg)
![Coverage](docs/badges/coverage.svg)
![Duplicated Lines](docs/badges/duplicated-lines.svg)
![Lines of Code](docs/badges/lines-of-code.svg)

[한국어](README.ko.md)

Veridger is a work ledger for AI agents and their operators. Agents record issues, specifications, tasks, release criteria, and deliverables over MCP. Operators use the admin console to manage projects, grant session permissions, and review the audit history.

## Getting started

1. Download the [latest release](https://github.com/jaeseongs95/veridger/releases/latest). Prepare Java 25, PostgreSQL, and its JDBC driver. Windows also requires WinSW.
2. Run the installer for your host. See the [Linux instructions](docs/guide.md#linux-server) or [Windows instructions](docs/guide.md#windows-a-personal-pc-or-a-server).
3. Create a project in the admin console, connect your agent to the `/mcp` endpoint, install its hook, and grant the session the permissions it needs. [Client setup](docs/guide.md#client-hooks)

## Documentation

- [Installation and administration](docs/guide.md#installation)
- [MCP tools and examples](docs/guide.md#mcp-tools)
- [Build and test](docs/guide.md#build-and-test)
- [Release notes](docs/release-notes/)

## License

[AGPL-3.0-or-later](LICENSE) · [Additional terms](ADDITIONAL-TERMS.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)
