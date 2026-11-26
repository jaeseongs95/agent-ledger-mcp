# Third-Party Notices

`agent-ledger-mcp` is licensed under `AGPL-3.0-or-later` (see [LICENSE](LICENSE) and [ADDITIONAL-TERMS.md](ADDITIONAL-TERMS.md)).

The binary distributions of `agent-ledger-mcp` (`ledger-mcp-server.jar`, `ledger-admin-web.jar`, `ledger-cli.jar`, `ledger-client-hook.jar`, and `ledger-migrate-1x.jar`) include third-party open-source runtime libraries compatible with `AGPL-3.0-or-later`. No JDBC driver for the user's ledger DBMS is bundled (`driver-register` loads the user-supplied JDBC driver at runtime); `com.h2database:h2` is included solely in `ledger-seed` and `ledger-cli` to read the packaged installation seed bundle.

Run `./gradlew generateLicenseReport` to generate the machine-readable dependency license report under `build/reports/dependency-license/`, and `./gradlew checkThirdPartyNotices` (part of `./gradlew check`) to verify that every resolved runtime dependency group across all modules is accounted for in this file.

## Runtime Dependency Groups and Licenses

| Maven Group(s) | Component | License |
|---|---|---|
| `org.springframework.boot`, `org.springframework`, `org.springframework.data`, `org.springframework.security`, `org.springframework.ai` | Spring Boot, Spring Framework, Spring Data JDBC, Spring Security, Spring AI MCP | Apache-2.0 |
| `io.modelcontextprotocol.sdk` | Model Context Protocol (MCP) Java SDK | MIT |
| `tools.jackson.core`, `tools.jackson.dataformat`, `com.fasterxml.jackson.core`, `com.fasterxml.jackson.dataformat`, `com.fasterxml.jackson.datatype`, `com.fasterxml.jackson.module` | Jackson 3 & Jackson 2 Core / Annotations / Databind / CBOR / YAML | Apache-2.0 |
| `com.webauthn4j` | WebAuthn4J (`webauthn4j-core`, `webauthn4j-util`, `webauthn4j-metadata`) | Apache-2.0 |
| `org.apache.kerby` | Apache Directory Kerby ASN.1 (`kerby-asn1`) | Apache-2.0 |
| `org.hibernate.validator`, `org.jboss.logging`, `com.fasterxml` | Hibernate Validator, JBoss Logging, ClassMate | Apache-2.0 |
| `jakarta.validation`, `jakarta.annotation`, `jakarta.servlet` | Jakarta Bean Validation, Annotations, Servlet API | Apache-2.0 / EPL-2.0 |
| `com.zaxxer` | HikariCP JDBC Connection Pool | Apache-2.0 |
| `org.apache.tomcat.embed` | Apache Tomcat Embedded Servlet Container | Apache-2.0 |
| `org.thymeleaf`, `org.thymeleaf.extras`, `org.attoparser`, `org.unbescape` | Thymeleaf Server-Side Templates | Apache-2.0 |
| `com.nimbusds`, `net.minidev`, `org.ow2.asm`, `com.github.stephenc.jcip` | Nimbus JOSE+JWT / OAuth2 OIDC SDK, JSON Smart, OW2 ASM, JCIP Annotations (clean-room) | Apache-2.0 / BSD-3-Clause |
| `io.micrometer`, `io.projectreactor`, `org.reactivestreams`, `io.netty`, `org.hdrhistogram` | Micrometer Observation/Tracing, Project Reactor, Reactive Streams, HdrHistogram | Apache-2.0 / MIT-0 / CC0-1.0 / BSD-2-Clause |
| `org.slf4j`, `org.apache.logging.log4j` | Simple Logging Facade for Java (SLF4J), Log4j-to-SLF4J API Bridge | MIT / Apache-2.0 |
| `ch.qos.logback` | Logback Classic & Core | EPL-1.0 OR LGPL-2.1-or-later |
| `org.yaml`, `org.snakeyaml` | SnakeYAML & SnakeYAML Engine | Apache-2.0 |
| `org.jspecify` | JSpecify Nullness Annotations | Apache-2.0 |
| `commons-codec`, `commons-logging`, `org.apache.commons` | Apache Commons | Apache-2.0 |
| `com.github.erosb`, `com.github.victools`, `com.networknt`, `com.ethlo.time`, `io.swagger.core.v3`, `com.knuddels`, `org.antlr` | JSON Schema Validation & Generation (`json-sKema`, `jsonschema-generator`, `json-schema-validator`, `itu`, `swagger-annotations`, `jtokkit`, `antlr4-runtime` — transitive of Spring AI MCP) | Apache-2.0 / MIT / BSD-3-Clause |
| `com.google.crypto.tink`, `com.google.gson`, `com.google.errorprone` | Google Tink / Gson / Error Prone Annotations (if resolved transitively) | Apache-2.0 |
| `com.h2database` | H2 Database Engine (`ledger-seed` and `ledger-cli` seed bundle reader only) | MPL-2.0 OR EPL-1.0 |
