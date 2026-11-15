# pg-ledger-mcp

[한국어](README.ko.md)

A ledger for work done by AI agents, served as one MCP endpoint. It records defects, tasks, fixes and their verification,
together with who did each one and why. Agents read and write through MCP tools; they never get a database login of their own.

Version 2.0.0 is a rewrite in Java 25 and Spring Boot. It keeps the contract that clients rely on (the names and input keys
of the write tools, the keys of the results, the `_hostAttestation` key) and changes what is stated in the
[release notes](docs/release-notes/2.0.0.md); the format of release notes is in [docs/RELEASE_NOTES_FORMAT.md](docs/RELEASE_NOTES_FORMAT.md).

## Contents

- Concepts
- MCP tools
- Attachments
- Modules
- Configuration
- Installation
- ledger-cli
- Admin web
- Client hooks
- Upgrading from 1.x
- Build and test
- Layout

## Concepts

- **Author identity is proved, not claimed.** Every write and every `ledger_whoami` carries the reserved key `_hostAttestation`,
  which the host hook of the client fills in. Models must omit it. The author is `<vendor>:<session-id>`, for example
  `claude:1f2e3d4c` or `codex:9a8b7c6d`; the owner of the ledger is `user:owner`. A deprecated `author_id` key is accepted and ignored.
- **Writes need a live grant.** The owner grants an author a scope on a project with `ledger-cli grant` (or on the grants page of
  the admin web). The scopes are `defect`, `task`, `fix` and `verify`. A write without a matching grant is refused.
- **Verification is separate.** `VERIFIED`, `MET` and `NOT_MET` are set only through the reviewer tools `ledger_review` and
  `ledger_review_correct`, which need the scope `verify`. A defect is verified only with an evidence link and at least one
  recorded fix from another session than the verifier; the verification and rework requests go to the session that recorded
  the fix (`fixed_by`), not to the one that registered the defect (`created_by`). CI results are optional metadata.
- **History is append-only.** Every write adds a history row: when, author, channel, operation, state change and reason. The
  application has no path that updates or deletes a history row, and the grant template of the database module (`migrate` applies it to
  the application account) gives that account no `UPDATE` or `DELETE` on it. A mistake is marked with a `CORRECT` row. An operation that changes an
  existing row also stores the full row as it was before the change (`before_data`, returned by `ledger_history` with
  `include_before`).
- **Retries are safe.** Pass `request_id`, a UUID. Repeating the same request returns the stored result and writes nothing twice.
  A request id that is already bound to another operation, payload, author or channel is refused with `REQUEST_CONFLICT`.
- **Optimistic locking.** Edits and transitions carry `expected_version`; a stale version is `VERSION_CONFLICT`.
- **Projects and ids.** A defect belongs to a project and gets the id `<CODE>-D-0001`, `<CODE>-D-0002`, and so on. A task without
  an id gets `<CODE>-T-0001`, and so on. The server assigns them. An id that a caller chooses for a task or a condition is 1 to 120
  characters without whitespace, in any case and with any characters (`:`, `/`, `#`, `.`, `_`, `-` and so on); an id that equals an
  existing one when the case is ignored is refused (`DUPLICATE_KEY`), and an id is always compared exactly.
- **One defect, one root cause, many observed cases.** Each observation of a defect (symptom, log, user report) is a case under it
  (`ledger_record_case`). A merged defect (`MERGED`, `merged_into`) keeps its cases; `ledger_defect` shows them under the defect it
  was merged into, and a `MERGED` defect takes no new cases.
- **Common codes.** Defect category, defect severity and relation kind are the three groups `DEFECT_CATEGORY`, `DEFECT_SEVERITY` and
  `RELATION_KIND`, with Korean and English names. The active values are part of the tool definitions. A retired code stays on
  existing rows and is refused when newly assigned. A severity is required on every defect. A relation kind has a direction, which is
  part of its code: `caused_by` (a is caused by b) and `blocks` (a blocks b) are directed, keep `a` and `b` in the order the caller gave
  them, and so (a, b) and (b, a) are two relations; `same_site`, `same_family`, `same_fix_unit` and `regression_of` have no direction
  and are one relation in either order. A kind that is added with `code-set` or in the admin console declares its direction as well
  (`ledger_codes` shows it, and the list of kinds in the `ledger_link` definition marks it); the direction of a kind that already has
  relations cannot be changed.
- **Creation provenance is immutable.** New rows receive `created_by` automatically; edits and reviewer transitions keep it.
- **Time is UTC.** Timestamps are stored and transmitted in UTC (ISO-8601 with `Z`); the server converts no time zone. The JVM and
  the database session must run in UTC, and a server that finds otherwise does not start (`ledger.schema.require-utc`).
- **ANSI SQL.** The product does not bundle a JDBC driver: the user obtains the JDBC driver of the database of their choice
  and registers it with `driver-register`. The SQL is ANSI and uses the JDBC standard, and no DBMS-specific feature is used: the
  schema files have no triggers, procedures, views, sequences, identity columns or boolean columns, and the rules that the database
  used to enforce are enforced by the application services. Three places differ by product: the row limit of a list, the
  declaration of the long text column type and the probe that proves the encoding of the database, and `SqlBuilder` chooses all three from the product of the connection. PostgreSQL is the
  database that is tested and guaranteed in this edition. Other products may work but are not tested, and outside these three places
  this edition contains no tailoring for a particular product (collations, session settings). The database must use UTF-8 and compare text case-sensitively.
  `migrate` proves the encoding before it changes anything: it sends a text of three characters (two Korean letters and an emoji) through a query of the dialect and
  refuses to go on when the database gives back another length or another text; it does not read the list of character sets of the database.
  Where a database keeps a default character set for each schema and a character set for the server as well as one for the connection (MariaDB
  and MySQL do), all three must be utf8mb4: the default character set of the database of the JDBC URL (the schema that is checked), the variable
  character_set_server and the character set of the connection. `migrate` refuses to go on, before it changes anything, when the first two are not.

A direct MCP caller is not authenticated as a host: the bearer token admits the caller, and the signed attestation proves the author.

## MCP tools

The server speaks MCP over Streamable HTTP at `/mcp` (default `127.0.0.1:8765`) and answers `/healthz`. Callers present
the bearer token `ledger.mcp.token`. The write tools keep their names and input keys. The results carry their keys at the top
level of the result; the wrapping `rows[0].result` of 1.x is gone. Every tool input is closed: an unknown argument is refused.

Write tools (a login, an attestation and a live grant are needed):

| Tool | Channel | What |
|---|---|---|
| `ledger_whoami` | author | is this author well formed, which grants does it hold (read only) |
| `ledger_save` | author | create or edit a defect, task or condition |
| `ledger_transition` | author | change state (`OPEN`, `SOURCE_FIXED`, `MERGED`, `ACTIVE`, `REPORTED_DONE`, and so on) |
| `ledger_record_fix` | author | record a fix commit and optional CI metadata; the fixing session becomes `fixed_by` and an `OPEN` defect becomes `SOURCE_FIXED` |
| `ledger_record_case` | author | add an observed case under a defect (a `MERGED` defect is refused and names its target) |
| `ledger_link` | author | link defects, tasks and conditions, or remove a link; the relation kinds `caused_by` and `blocks` are directed (`a` is the subject, an unlink names the ends in the order they were linked) |
| `ledger_correct` | author | mark a history row as wrong |
| `ledger_batch` | author | up to 200 of the operations above in one transaction: all commit or none |
| `ledger_review` | reviewer | `VERIFIED`, `MET`, `NOT_MET`, and reopening them; the evidence link is required |
| `ledger_review_correct` | reviewer | mark a verification row as wrong |

Attachment tools (a login, an attestation and at least one live grant of the author; see Attachments below):

| Tool | What |
|---|---|
| `artifact-begin` | start the upload of a file that a record can point to; `operationKey` is required and makes the call repeatable, `purpose` is optional and only `evidence` is supported |
| `artifact-chunk` | send the next chunk (at most 262144 bytes, base64) with its SHA-256; the last applied chunk sent again is answered with the current state |
| `artifact-finish` | check the received bytes against the declared size and SHA-256 and keep the file as an attachment; the same upload id again gives the same attachment id |
| `artifact-status` | the state, size, SHA-256, expiry and progress of an upload or an attachment (never a path or a storage key) |
| `artifact-read` | one page of a kept attachment, decrypted and checked |

Read tools (no attestation and no grant; structured queries that replace the raw SQL tools of 1.x):

| Tool | What |
|---|---|
| `ledger_get` | state and version of many ids at once (defects, tasks and conditions mixed); the check after a write and the source of `expected_version` |
| `ledger_defect` | one defect with fixes, tasks, conditions, cases (also those of merged defects), relations, `fixed_by` and recent changes |
| `ledger_task` | one task with purpose, success condition, origin, owner, the session that reported it done, defects and conditions |
| `ledger_history` | the history and the receipt of every write, by ids, id range, entity, project, operation, author or time range; `include_before` adds `before_data` |
| `ledger_search` | defects, tasks and conditions by keywords, case-insensitive, `match` is `ANY` (default) or `ALL` |
| `ledger_defects` | the defects of a project, filtered by status, severity, category, attention kind and owner |
| `ledger_tasks` | the tasks of a project, filtered by state, owner and attention kind |
| `ledger_conditions` | the release conditions of a project, filtered by state and release |
| `ledger_attention` | what needs a look: `OPEN_WITH_FIX`, `FIXED_WITHOUT_COMMIT`, `OPEN_NO_OWNER`, `TASK_UNKNOWN`, `DONE_NOT_VERIFIED` |
| `ledger_stats` | counts per state and severity of one project or of all; `bucket` = `day` adds the new items per UTC day |
| `ledger_projects` | the projects with id, code and title |
| `ledger_codes` | the common codes; retired codes with `include_inactive` |

The list read tools take `max_rows` (1 to 1000, default 50) and `cursor`; their answers carry `truncated` and `next_cursor`.
The tools `execute_sql_reader` and `search_objects_reader` of 1.x were removed: use the read tools above.

When a common code changes, the server sends `notifications/tools/list_changed`, so a connected client reloads the tool
definitions without a restart. A change made by another process (the admin web, `ledger-cli`) is noticed within
`ledger.mcp.code-poll-interval`. A new tool is registered when the server starts.

`ledger_record_case` takes `defect_id`, `observation` (1 to 4000 characters) and `reason`, and optionally `evidence`,
`user_quote` and `observed_at` (ISO-8601 with `Z` or an offset). In `ledger_batch` the same arguments go in an operation
`{"op": "record_case", ...}`:

```json
{"defect_id": "WEB-D-0007", "observation": "checkout returns 500 for a gift card",
 "evidence": "request 7f3a, api/checkout.ts:88", "user_quote": "I can't pay with my gift card",
 "observed_at": "2026-11-05T10:00:00Z", "reason": "second report of the same failure"}
```

## Attachments

A review report (or any other evidence) is kept as an attachment, never as text in the database and never as a local file that
can be edited without a trace. The history row of a record points to it with the optional `attachment_id` of `ledger_transition`,
`ledger_review`, `ledger_review_correct`, `ledger_correct`, `ledger_record_fix`, `ledger_record_case` and the same operations of
`ledger_batch`. The record is written only when the attachment exists and was uploaded by the author who writes the record.

- **Upload.** Uploads are made through the five MCP tools above: `artifact-begin` (with a required `operationKey`), `artifact-chunk`
  for each chunk of at most 262144 bytes, `artifact-finish`, and `artifact-status` and `artifact-read` to look at it. A client
  program outside the model that sends a file of the PC is an open item; the tools are the one way in.
- **Storage.** The database holds only metadata and the name of the file (`upload_staging`, `attachment`, `attachment_file`;
  no body column, no foreign key). The file is under `ledger.attachments.directory`, named by 128 random bits, with no extension and
  no original name, encrypted with AES-256-GCM in segments (one per chunk, the storage key and the position are authenticated), and
  read only once it is finished. An attachment is never changed or deleted: the application account of the database has `SELECT`
  and `INSERT` on it only.
- **Keys.** `setup` creates the first 256-bit key file in `ledger.attachments.key-directory` (readable by the service account
  only) and sets `ledger.attachments.active-key-id`. To rotate: `ledger-cli attachments-key-create`, set `active-key-id` to the new id,
  restart, then `ledger-cli attachments-rekey --from=<old id>` writes the files of the old key again; an old key file is kept while a
  current file or an open upload uses it. The start check names the key id (never the key) when a needed key file is missing.
- **Errors.** `ARTIFACT_CHUNK_CONFLICT`, `CHUNK_HASH_MISMATCH`, `ARTIFACT_BUSY` (send again later), `ARTIFACT_INCOMPLETE`,
  `ARTIFACT_HASH_MISMATCH` (the bytes are kept), `ARTIFACT_CORRUPTED`, `ARTIFACT_LIMIT`, `ARTIFACT_UNSUPPORTED_PURPOSE`,
  `ARTIFACT_EXPIRED`, `ARTIFACT_NOT_FOUND`, `ARTIFACT_OPERATION_CONFLICT`, `ARTIFACT_STORAGE_FULL` (the disk is too full: the owner
  has to free it), `ARTIFACT_STORAGE_IO_ERROR`, `ARTIFACT_STORAGE_UNSAFE` (the storage path cannot be used for this purpose: a file where the folder belongs, a folder where a file belongs, or it cannot be made owner-only on this file system or for this account), `ARTIFACT_KEY_MISSING` (no usable key: the key file is missing, empty or not 256 bits long; restore it). Every error of these tools
  carries `details.retryable`, `true` only when the same request sent later can succeed (`ARTIFACT_BUSY`, and `ARTIFACT_LIMIT`
  with `details.limit` `staging-budget` or `open-uploads`); `ARTIFACT_INCOMPLETE` is `false` (send the missing chunk, then finish).
- **Check.** `ledger-cli attachments-check` reports the attachments that are missing or damaged (never deleted) and those no
  history row points to (kept), and moves a file that no row names, or that a rekey replaced, to `orphans/` once
  `orphan-grace` has passed; `--purge-orphans` is the only way `orphans/` is emptied (audited). The admin web shows the same result
  read only, and shows a text attachment escaped on the page of the record and offers any other type as a download through the
  application.
- **Backup.** Back up and restore the attachment directory, the key directory and the database together. After a restore run
  `ledger-cli attachments-check`: it has to report no missing and no damaged attachment. The check moves a quarantined file back
  when its row exists again.
- **Install.** The installer creates the attachment directory (owned by the service account, closed to every other account), the key
  directory below the secret directory, and the settings. 1.x evidence stays as the URI and digest it was recorded with: the import
  fetches nothing.

## Modules

The build is a Gradle multi-module project.

| Module | What |
|---|---|
| `ledger-domain` | aggregates, value types, state rules, error codes and `ToolContract`, the list of tool names |
| `ledger-attestation` | the `_hostAttestation` token, RFC 8785 canonical JSON and the Ed25519 key forms, shared by the hook and the server (JDK only) |
| `ledger-database` | Spring Data JDBC repositories, the schema files `V{n}__*.sql` and their runner, the loader of the registered JDBC driver |
| `ledger-application` | the application services: write gate, receipts, history, the write and read operations, grants, session keys |
| `ledger-mcp-server` | the Spring Boot MCP server (`ledger-mcp-server.jar`) |
| `ledger-admin-web` | the Spring Boot admin web for the owner (`ledger-admin-web.jar`) |
| `ledger-cli` | the owner command line tool (`ledger-cli.jar`) |
| `ledger-seed` | the seed bundle of a new installation, the default defect categories and the install defaults in one H2 file, and its reader; only `ledger-cli` uses it |
| `ledger-migrate-1x` | the separate tool that moves a 1.x installation to 2.0 (`ledger-migrate-1x.jar`, see Upgrading from 1.x); it uses `ledger-domain`, `ledger-database` and `ledger-application`, and the classes (the plain jar, none of its libraries) of `ledger-cli` for its command line machinery and secret store, so that the secret store is the one of the services; no other module uses it |
| `ledger-client-hook` | the one hook jar of all clients (`ledger-client-hook.jar`) |
| `ledger-system-test` | test code only: the system test of the packaged product, which starts the built jars against the local PostgreSQL |

## Configuration

Settings are Spring Boot properties: in a file, on the command line (`--name=value`) or from the secret store. Secrets are never
put in a file or in an environment variable; `ledger-cli secret-set <name>` reads a secret with hidden input and stores it as one file
of the secret directory, which the processes read as a Spring configuration tree. A secret is stored under the name of its property.
There is no command that prints a stored secret.

| Key | Used by | Meaning |
|---|---|---|
| `ledger.jdbc.url` | all | JDBC URL of the ledger database |
| `ledger.jdbc.username` | all | database account |
| `ledger.jdbc.password` | all | database password (secret) |
| `ledger.jdbc.pool-size` | all | connection pool size, default 10 |
| `ledger.jdbc.driver` | all | the registered driver, written by `driver-register` (jar, class, SHA-256), read at start |
| `ledger.schema.name` | all | schema that holds the tables; created by `migrate` when missing; absent means the default schema of the account |
| `ledger.schema.require-utc` | servers, CLI | refuse to start when the JVM or the database session is not UTC, default true |
| `ledger.attestation.window` | servers | how far the time of an attestation may be from the server time, default `PT5M` |
| `ledger.mcp.token` | MCP server, `mcp-check` | the bearer token of the agents, at least 16 characters (secret) |
| `ledger.mcp.allowed-hosts` | MCP server | accepted `Host` values, default `127.0.0.1` and `localhost` |
| `ledger.mcp.allowed-origins` | MCP server | accepted browser origins, default none |
| `ledger.mcp.max-request-size` | MCP server | largest request body, default 1MB |
| `ledger.mcp.code-poll-interval` | MCP server | how often a code change of another process is looked up, default 5s |
| `ledger.admin.token` | admin web | the admin token, at least 16 characters (secret): the bearer of the read-only JSON endpoints under `/api`; with `ledger.admin.sign-in=token` it is also the password of the login page; it never approves a change |
| `ledger.admin.agent-token` | admin web | required: the agents' bearer token, equal to `ledger.mcp.token`, and distinct from `ledger.admin.token`; the admin web refuses it with `AGENT_TOKEN_FORBIDDEN` (secret) |
| `ledger.admin.allowed-hosts` | admin web | accepted `Host` values (DNS rebinding guard), default `127.0.0.1` and `localhost` |
| `ledger.admin.driver-file` | admin web | the driver registration file the driver page writes, default `/etc/pg-ledger-mcp/jdbc-driver.yaml` |
| `ledger.admin.driver-directory` | admin web | the one directory a driver jar may be taken from, default `/etc/pg-ledger-mcp/drivers` |
| `ledger.admin.sign-in` | admin web | how the owner signs in, exactly one of `token`, `oidc` or `passkey`; the login page offers only that one; absent means `passkey` for a new installation, and an installation with `ledger.admin.oidc.*` settings and no choice is refused at start (nothing is guessed) |
| `ledger.admin.oidc.owner-email` | admin web | with `ledger.admin.sign-in=oidc`: the one e-mail address of the owner, which the provider must have verified; the provider itself is `spring.security.oauth2.client.*` |
| `ledger.admin.oidc.issuer` | admin web | with `ledger.admin.sign-in=oidc`: the issuer (`iss`) the address counts for: an address is only meaningful at the issuer that verified it |
| `ledger.admin.oidc.owners` | admin web | no longer supported (the earlier list of owners): a configuration that still has it is refused at start, and the message names `ledger.admin.oidc.owner-email` |
| `ledger.admin.passkey.rp-id` | admin web | the host name the passkeys are bound to; default: the one non-loopback host of `ledger.admin.allowed-hosts`, else `localhost` (several public hosts need this setting) |
| `ledger.admin.passkey.origins` | admin web | the origins the browser shows (scheme, host and port): `https` except `http://localhost`; default `https://<rp-id>`, or `http://localhost:<server.port>` for `localhost` |
| `ledger.admin.lockout.max-failures` | admin web | failed attempts (admin token, passkey sign-in, one-time code) of one client before it is locked, default 5 |
| `ledger.admin.lockout.window` | admin web | the period in which failures are counted, default 60s |
| `ledger.admin.lockout.lock-period` | admin web | how long a client stays locked, default 60s |
| `ledger.admin.lockout.global-max-failures` | admin web | failed attempts of all clients together within the window before the global delay starts, default 50 |
| `ledger.admin.lockout.global-delay` | admin web | the wait before a token is compared once the global budget is used, default 1s; it slows down and never locks |
| `ledger.admin.mcp.health-url` | admin web | the `/healthz` URL of the MCP server shown on the overview; absent means not configured |
| `ledger.admin.mcp.timeout` | admin web | timeout of that check, default 2s |
| `ledger.attachments.directory` | servers, CLI | where the files of the attachments are kept (the installer sets it to `<data folder>/attachments`); when it is not set the attachment tools are not registered |
| `ledger.attachments.key-directory` | servers, CLI | where the key files are kept, outside `directory` (the start fails when it is inside); the installer sets it to `<settings folder>/secrets/attachment-keys` |
| `ledger.attachments.active-key-id` | servers, CLI | the key that new uploads are written with; `setup` writes it into `local.yaml`; the server does not start without the key file |
| `ledger.attachments.max-file-bytes` | servers, CLI | the largest file, default 64MB |
| `ledger.attachments.staging-budget-bytes` | servers, CLI | what all open uploads may declare together, default 512MB |
| `ledger.attachments.attachment-budget-bytes` | servers, CLI | what all kept attachments (and the open uploads) may hold together, default 10GB; over it a new `artifact-begin` is refused and nothing is deleted |
| `ledger.attachments.max-open-per-owner` | servers, CLI | how many uploads of one author may be open, default 4 |
| `ledger.attachments.disk-margin-bytes` | servers, CLI | the space that has to stay free on the disk of `directory` after a file is written, default 64MB (the size of the largest file): when `usable - size` is below it a begin or a chunk is refused with `ARTIFACT_STORAGE_FULL` and the owner has to free the disk |
| `ledger.attachments.upload-ttl` | servers, CLI | how long an upload lives after its last chunk, default 10m |
| `ledger.attachments.orphan-grace` | servers, CLI | how long a file without a row (or a replaced file) waits before `attachments-check` moves it to `orphans/`, default 24h |
| `ledger.attachments.reaper-interval` | MCP server | how often expired uploads are removed, default 1m |
| `ledger.secrets.home` | `ledger-cli` | the directory of the installation that holds the settings, the driver registration, the driver jars and the secret directory, default `/etc/pg-ledger-mcp` (the Windows installer gives its own) |
| `ledger.secrets.directory` | `ledger-cli` | the secret directory, default `/etc/pg-ledger-mcp/secrets` |
| `ledger.secrets.instance` | `ledger-cli` | the instance name that the default paths follow, default `pg-ledger-mcp` |
| `ledger.cli.driver.directory` | `ledger-cli` | the directory `driver-register` takes a jar from, default `/etc/pg-ledger-mcp/drivers` |
| `ledger.cli.driver.file` | `ledger-cli` | the registration file `driver-register` writes, default `/etc/pg-ledger-mcp/jdbc-driver.yaml` |

The servers listen on loopback only (MCP `127.0.0.1:8765`, admin web `127.0.0.1:8865`); publish them through a reverse proxy
that terminates HTTPS and list its public host name in the allowed hosts. The admin web trusts `X-Forwarded-For` and
`X-Forwarded-Proto` from the loopback address only. The servers read the driver registration file and the secret directory
through `spring.config.import`, which the installers put into the service definition (see Installation); `ledger-cli` reads them by default.

## Installation

Two installers cover three targets. A **personal PC** (Windows) runs `deploy\install.cmd` with its defaults: both servers listen on
the loopback address only, and there is no reverse proxy and no TLS. A **Windows server** runs the same script and, when it is
reached from outside, names its public host names and puts a reverse proxy that the operator has prepared in front of it. A **Linux
server** runs `sudo deploy/install.sh`, optionally with an nginx site. macOS is not supported; a Linux desktop that is a personal PC
follows the Linux procedure. The build needs JDK 25 and the host needs a Java 25 runtime at a path that every account can execute (not
under `/home`: the service account runs it too).

What every target has in common:

- **One service account.** The MCP server and the admin web run as one operating system account that belongs to no person: on
  Linux the system user `pg-ledger-mcp` (no login, not root), on Windows the predefined account LocalService (no password). Its purpose
  is to keep the secrets of the ledger, the admin token and the database password, away from the account of the person who works on
  the machine and from the agents that run under it: the secret directory is readable by the service account only. The two
  services of an installation, called an instance, are named after it: the instance is `pg-ledger-mcp` unless `--name=<name>` gives
  another name, and its services are `<name>` and `<name>-admin` (`pg-ledger-mcp` and `pg-ledger-mcp-admin` by default). The Linux user, the
  directories (`/opt/<name>`, `/etc/<name>`, on Windows `C:\ProgramData\<name>`) and the WinSW ids follow the name, so a second instance, or an
  installation next to a host that still runs 1.x under the default names, needs only its own `--name=<name>` and its own ports.
- **Two database accounts**, created beforehand with the tools of your DBMS (creating an account is not ANSI SQL and is not part of the
  product). The application account is used by both servers and by the owner commands of `ledger-cli`; `migrate` gives it the least
  privilege of `ledger-database/src/main/resources/db-templates/dml-grants.sql` (no DDL, no `UPDATE` or `DELETE` on the history). The
  DDL account is used only by `migrate` and `setup`; its password is typed with hidden input and is never stored. The
  session time zone of the database must be UTC, and the application account must be allowed to use the schema, a step of your DBMS
  that is not part of the product SQL: `setup` checks it and says so when it is missing (see First setup).
- **Jars and the driver.** Build the jars with `./gradlew assemble` (the jars only; `./gradlew build` also runs the tests, which need a PostgreSQL of their own, see Build and test) and collect the jars in one directory: the three jars of the host, and
  `ledger-migrate-1x.jar`, the tool that moves a 1.x installation (see Upgrading from 1.x). The installers use the three jars of the host only; they do not know the
  tool and do not copy it, because it is no part of the services. The JDBC driver of your database is obtained by you, and you name its class; it is not bundled.

```sh
mkdir dist
cp ledger-cli/build/libs/ledger-cli.jar ledger-mcp-server/build/libs/ledger-mcp-server.jar ledger-admin-web/build/libs/ledger-admin-web.jar ledger-migrate-1x/build/libs/ledger-migrate-1x.jar dist/
```

- **What an installer does.** The installers are thin: they start only what Java cannot do, and everything else is a command of
  `ledger-cli`. Java does the work on the files of the host: `render-deploy --check` validates every option first, and
  `prepare-host` creates the folders with their owners, modes and ACLs, copies the jars and the driver jar and writes the service
  definitions and the settings (`application.yaml` and one file per service in one directory; your own settings belong in
  `local.yaml` there, which is read last and never replaced). The installer then has the secrets stored by `ledger-cli`: the
  database password of the application account (hidden input), `ledger.admin.token` and `ledger.mcp.token` (generated, shown once,
  at the end of their step) and the copy `ledger.admin.agent-token`; it registers the driver, runs `setup` with the DDL account (the
  schema, the grants and the seed codes, see First setup) and starts the two services. What stays in the scripts, because Java cannot do it, is creating the operating system user (`useradd`),
  starting a command as that user (`runuser`), the service managers (`systemctl`, WinSW and `sc.exe`) and `nginx -t` with the reload.
  The Java 25 check is the JVM's own: the jars are compiled for Java 25, and no other runtime loads them. Nothing is downloaded or
  installed. Running it again with newer jars is the upgrade: files and services are replaced, the schema and the grants are applied
  again, and secrets, tokens and data are kept. A run that stopped half way is repaired the same way: run it again with the same
  options (the commands that store secrets do not need the database). The settings directory is `ledger.secrets.home` of the tool; the
  services read the same sources through `spring.config.import`.

The options of `deploy/install.sh` are given as `--name=value`; the ones it does not read itself go on to `render-deploy`:

| Option | Meaning |
|---|---|
| `--name=<name>` | the name of the instance, default `pg-ledger-mcp`: lower-case letters, digits and hyphens, at most 23 characters, not ending with `-admin`; Java validates it before anything is created |
| `--java=<path>` | the Java 25 runtime, default the `java` on the `PATH` of root; the service user runs it too, so it is at a path every account can execute (not under `/home`), which the script checks before it creates anything |
| `--jars=<dir>` | the directory that holds the three jars |
| `--driver-jar=<file>` | the JDBC driver jar |
| `--driver-class=<class>` | the driver class, which must accept the JDBC URL |
| `--owner-account=<account>` | the DDL account (its password is asked for) |
| `--app-account=<account>` | the application account |
| `--jdbc-url=<url>` | the JDBC URL of the ledger database (no password in it) |
| `--schema=<name>` | `ledger.schema.name`, optional |
| `--mcp-port=<n>` | the loopback port of the MCP server, default 8765 |
| `--admin-port=<n>` | the loopback port of the admin web, default 8865 |
| `--mcp-host=<host>[,<host>...]` | the public host name of the MCP server, or a comma list of several (each once, at most 10, lower case, no port); every name is allowed in its `Host` check and served by the nginx site. It can be given without `--admin-host` |
| `--admin-host=<host>[,<host>...]` | the public host names of the admin web, in the same form; optional: without it the admin web is reached on loopback only (its `Host` check allows `127.0.0.1` and `localhost`), and the nginx site has no block for it. With a public name, the installer also writes `ledger.admin.passkey.rp-id` (the first name) and `ledger.admin.passkey.origins` (`https://<name>` for the first name and every name below it) into `admin.yaml`, so the passkeys of a remote installation work without a hand edit; a name that is not below the first one is reachable but does not share its passkeys (set `rp-id` and `origins` in `local.yaml` for another arrangement) |
| `--nginx-conf=<path>` | Linux: write the nginx site to this file. The path must be one that the nginx of the host already includes in its `http` context (for example `/etc/nginx/conf.d/<name>.conf`, which the Debian, Ubuntu and RHEL-family packages include): the installer writes the site at that path and only runs `nginx -t` and a reload, and it links nothing into `sites-enabled`; needs `--mcp-host` and either `--tls-certificate=<path>` and `--tls-key=<path>` or `--tls-acme` |
| `--tls-certificate=<path>` | the TLS certificate of the operator; nothing issues or renews one |
| `--tls-key=<path>` | the TLS key of the operator |
| `--tls-acme` | a flag, instead of the certificate and the key of the operator: the certificate is made and renewed by `ledger-cli` (see Automatic certificate) |
| `--acme-directory=<url>` | with `--tls-acme`, optional: the ACME directory URL of the certificate authority; the default is the production directory of Let's Encrypt, `https://acme-v02.api.letsencrypt.org/directory` |
| `--acme-email=<address>` | with `--tls-acme`, optional: the contact address of the ACME account |
| `--agree-tos` | with `--tls-acme`: you have read the terms of service of the certificate authority and agree to them; the first order needs it |
| `--stdin` | read the passwords from standard input instead of the terminal: first the one of the application account (only while it is not stored), then the DDL one; the convention of `docker login --password-stdin` |

`render-deploy`, `prepare-host` and `seal-host` take five more options that the installers set themselves: `--platform=<platform>`
(`linux`, the default, or `windows`), `--home=<path>` (Windows: the installation directory), `--java=<path>` (the Java executable),
`--root=<directory>` (put the files below a directory instead of at their paths, for a look before they are put in place) and, for
`render-deploy` only, the flag `--check` (validate every option and change nothing; the installers run it first, so that a bad value,
the name included, stops them while nothing exists yet). `prepare-host` also takes `--jars=<dir>`, `--driver-jar=<file>` and, on
Windows, `--winsw=<file>`. Without `--root` the platform has to be the platform of the host that runs the command: the paths of the
other one are no paths of this host and would end up below the working directory, so the commands refuse them (naming `--platform`)
before anything is read or written.

### Linux server

Run as root; the user `pg-ledger-mcp` is created when it is missing, `prepare-host` (Java) creates `/opt/<name>` (root, 0755),
`/etc/<name>` (the service user, 0750), its `drivers` folder (root, 0755) and its `secrets` folder (the service user, 0700), copies the
jars and the driver and renders the units into `/etc/systemd/system`, which are then enabled; the secrets are stored by the service
user. A file that the installer did not render and that a rendered file replaces is kept as `<file>.backup`; to move a 1.x installation,
see Upgrading from 1.x.

```sh
sudo deploy/install.sh --jars=dist --driver-jar=<driver-jar> --driver-class=<driver-class> --owner-account=<ddl-account> --app-account=<app-account> --jdbc-url=<jdbc-url>
```

With a public name and TLS, add the host names and the paths of your certificate and key; the nginx site overwrites
`X-Forwarded-For` with the address of the connection, which the admin web trusts from the loopback proxy only:

```sh
sudo deploy/install.sh --jars=dist --driver-jar=<driver-jar> --driver-class=<driver-class> --owner-account=<ddl-account> --app-account=<app-account> --jdbc-url=<jdbc-url> --mcp-host=<mcp-host> --admin-host=<admin-host> --nginx-conf=/etc/nginx/conf.d/pg-ledger-mcp.conf --tls-certificate=<certificate> --tls-key=<key>
```

### Automatic certificate

With `--tls-acme` in place of `--tls-certificate` and `--tls-key`, `ledger-cli` makes and renews the certificate itself. It is a
standard ACME client (RFC 8555) built on the library acme4j (Apache-2.0; it brings jose4j, Apache-2.0, and Bouncy Castle, under the
Bouncy Castle license, an MIT-style license, which acme4j uses for the certificate request). The certificate authority is any ACME
directory: `--acme-directory=<url>`, by default the production directory of Let's Encrypt. The proof is HTTP-01 only, so no
interface of a DNS or CDN vendor is involved. The result is `fullchain.pem` and `privkey.pem` in `/etc/<name>/tls`, next to the
account key `account.key`: standard PEM files that any other proxy can use as well. The keys are made with the JDK (EC P-256),
stored so that only the service user can read them, never printed, and the files are replaced atomically, so a failed renewal
leaves the old certificate in place.

What the operator does, in this order:

1. Create the DNS records (`A` and `AAAA`) of the host names, `--mcp-host` and `--admin-host` (every name of a list; the admin web needs
   one only when it has a public name), that point to the server.
2. Allow the ports `80` and `443` in the firewall. Port `80` must reach nginx: the certificate authority fetches the proof from
   there over HTTP.
3. If a CDN or another proxy stands in front of the server, it has to pass the path `/.well-known/acme-challenge/` through to the
   server on port `80` and must neither cache it nor redirect it. Long-lived MCP connections (Streamable HTTP) need a timeout of
   the proxy that is long enough; the nginx site itself sets `proxy_buffering off` and `proxy_read_timeout 3600s` for the MCP host.
4. Read the terms of service of the certificate authority and give `--agree-tos`; `--acme-email=<address>` is an optional contact.

```sh
sudo deploy/install.sh --jars=dist --driver-jar=<driver-jar> --driver-class=<driver-class> --owner-account=<ddl-account> --app-account=<app-account> --jdbc-url=<jdbc-url> --mcp-host=<mcp-host> --admin-host=<admin-host> --nginx-conf=/etc/nginx/conf.d/pg-ledger-mcp.conf --tls-acme --agree-tos
```

The nginx site holds only the `server` blocks of its own host names (the names of the MCP server in one block, the names of the admin web in another when it has any). It never takes `default_server` and changes no other
site, so it can live next to other services of the host. For each host name a port 80 block serves `/.well-known/acme-challenge/`
from the web root `/var/www/<name>-acme` and redirects everything else to https; the 443 blocks use the PEM files.

Port 80 is open to the whole network, so the port 80 blocks are hardened. A port 80 block serves a file of the web root only for a path
`/.well-known/acme-challenge/<token>` whose token consists of the characters of base64url (`A-Za-z0-9_-`), and only for the methods `GET` and
`HEAD`. It answers every other request below `/.well-known/acme-challenge/` with 404 and redirects everything else to https at the fixed
host name of the block, never to the `Host` header of the request. It accepts `1k` of request body and waits `10s` for the header, the
body and an idle connection. It allows each address 10 requests per second (a burst of 20) and 20 connections at the same time and
answers `429` above that; the limits are far above what a certificate authority needs, because it checks from several places at the
same time, each from its own address. It hides the version of nginx (`server_tokens off`, in the 443 blocks too). The two shared memory
zones that count the addresses are declared at the top of the site file and carry the name of the instance (`<name>-acme-req` and
`<name>-acme-conn`), so no other site and no other instance is affected. A port 80 block does not take `default_server`, does not
change any other site, sets no global directive and proxies nothing to the services.

Recommendation: when this is the only site on the host that uses port 80, add a default server of your own that closes the requests for
names and addresses that nobody serves there, for example the following. The product does not create it.

```nginx
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;
    return 444;
}
```

First issuance: nginx cannot load a 443 block before its certificate files exist, so `render-deploy` writes the 443 blocks only
when `fullchain.pem` and `privkey.pem` exist. The installer therefore writes the site with the port 80 blocks, lets nginx load it,
runs `tls-issue` as the service user, runs `render-deploy` again (now with the 443 blocks), lets nginx load the site again and
enables the timer. Running the installer again asks `tls-renew` instead, which orders only what is due.

Renewal: `render-deploy --tls-acme` writes the systemd service `<name>-tls-renew.service` and the timer
`<name>-tls-renew.timer`. The timer runs twice a day, at a random time. `tls-renew` orders a new certificate only when the present
one expires within 30 days (`--renew-before-days=<n>`) or does not name exactly the host names given, and otherwise does nothing and
says so. The service runs as the service user without any capability, may write only the TLS directory and the web root, and
reloads nginx afterwards with `ExecStartPost=+/usr/bin/systemctl reload nginx`. That is one fixed command line that systemd runs with
full privileges outside the sandbox (the `+` prefix of systemd.service(5)), because only root may reload nginx. Nothing from the
service user reaches it, and no `sudo` rule and no polkit rule has to be kept; the process that talks to the network and writes the
keys keeps no privilege.

Automatic certificates are for Linux only, because the nginx site is: the installer for Windows has no web server of its own (a
Windows server is reached through a reverse proxy that you prepare). The commands themselves run on any host: with `--webroot=<dir>`
and `--tls-dir=<dir>` they write the proof where the web server of your choice serves it and the PEM files where your proxy reads
them, and `--name=<name>` selects the instance whose directories are the defaults.

### Windows: a personal PC or a server

Run in an elevated prompt. The script takes its arguments in this order, and the services are registered with WinSW, the way
Spring Boot's documentation describes for Windows services; you obtain the WinSW 2.x executable yourself, as you do the JDBC driver,
and the installer neither bundles nor downloads it.

| Argument | Meaning |
|---|---|
| `[--stdin]` | optional, and only as the first argument: read the passwords from standard input instead of the terminal (first the application account, only while its password is not stored, then the DDL account), as `install.sh --stdin` does; the other arguments keep their order |
| `<jars-dir>` | the directory that holds the three jars |
| `<winsw-exe>` | the WinSW 2.x executable |
| `<driver-jar>` | the JDBC driver jar |
| `<driver-class>` | the driver class |
| `<ddl-account>` | the DDL account |
| `<app-account>` | the application account |
| `"<jdbc-url>"` | the JDBC URL, in quotes |
| `"<name>"` | optional, in quotes: the name of the instance, default `pg-ledger-mcp` |
| `"<more options>"` | optional, in quotes, after the name: more options of `render-deploy`, for example the host names of a server |

```bat
deploy\install.cmd dist <winsw-exe> <driver-jar> <driver-class> <ddl-account> <app-account> "<jdbc-url>"
```

A Windows server that is reached from outside adds its host names and puts a reverse proxy that you have prepared in front of the two
loopback ports (the installer writes no proxy site on Windows):

```bat
deploy\install.cmd dist <winsw-exe> <driver-jar> <driver-class> <ddl-account> <app-account> "<jdbc-url>" "pg-ledger-mcp" "--mcp-host=<mcp-host> --admin-host=<admin-host>"
```

Everything lives in `C:\ProgramData\<name>`. Both services run as LocalService, restart after a failure (the WinSW
configuration), and write their logs to the `logs` folder. `prepare-host` closes the folders to every account but SYSTEM and
Administrators; `seal-host` hands the secrets over to LocalService at the end of the installation (Java, with ACLs, no `icacls`): the
installing user can only see that a file exists, and `prepare-host` takes them back for the duration of a re-run. So the
owner commands of `ledger-cli` cannot read them afterwards: do the owner work (projects, common codes, grants, keys) on the pages of
the admin web (see Admin web: sign in with a passkey or OpenID Connect, and register the first passkey with `passkey-code`). On a
personal PC open the admin web at `http://localhost:8865` (the port is `--admin-port=<n>`): the admin web sets a `Secure` session
cookie, which the major browsers accept from `localhost` because they treat it as a secure context, and a passkey works on `localhost`
without HTTPS.

### First setup

The installers call `setup`, and you run it yourself after an upgrade or against a database that you prepared by hand. A new ledger
has to start with some data, so `ledger-cli` carries a seed bundle: the file `ledger-seed.mv.db`, which the build writes with a Java
program from the text files `src/seed/defect-categories.tsv` and `src/seed/install-defaults.properties` of the module `ledger-cli`
and packs into `ledger-cli.jar`. It is never committed. The bundle holds the 14 default defect categories (code, Korean and English
name and description) and the defaults of an installation (the instance name and the two ports); it holds no secret. The file has the
format of the H2 database, and that is all H2 is here: the ledger is always kept by the database system that you install, and the
registration of an H2 driver, or of a JDBC URL of H2, is refused by `driver-register` and by the driver page of the admin web.

`setup [--owner-account=<account>] [--app-account=<account>] [--stdin]` takes the options of `migrate` and does four things in this
order:

1. It migrates the schema exactly as `migrate` does, and applies the grants of the application account.
2. It checks, over a connection of the application account, that this account can use the schema. The privilege to use a schema is a
   statement of your database system and is not part of the product SQL, so when it is missing `setup` stops with exit code `3`, names
   the account and says that an operator has to give it that privilege with the tools of the database system. Nothing else has been
   changed at that point; run `setup` again after that step and it continues where it stopped.
3. It adds the codes of the seed that the ledger does not have yet, through the service of `code-set` and of the admin web, so that
   the code revision and the owner's audit trail are kept. A code that exists is never changed: a code that you renamed or retired
   stays as you left it.
4. It compares the seed with the database by the number of codes and by a SHA-256 digest, and prints the report. Running `setup`
   again changes nothing and prints the same report; a code of yours that differs from the seed is counted as changed and is kept.

The defaults of an installation are written in one place, the seed bundle. `render-deploy`, `prepare-host` and `seal-host` take the name
and the ports from it when an option is not given, and the installers ask for them with `install-default <name>` (`instance-name`,
`mcp-port` or `admin-port`), so no script holds a second copy.

### After the installation

1. Check the MCP server with `ledger-cli mcp-check`; without a registered driver the MCP server stops and says why, and the admin web
   starts in a setup mode that offers only the login page, which tells you to register the driver with `ledger-cli driver-register`
   (without a database there is no passkey to sign in with, so the driver page of the admin web is a console page).
2. Set up the owner side: `project-add` and `code-set`; when you come from 1.x, see Upgrading from 1.x instead of creating projects by hand. On Linux run
   `ledger-cli` as the service user, for example `sudo -u <name> java -jar /opt/<name>/ledger-cli.jar --ledger.secrets.instance=<name> project-add ...`.
3. Install the client hooks (see Client hooks), register the key of each session with `key-register` and issue the grants with `grant`.

`migrate` applies the files `V{n}__*.sql` in order and records every attempt, with the SHA-256 of the file, in the table
`schema_history`: one row for each attempt of a version, with the columns `version`, `attempt`, `script`, `digest`, `applied_at` and
`outcome` (`SUCCEEDED` or `FAILED`). An attempt is one transaction. It starts by inserting its own row as `FAILED` and ends by turning that
one row to `SUCCEEDED`; if the file fails, the attempt is rolled back, the row stays `FAILED`, and the next run tries the version again as
the next attempt without changing or deleting the earlier rows. Two `migrate` runs at the same time are safe: the second waits in the
database for the first and then skips what the first has applied. A file that was changed after it was applied stops `migrate` before it
applies anything, and every process at start; no row is added for it. The database must use the encoding UTF-8, which `migrate` proves with a probe query of the dialect before it changes anything, and the long text type of
the schema files is decided by the database dialect of the ledger, which `migrate` takes from the product name of the connection (there is
no setting for it). Each declaration is taken from the official documentation of the database it is for, and where a database sets a small
default limit when the length is left out, the declaration carries a length. The type information of the JDBC driver is not used, because a
driver may report it in a form that cannot be judged.

The ledger does not use Flyway: a current Flyway release needs a module for each DBMS and refuses a database it has none for, while the
ledger asks for ANSI SQL on any database whose driver you register. And the ledger migrates by this explicit command and not when a server
starts, because the servers never hold the password of the DDL account: a server only checks the schema at start and, when files are
pending, refuses to start and names `ledger-cli migrate`, so a server that is taken over cannot change the schema.

## ledger-cli

`java -jar ledger-cli.jar <command> [arguments]`. An option is written `--name=value`. A Spring property (a name with a dot, such as
`--ledger.jdbc.url=...`) is passed the same way. The tool runs as the user that owns the secret directory (on Linux the
service user), and every owner operation is recorded with that user.

| Command | Syntax | What |
|---|---|---|
| `secret-set` | `secret-set <name> [--stdin] [--if-absent]` | store a secret from hidden input, or with `--stdin` from the first line of standard input (never from an argument or the environment); `--if-absent` keeps an existing secret and asks for nothing |
| `driver-register` | `driver-register --jar=<path> --class=<driver-class>` | register the JDBC driver; needs no database |
| `token-generate` | `token-generate <name> [--print] [--copy-to=<name>]` | store a random token (32 bytes from `SecureRandom`) as a secret; an existing secret is kept; `--print` shows a token that was just created, once; `--copy-to` stores the same token under a second name, once |
| `migrate` | `migrate [--owner-account=<account>] [--app-account=<account>] [--stdin]` | apply the pending schema files, as the DDL account when `--owner-account` is given (its password is typed, or with `--stdin` read from standard input, and not stored), and then the grants of `dml-grants.sql` to the application account |
| `setup` | `setup [--owner-account=<account>] [--app-account=<account>] [--stdin]` | the first setup: migrate as `migrate` does, check that the application account can use the schema (exit code `3` and the missing step when it cannot), add the seed codes that are missing (a code that exists is never changed) and compare the seed with the database by counts and digest; running it again changes nothing |
| `key-register` | `key-register <author> <public-key-or-block> <fingerprint> [--replace=<current-fingerprint>]` | bind the public key a session printed to its author; a live key is replaced only with `--replace` |
| `key-revoke` | `key-revoke <author> --fingerprint=<fingerprint>` | close the live key of an author |
| `passkey-code` | `passkey-code [--remove-existing]` | issue the one-time code that enrols a passkey of the owner in the admin web (shown once, valid 15 minutes, stored only as its hash; a new code ends the earlier unused one); `--remove-existing` closes every live passkey first, for an owner who lost them all |
| `project-add` | `project-add <id> <code> <title>` | add a project |
| `code-set` | `code-set <group> <code> <name-ko> [<description-ko>] [--name-en=<text>] [--description-en=<text>] [--sort=<n>] [--inactive] [--direction=<direction>]` | add or update a common code; `--direction` (`GIVEN` or `CANONICAL`) is for the group `RELATION_KIND` (see the `ledger_link` tool) |
| `codes` | `codes [<group>]` | list the common codes |
| `grant` | `grant <author> <project> <scope>... [--note=<text>] [--expires=<ISO-8601 with Z or an offset>]` | grant scopes on a project; the author needs a live session key first |
| `revoke` | `revoke <author> [<project> [<scope>]]` | revoke live grants |
| `grants` | `grants [<author>]` | list live grants |
| `attachments-check` | `attachments-check [--purge-orphans]` | check the attachments against their files and keys: a file that no row names, and a file that `attachments-rekey` replaced, is moved to `orphans/` and never deleted; a file in `orphans/` whose row exists again is moved back; the report gives the counts and the ids (never a storage key or a path) of the attachments that are missing, damaged or pointed to by no history row, and the exit code is `1` when one is missing or damaged; `--purge-orphans` first empties `orphans/` and records the number in the audit trail |
| `attachments-rekey` | `attachments-rekey --from=<keyId>` | write the attachment files of the key `<keyId>` again under the active key `ledger.attachments.active-key-id` (a different key); the old files are moved to `orphans/` by `attachments-check` after `ledger.attachments.orphan-grace` |
| `attachments-key-create` | `attachments-key-create` | create a new 256-bit key file in the key directory (readable by the service account only) and print its key id, never the key; set `ledger.attachments.active-key-id` to it to write new uploads with it |
| `mcp-check` | `mcp-check [--url=<url>] [--ledger.mcp.token=<token>]` | connect to a running MCP server and check its tools |
| `render-deploy` | `render-deploy --java=<path> --jdbc-url=<url> --app-account=<account> [--name=<name>] [--platform=<platform>] [--home=<path>] [--schema=<name>] [--mcp-port=<n>] [--admin-port=<n>] [--mcp-host=<host>[,<host>...]] [--admin-host=<host>[,<host>...]] [--nginx-conf=<path> (--tls-certificate=<path> --tls-key=<path> | --tls-acme [--acme-directory=<url>])] [--root=<directory>] [--check]` | write the service definitions (systemd units, or WinSW configurations on Windows), the settings and the optional nginx site; the installers call it |
| `prepare-host` | `prepare-host --jars=<dir> --driver-jar=<file> [--winsw=<file>] --java=<path> --jdbc-url=<url> --app-account=<account> [--name=<name>] [--platform=<platform>] [--home=<path>] [--schema=<name>] [--mcp-port=<n>] [--admin-port=<n>] [--mcp-host=<host>[,<host>...]] [--admin-host=<host>[,<host>...]] [--nginx-conf=<path> (--tls-certificate=<path> --tls-key=<path> | --tls-acme [--acme-directory=<url>])] [--root=<directory>]` | prepare the files of the host (run as root, or elevated on Windows): the folders with owner and mode, or their ACLs, the copies of the jars and the driver (on Windows also of WinSW), and what `render-deploy` writes; the installers call it |
| `seal-host` | `seal-host --java=<path> --jdbc-url=<url> --app-account=<account> [--name=<name>] [--platform=<platform>] [--home=<path>] [--schema=<name>] [--mcp-port=<n>] [--admin-port=<n>] [--mcp-host=<host>[,<host>...]] [--admin-host=<host>[,<host>...]] [--nginx-conf=<path> (--tls-certificate=<path> --tls-key=<path> | --tls-acme [--acme-directory=<url>])] [--root=<directory>]` | Windows, last: give LocalService its access and hand the secrets over to it; the installer calls it |
| `install-default` | `install-default <name>` | print one default of the installation from the seed bundle (`instance-name`, `mcp-port` or `admin-port`); the installers ask for their defaults with it |
| `tls-issue` | `tls-issue --mcp-host=<host>[,<host>...] [--admin-host=<host>[,<host>...]] [--name=<name>] [--webroot=<dir>] [--tls-dir=<dir>] [--acme-directory=<url>] [--acme-email=<address>] [--agree-tos]` | order a certificate for the host names given from a certificate authority that speaks ACME and write `fullchain.pem` and `privkey.pem`; the first run creates the account and needs `--agree-tos`; see Automatic certificate |
| `tls-renew` | `tls-renew --mcp-host=<host>[,<host>...] [--admin-host=<host>[,<host>...]] [--name=<name>] [--webroot=<dir>] [--tls-dir=<dir>] [--acme-directory=<url>] [--renew-before-days=<n>]` | order a new certificate when the present one expires within `--renew-before-days` (default 30) or does not name exactly the host names given, and otherwise do nothing; the timer of `--tls-acme` runs it |

## Admin web

The admin web (`ledger-admin-web.jar`) is a server-rendered console for the owner, on `127.0.0.1:8865` by default. It runs as
the service account of the installation and never as root.

- **Sign-in, one of three** (`ledger.admin.sign-in`). With `passkey` (the default of a new installation) the owner signs in with a
  passkey. With `token` (the personal edition, one person on their own computer or server) the owner types the admin token
  `ledger.admin.token` into the login page of the application. With `oidc` the owner signs in with OpenID Connect, and the owner is **one e-mail address**
  (`ledger.admin.oidc.owner-email`, verified by the provider at `ledger.admin.oidc.issuer`); another address, or one the provider did not
  verify, is refused. Only one of the three is offered: with `oidc` or `passkey` the token form is absent and a posted token
  finds no sign-in. The admin token `ledger.admin.token` also stays the bearer of the read-only JSON endpoints. Whichever sign-in
  is chosen, a change is approved by a passkey, so a session opened with the token registers its first passkey with the one-time
  code of `passkey-code`. The agents' token is refused, so an agent that holds the MCP token cannot grant itself
  anything. Failed attempts (passkey, one-time code, token) lock the client and slow down all clients (`ledger.admin.lockout.*`).
- **Approval of every change by a passkey, whichever way you signed in.** Every change you make in the console (grants, session
  keys, common codes, projects, the driver, closing a passkey) is approved with a passkey, with user verification: the page asks the
  server for a challenge made from that very change and your session, the authenticator signs it, and the write goes with the
  signature. A signature is good for that one change, once, for two minutes; one for another change or another session is refused.
  Reading needs no approval. The commands of `ledger-cli` on the server are the owner's shell and need none.
- **First passkey, another one, or a reset.** Run `ledger-cli passkey-code` on the server as the service account: it prints a
  one-time code (shown once, valid 15 minutes, stored only as its hash). On the login page type it under "Register a passkey"; that
  signs in an enrolment session (a quarter of an hour, it may register a passkey and nothing else), where "Register" creates the
  passkey. `passkey-code --remove-existing` closes every live passkey first, for an owner who lost them all; without the flag no
  passkey is touched. Each code and each reset is recorded in the audit trail of the owner. The passkey is bound to the host name
  of the admin web (`ledger.admin.passkey.rp-id`), HTTPS is required except on `localhost`, and a wrong host or origin is refused with
  `PASSKEY_HOST_MISMATCH`.
- **How it is built.** The ceremonies are Spring Security's WebAuthn support (`spring-security-webauthn` with WebAuthn4J, both
  Apache-2.0); the passkeys are kept in the ledger database (V7: base64url text, no BLOB, a credential is never changed or deleted: closing it
  is a row of its own).
- **Browser hardening.** Spring Security provides CSRF tokens, a `SameSite=Strict` session cookie that is `Secure` by default, the
  `Host` allow list and a Content Security Policy.
- **Pages.** Overview (attention counts and the state of the MCP server), projects, defects, tasks, conditions and history
  (read only), common codes, grants with their session keys, and the JDBC driver page. Times are sent as UTC and shown in the local
  time of the browser.
- **Grants.** The owner pastes the public key a session printed, compares the fingerprint shown with the one the session showed, and
  only then the key is registered and the grants are issued. A live key is replaced only on an explicit confirmation of that exact key.

## Client hooks

One executable jar, `ledger-client-hook.jar`, serves all clients; the first argument selects the client:
`java -jar ledger-client-hook.jar claude`, `codex` or `antigravity`. The plugin directories are in `clients/claude`,
`clients/codex` and `clients/antigravity` (see the README of each). The hook needs a Java 25 runtime on the `PATH` of the client
host; with no hook, or a failing hook, a write is refused with `HOST_ATTESTATION_REQUIRED`.

The hook signs only the ten write tools, named `mcp__<server>__ledger_<op>` or `mcp__<server>__<server>_ledger_<op>`
(Codex Apps: `mcp__codex_apps__app_<app>_ledger_<op>`). The value of `_hostAttestation` is
`pgla2.<author>.<tool>.<request id>.<arguments digest>.<issued at>.<signature>`: an Ed25519 signature over the author, the tool, the
request id, the SHA-256 of the RFC 8785 canonical form of the arguments (without `_hostAttestation`; a batch: every operation, its
content, its order and its request id) and the time. A captured token cannot carry other content. `pgla1` tokens are refused with
`ATTESTATION_VERSION_UNSUPPORTED`.

- The hook keeps one Ed25519 key per MCP server name and author in an owner-only folder: `~/.pg-ledger-mcp/session-keys`, or the
  absolute path in `LEDGER_HOOK_KEY_DIR`. A signature made for one server never verifies on another. For Codex Apps the server name
  is `codex_apps.<app>`.
- `java -jar ledger-client-hook.jar session-key <server> <author>` prints the server, the author, the public key and its fingerprint.
  The owner compares the fingerprint and registers the key on that server with `ledger-cli key-register` or on the grants page.
- The server verifies the signature, the tool, the request id, the arguments and the time (`ledger.attestation.window`) before it
  looks at any grant. A missing key and a wrong signature are the same refusal, `ATTESTATION_SIGNATURE_INVALID`; a different
  content is `ATTESTATION_BINDING_MISMATCH`.
- Limits: another process of the same operating system user can read the key file, so isolation inside one user needs separate
  accounts. The hook creates the folder and the key file owner-only and reads an existing one whoever may reach it or owns it (a
  link is followed): the personal edition trusts the whole PC. A directory where the key file belongs, and an empty key file, are refused.

## Upgrading from 1.x

1.x was a DBHub and Node implementation on PostgreSQL functions; 2.0.0 replaces all of it. Existing data is moved, not converted in
place. The 1.x schema stays and can be used to go back. See the [release notes](docs/release-notes/2.0.0.md) for the contract changes.

Version 2.0 itself knows nothing of 1.x: its services, its installers and `ledger-cli` read no 1.x setting, file or table. The move is
done by a separate tool, the module `ledger-migrate-1x`. The build makes it as `ledger-migrate-1x/build/libs/ledger-migrate-1x.jar`, and
it is kept in the distribution folder `dist` next to the other jars (the `cp` step of Installation). The installers do not know it and
do not copy it, because it is no part of the services. It is run from there with the Java 25 runtime of the installation, as
`java -jar dist/ledger-migrate-1x.jar <command> [options]`. The folder `dist` has to be readable by the service user (not under `/home`), because the
commands that use the secret store run as that user. The tool reads the settings of the installation the way the services do (the settings
directory `ledger.secrets.home`, with `application.yaml`, `local.yaml`, the driver registration and the secret directory); for an
instance with another name give `--ledger.secrets.instance=<name>`. A command that needs the ledger database needs the registered JDBC
driver; `stop-1x` and `import-secrets-1x` need none.

| Migration command | Syntax | What |
|---|---|---|
| `stop-1x` | `stop-1x [--name=<instance>] [--platform=<platform>] [--unit-dir=<dir>] [--home=<dir>] [--env=<file> [--mcp-host=<host>[,<host>...]] [--admin-host=<host>[,<host>...]]]` | first step: stop and disable the two services of 1.x, `<name>` and `<name>-admin`, and keep each definition as a backup next to it (`<file>.1x-backup`); with `--env` it first checks the public host names of 1.x |
| `import-secrets-1x` | `import-secrets-1x --env=<file> [--replace]` or `import-secrets-1x --stdin [--replace]` | after the installer: move the secrets of 1.x into the secret store of 2.0; with `--replace` they replace the tokens that the installer generated |
| `import-1x` | `import-1x [--verify-only] [--owner-account=<account>] [--stdin]` | copy the 1.x ledger into the new schema in one transaction and compare every table by row count and SHA-256; `--verify-only` repeats only the comparison |
| `retire-1x` | `retire-1x [--confirm=<sha256> --backup-tested]` | after you accept 2.0, remove what the 1.x installer made in the database; without options it lists it and changes nothing |

The order of an upgrade is: back up, `stop-1x`, prepare the database accounts, install 2.0 with the installer, `import-secrets-1x`, restart
the services, `import-1x`, check, and, only after you accept 2.0, `retire-1x`. The 1.x service stays on the 1.x database until it is stopped, and
the installer of 2.0 stops nothing of 1.x. Do the steps in this order.

1. **Back up.** Dump the ledger database with the dump tool of your DBMS, and copy the 1.x settings directory `/etc/pg-ledger-mcp`
   (the file `/etc/pg-ledger-mcp/env` holds the 1.x tokens and passwords) and the 1.x unit files in `/etc/systemd/system`.
2. **Stop the 1.x services with `stop-1x`.** Run it first, as root, on the host of 1.x. Given the 1.x environment file and the host options
   (the same ones that you will give the installer of 2.0 in step 4), it checks the public host names of 1.x before it stops anything:

   ```sh
   sudo java -jar dist/ledger-migrate-1x.jar stop-1x --env=/etc/pg-ledger-mcp/env --mcp-host=<mcp-host> --admin-host=<admin-host>
   ```

   - **What it changes.** It stops and disables the two services of 1.x, `<name>` (the MCP server) and `<name>-admin` (the admin web, which
     1.0.0 did not have); `<name>` is `--name=<instance>`, default `pg-ledger-mcp`. It keeps the definition of each next to it as a backup: the
     unit file `<unit-dir>/<service>.service` is moved to `<service>.service.1x-backup`; the directory is `--unit-dir=<dir>`, default
     `/etc/systemd/system`. 1.x had Linux (systemd) installations only, so there is nothing to stop on Windows: `--platform=windows` is refused
     with `NO_1X_SERVICE_WINDOWS`, and a Windows service of that name is a service of 2.0 or of the system, which the tool never touches.
     The 1.x database and the 1.x environment file are not touched.
   - **What it reads.** The definitions of the two services and, with `--env=<file>`, the host names that the 1.x environment file allows.
   - **Why it starts a process.** Java has no API for the service manager of the host, so the command runs `systemctl`
     with fixed arguments (`stop`, `disable` and `daemon-reload`) and the validated name, in one place of its code.
     It touches only a service called `<name>` or `<name>-admin` whose definition is the one of 1.x: a unit of 1.x has an `EnvironmentFile=`
     line that names `/etc/<name>/env`.
     Every service is checked before the first change; otherwise the command stops with `NOT_1X_SERVICE` and has changed nothing.
   - **The public host names of 1.x.** The clients reach the 1.x server through the host names that the 1.x setting `DBHUB_ALLOWED_HOSTS`
     allows (the admin web of 1.1.0: `PGLEDGER_ADMIN_ALLOWED_HOSTS`), for example the name of your reverse proxy. The `Host` check of 2.0 allows
     `127.0.0.1`, `localhost` and the names of `--mcp-host` and `--admin-host` only, so a name that is missing would make every client request
     fail with HTTP 403 right after the migration. With `--env=<file>` the command therefore refuses, before it stops anything, when the file
     allows a public host name that `--mcp-host` (or `--admin-host`) does not carry: it stops with `HOSTS_DROPPED` and exit code 2, names the host
     names and the option, and has changed nothing. Add the names to the option, one name or a comma list (`--mcp-host=<name>[,<name>...]`;
     `--mcp-host` can be given without `--admin-host`), and give the same options to the installer. The loopback names need no option, and
     nothing else of the file is printed. A name that the clients no longer use is removed from a copy of the file, and the copy is given to
     `--env`. The two host options without `--env` are refused.
   - **Run it again** when it stopped half way: a service that was stopped before is reported as such, a backup file that holds other bytes
     than the definition is never replaced (`BACKUP_DIFFERS`), and a failed call of the service manager (`SERVICE_COMMAND_FAILED`) leaves what
     was done and the next run continues. The clients of 1.x are refused from now on, until the server of 2.0 runs. To keep 1.x running
     next to the new installation instead, do not run `stop-1x`, and give the new one its own `--name=<name>` and its own ports.
3. **Create the two database accounts** of the new installation with the tools of your DBMS (see Installation: the application account and
   the DDL account); the 1.x accounts stay as they are. When the new schema lives in the database of the 1.x ledger, set
   `ledger.schema.name` to a schema other than the 1.x schema `ledger`.
4. **Install 2.0** with the installer of your host (see Installation). The installer knows nothing of 1.x; give it the host names that
   you gave `stop-1x`:

   ```sh
   sudo deploy/install.sh --jars=dist --driver-jar=<driver-jar> --driver-class=<driver-class> --owner-account=<ddl-account> --app-account=<app-account> --jdbc-url=<jdbc-url> --mcp-host=<mcp-host> --admin-host=<admin-host>
   ```

   The installer generates tokens of its own (each shown once) and starts the two services with them.
5. **Move the secrets of 1.x with `import-secrets-1x`.** The secret store belongs to the service account, which the installer creates, so
   this runs after the installer. The 1.x file is owned by root and the secret store by the service user, so root opens the file and pipes
   it to the tool, which runs as the service user:

   ```sh
   sudo sh -c 'runuser -u pg-ledger-mcp -- java -jar dist/ledger-migrate-1x.jar import-secrets-1x --replace --stdin < /etc/pg-ledger-mcp/env'
   ```

   - **What it reads.** The 1.x environment file, given with `--stdin` (as above) or with `--env=<file>` for an operator who can read the file as the
     service user; exactly one of the two. Only the two tokens are read, and all values are checked before anything is written (at least 16 characters, and the admin token
     differs from the agent token). It uses no database.
   - **What it changes.** The installer has generated tokens of its own already, so without `--replace` a secret that exists is kept. With `--replace`
     the three secrets take the 1.x values: `DBHUB_AUTH_TOKEN` of 1.x becomes `ledger.mcp.token` and `ledger.admin.agent-token`, and `PGLEDGER_ADMIN_TOKEN` of 1.x becomes
     `ledger.admin.token`. The clients of 1.x keep working, and the admin token stays the bearer of the read-only endpoints (it signs a
     browser in only with `ledger.admin.sign-in=token`). A 1.0.0 installation had no admin web and so no `PGLEDGER_ADMIN_TOKEN`: then only
     the two tokens of the agents are replaced, the command says so, and the `ledger.admin.token` of the installer stays. Only the names of the
     secrets are printed, never a value.
   - For an instance with another name the service user is that name (`runuser -u <name>`), and the tool takes `--ledger.secrets.instance=<name>`.

   The services read their secrets at start, so restart the two services of 2.0:

   ```sh
   sudo systemctl restart pg-ledger-mcp pg-ledger-mcp-admin
   ```

6. **Import the ledger with `import-1x`.** Store the password of a read-only account of the 1.x database as the service user, then run the import
   as the DDL account; the settings of the 1.x source are described in the table below:

   ```sh
   sudo runuser -u pg-ledger-mcp -- java -jar dist/ledger-cli.jar secret-set ledger.import.source.password
   sudo runuser -u pg-ledger-mcp -- java -jar dist/ledger-migrate-1x.jar import-1x --owner-account=<ddl-account> --ledger.import.source.url=<jdbc-url> --ledger.import.source.username=<account>
   ```

   The import writes the whole ledger, which neither application account may do, so `--owner-account=<account>` runs it as the DDL account, whose
   password is typed with hidden input and is not stored (`--stdin` takes it from the first line of standard input instead and needs `--owner-account`).
   It reads the 1.x schema read only and writes the new schema in one transaction; see What the import does below.
7. **Register the session keys.** The grants of 1.x are copied without a session key. Every author registers a session key with
   `ledger-cli key-register`; then its grants work.
8. **Check.** Run `ledger-cli mcp-check --url=<its public URL>` and `import-1x --verify-only` (it needs the settings of the 1.x source and
   changes nothing), and run `ledger-cli passkey-code` and register your first passkey in the admin web (the admin token of 1.x signs a
   browser in only with `ledger.admin.sign-in=token`; the default is `passkey`). Clients: the tools `execute_sql_reader` and `search_objects_reader` were removed; the read tools replace them. The results of the write
   tools keep their keys without the `rows[0].result` wrapping. Install the hook jar (a Java 25 runtime) on every client host before the server is switched.
   When the checks have passed, the read-only account of the 1.x database that was made for the import may be dropped.
9. **Roll back** if the check fails: stop the services of 2.0, put each `<unit>.1x-backup` back under its own name in place of the unit of 2.0
   (copy it, so that the backup stays), run `sudo systemctl daemon-reload`, and run `sudo systemctl enable --now <name>` and the same for
   `<name>-admin` to start the 1.x services with their previous configuration. `stop-1x` prints these steps. The 1.x database
   was only read, so 1.x continues as it was.
10. **Retire the 1.x database objects with `retire-1x`, after you accept 2.0.** The kept units (`<unit>.1x-backup`) and the 1.x database are the way
    back until then, and nothing deletes them before. The 1.x installer made functions, triggers, views, tables, a schema and
    four database roles in your database, and `import-1x` only reads them. Make a `pg_dump` backup of the 1.x database and test the restore of that 1.x backup: after the next step it is the
    only way back. Stop the 1.x service (`stop-1x`), store the password of an account that owns the 1.x objects (the owner role of the 1.x installer
    or a role that has its rights) with `secret-set ledger.import.source.password`, set `ledger.import.source.username` to that account
    and run `retire-1x` without options:

    ```sh
    sudo runuser -u pg-ledger-mcp -- java -jar dist/ledger-migrate-1x.jar retire-1x --ledger.import.source.username=<owner-account>
    ```

    It checks that `import-1x --verify-only` matches, that no other session runs as a 1.x
    role or holds a lock in the 1.x schema and that the account owns the objects; it prints every object in the order it is dropped and
    the SHA-256 of that list, and changes nothing. Read the list, then run `retire-1x --confirm=<sha256> --backup-tested`:

    ```sh
    sudo runuser -u pg-ledger-mcp -- java -jar dist/ledger-migrate-1x.jar retire-1x --ledger.import.source.username=<owner-account> --confirm=<sha256> --backup-tested
    ```

    The command cannot check the backup, so `--backup-tested` is your statement that the 1.x backup was made and its restore was tested. It drops
    the triggers, views, tables, functions, sequences and the schema in one transaction, and then the roles of
    `ledger.import.source.roles`; a role that is the account of the run or of the 2.0 settings, has other members or owns objects
    elsewhere stays and is listed as `SKIPPED`. The ledger never runs this by itself.

### What the import does

- It reads the 1.x schema read only, copies everything in one transaction and rolls back on any failure; then it compares the
  number of rows and the SHA-256 digest of every table (`--verify-only` repeats the comparison). It runs once, on the schema that
  `migrate` and `setup` made, which is the state after the installer (the installer runs `setup`: the seed codes and the audit rows of
  that are accepted, and a seed code that 1.x also has takes the 1.x values). It refuses when a table of ledger data, a session key or
  an earlier import is already there, and it lists every value of the old data that the new schema cannot hold; it never invents a value.
- The ids of 1.x are kept as they are: an id of a task or a condition is 1 to 120 characters without whitespace or control
  characters, in any case and with any characters (`:`, `/`, `#`, `.`, `_`, `-` ...); defect ids keep their form (`<CODE>-D-0001`). Ids of one
  kind that differ only in case are one id to the new schema (a lookup compares the id exactly, and the key of the id in upper case is
  unique on every DBMS), so the import refuses such a pair and lists it.
- An empty text of 1.x (`purpose`, `success_condition`, `behavior`, `root_cause` and `condition_text` allowed `''`) is stored as NULL and
  the tools return an empty string for it, as 1.x did. The `code` of a condition (200 characters) and the `ci_ref` (2000 characters) are
  longer than in the earlier 2.0 builds, and the `origin_ref` of a task is a long text (up to 20000 characters when written), so every value of 1.x fits.
- 1.x stored every relation with its smaller id first and kept nowhere which defect the caller named first, so the import copies the
  relations of the directed kinds (`caused_by`, `blocks`) as they are stored and never guesses; it lists them (the count and the ids,
  also in the audit row and with `--verify-only`). Check each one and, where it is the wrong way round, remove it and link it again with
  `ledger_link` with the right defect first.
- The grants of 1.x are copied without a session key (step 7). The request receipts of 1.x are not carried over: a request id of 1.x is never
  replayed by 2.0, so a write that repeats one is judged by the normal rules like any new request. The history rows that come from 1.x
  carry the neutral names `IMPORT`, `IMPORTED_NOTE`, `IMPORTED_CHANGE`, `IMPORTED_LOGIN`, `IMPORTED_SHARED` and `IMPORTED_UNKNOWN`, and the field `legacy_ref` of 1.x is
  `source_ref` in the read tools.

### Settings of the 1.x source

The rows `ledger.import.source.*` belong to the commands `import-1x` and `retire-1x` and to no other part of 2.0. Put them in `local.yaml` of the settings
directory or give them on the command line as `--name=value`; the password is a secret and is never put in a file.

| Key | Used by | Meaning |
|---|---|---|
| `ledger.import.source.url` | `import-1x`, `retire-1x` | JDBC URL of the database that holds the 1.x ledger |
| `ledger.import.source.username` | `import-1x`, `retire-1x` | a read-only account of that database (`retire-1x` needs an account that owns the 1.x objects) |
| `ledger.import.source.password` | `import-1x`, `retire-1x` | its password (secret) |
| `ledger.import.source.schema` | `import-1x`, `retire-1x` | the 1.x schema, default `ledger` |
| `ledger.import.source.roles` | `retire-1x` | the database roles of the 1.x installer that `retire-1x` removes after the schema, default `ledger_owner`, `ledger_author`, `ledger_reviewer`, `ledger_reader` |

**Upgrade note: the admin web sign-in.** If you signed in to the admin web with OpenID Connect, say so: set `ledger.admin.sign-in=oidc`
(an installation with `ledger.admin.oidc.*` settings and no `ledger.admin.sign-in` is refused at start with a message that names it), and
replace the list `ledger.admin.oidc.owners` by the one e-mail address of the owner, `ledger.admin.oidc.owner-email`, with the issuer it
counts for, `ledger.admin.oidc.issuer`; a configuration that still has the list is refused at start and the message names both keys,
because the product never picks one of several people silently. Whichever way you sign in, register a passkey with `passkey-code`: it
approves every change.

## Build and test

JDK 25 and the Gradle wrapper are all that is needed; the wrapper downloads Gradle itself.

```sh
./gradlew build
```

The build compiles with warnings as errors, runs every module test against the PostgreSQL that is installed on the machine
(see the test database below; there is no embedded database and the build does not start one) and checks that no JDBC driver, JPA,
migration tool or Lombok is on a runtime classpath. The documentation tests of
`ledger-cli` (DOC-001 to DOC-029 in [docs/testing/TEST_CASES.md](docs/testing/TEST_CASES.md)) check this README, the Korean one and the
release notes against the code.

The system test of `ledger-system-test` (SYS-001 to SYS-012) needs that same local PostgreSQL and the built jars: it runs the install
commands, `ledger-cli.jar`, `ledger-mcp-server.jar` and the hook jar as separate Java processes against the test database, so the build
makes the jars before it runs the test. It is part of `./gradlew build`; `./gradlew :ledger-system-test:test` runs it alone.

### The test database

The module tests need a PostgreSQL 18 or later that is already installed and running. By default they connect to
`127.0.0.1:55432`, database `ledger_test`, as the owner of that database; a second login, the least-privilege application account,
is used by the tests of the account rights. Each test works in a schema of its own that it creates and drops, so nothing remains
except inside that database, and one test JVM holds at most 10 connections. A test that cannot reach the database fails with a
connection error; it is not skipped and does not fall back to another database.

The connection is set with Gradle properties, on the command line or in `gradle.properties`. The values below are the defaults,
except the two password file properties, whose defaults are the files of the test machine that are named in the root
`build.gradle.kts`:

```sh
./gradlew test \
  -Pledger.test.jdbc-url=jdbc:postgresql://127.0.0.1:55432/ledger_test \
  -Pledger.test.jdbc-user=ledger_test \
  -Pledger.test.app-user=ledger_test_app \
  -Pledger.test.jdbc-password-file=<owner-password-file> \
  -Pledger.test.app-password-file=<app-password-file>
```

A password is never a property value: the two password file properties name a file whose first line is the password, and only the
test JVM reads it. When a property is not set, no password is given to the driver and the PostgreSQL JDBC driver reads the pass
file of the user (`pgpass.conf` on Windows, `.pgpass` on Linux). A password is never written to code, configuration, logs or
commit messages.

### Optional properties for machines with few resources

The test JVMs are not limited by default. On a machine with few resources, the following optional Gradle properties limit them:
the number of test JVMs run at the same time, the maximum heap size of each, and the number of processors each reports. They are
passed on the command line and are not set in the repository, so they do not affect other users of it. A value that is not a
number, or is smaller than 1, fails the build at configuration time.

```sh
./gradlew test -Pledger.test.forks=1 -Pledger.test.maxHeapSize=1g -Pledger.test.activeProcessorCount=1
```

### Build speed

`gradle.properties` switches on the Gradle configuration cache, the build cache and parallel execution (`org.gradle.configuration-cache`,
`org.gradle.caching`, `org.gradle.parallel`); the Gradle daemon and file watching keep their defaults. A second `./gradlew build` with
nothing changed reuses the configuration and runs only the tasks whose inputs changed. The tests of the modules that use the test
database (`ledger-database`, `ledger-application`, `ledger-mcp-server`, `ledger-admin-web`, `ledger-cli` and `ledger-system-test`) are the
exception: the state of the database is an input that Gradle cannot see, so they run every time and are never taken from the build
cache or stored in it. They also run one after the other, even with parallel execution: the test database role has a connection
limit that several test JVMs at once would exceed. The tests of the other modules can be taken from the cache. The limits of a machine (the number of workers, the
test JVMs, the heap, the processors) are not repository settings: give them as command arguments, for example `--max-workers=1`
together with the properties above.

## Layout

- `ledger-domain`, `ledger-attestation`, `ledger-database`, `ledger-application`, `ledger-mcp-server`, `ledger-admin-web`,
  `ledger-cli`, `ledger-client-hook`, `ledger-migrate-1x`: the modules above, each with `src/main` and `src/test`; `ledger-system-test` has `src/test` only.
- `clients/claude`, `clients/codex`, `clients/antigravity`: the manifests and README of each client plugin; the build stages them with
  the hook jar into `ledger-client-hook/build/client-plugins`.
- `docs/`: the test case catalogue, the release note format and the release notes.
- `gradle/`, `build.gradle.kts`, `settings.gradle.kts`: the build.
- `deploy/`: the two installers, `install.sh` (Linux) and `install.cmd` (Windows).
- The 1.x implementation (DBHub, Node and PostgreSQL functions) is not part of 2.x and is no longer in the tree. Its source is available at the tag `v1.1.0`.

## License

From version 2.0, pg-ledger-mcp is licensed under the GNU Affero General Public License v3.0 or (at your option) any later version (`AGPL-3.0-or-later`); see [LICENSE](LICENSE).

Copyright (C) 2026 Jaeseong Song

`AGPL-3.0-or-later` applies to 2.0.0 and to every release published after it, including any later maintenance release of the 1.x line. The MIT License continues to apply only to the copies already published under it, that is v1.0.0 and v1.1.0.

Under section 13 of the license, whoever runs a modified version as a network service must offer the users of that service the corresponding source code.
The admin web shows a link to the source code in the footer of its pages; its address is the setting `ledger.source.url`, by default the
public repository of the project. Whoever runs a modified version sets `ledger.source.url` to the place where the corresponding source of that
modified version is offered. The setting takes an `http` or `https` address; the answers of the MCP server do not carry it.
