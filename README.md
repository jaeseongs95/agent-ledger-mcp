# Veridger

[한국어](README.ko.md)

A universal ledger for work done by AI agents across software, film production, writing, research and operations, served as one MCP
endpoint. It records issues, specifications, tasks, milestone criteria, deliverables and their independent verification, together with
who did each one and why. Agents read and write through standard MCP tools; they never get a database login of their own.

This is the **personal edition**: one person installs it and runs it on their own computer or on a server of their own, and trusts the
whole installation target. The two services (the MCP server and the admin web) listen on the loopback address `127.0.0.1` only. Making
them reachable from another machine, with a reverse proxy and a TLS certificate, is the operator's, with a tool of the operator's
choice: the installers write no proxy configuration and order no certificate.

Version 2.0.0 is a rewrite in Java 25 and Spring Boot with its own tool contract (every write tool takes a required `operationKey`,
every response uses the `ApiResult.v1` envelope, and no argument of an earlier version is accepted next to the new ones); what changed
for a client is stated in the release notes ([2.0.0](docs/release-notes/2.0.0.md), [2.1.0](docs/release-notes/2.1.0.md), [2.1.1](docs/release-notes/2.1.1.md), [3.0.0](docs/release-notes/3.0.0.md), [4.0.0](docs/release-notes/4.0.0.md), [4.0.1](docs/release-notes/4.0.1.md), [4.1.0](docs/release-notes/4.1.0.md), [4.2.0](docs/release-notes/4.2.0.md), [4.3.0](docs/release-notes/4.3.0.md), [4.3.1](docs/release-notes/4.3.1.md), [4.3.2](docs/release-notes/4.3.2.md), [4.4.0](docs/release-notes/4.4.0.md), [4.4.1](docs/release-notes/4.4.1.md)); the format of release notes is in
[docs/RELEASE_NOTES_FORMAT.md](docs/RELEASE_NOTES_FORMAT.md).

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

- **Author identity is stamped by the host hook, not claimed by the model.** Every MCP request authenticates over HTTP with the
  configured `Bearer` token `ledger.mcp.token`, and every write call and `ledger_whoami` carries the reserved key `_hostAttestation`
  set to `<vendor>:<session-id>`, which the client host hook stamps and models must omit. For example, the author is `claude:1f2e3d4c`
  or `codex:9a8b7c6d`; the owner of the ledger is `user:owner`. A tool has no `author_id` argument.
- **Writes need a live grant.** The owner grants an author a scope on a project with `ledger-cli grant` (or on the grants page of
  the admin web). The scopes are `issue`, `spec`, `task`, `deliverable` and `verify`. A write without a matching grant is refused. A
  write that touches rows of two projects (a spec, task or criterion moved to another project, a link, a merge into an issue of another
  project) needs the matching grant on both projects.
- **Completion and verification are separate across all entities.** Authors record deliverables (`ledger_record_deliverable`) on an
  `issue`, `spec` or `task` and report completion (`RESOLVED` on an `issue`, `COMPLETED` on a `spec`, `task` or criterion). `VERIFIED`,
  `MET` and `NOT_MET` are set only through the reviewer tools `ledger_review` and `ledger_review_correct`, which need the scope
  `verify` and an `evidence_uri`. An issue is verified only with at least one recorded deliverable and only by a session that did not
  register the issue, did not record a case of it and did not record or amend a deliverable of it (merged issues count as its own); a
  specification or task is verified only by a session that did not create it, does not own it, never recorded a deliverable on it and
  never reported it completed; a milestone criterion is set `MET` or `NOT_MET` only by a session that did not register or complete it
  (otherwise `SAME_SESSION`). Verification and rework follow-ups route to `owner_actor_id` rather than `created_by`. Check metadata
  (`check_ref`, `check_result`) is optional and vendor-neutral. A `VERIFIED` entity takes no `ledger_record_deliverable` and no edit
  (`VERIFIED_ENTITY`): a reviewer reopens it first with `ledger_review`.
- **History is append-only.** Every write adds a history row: when, author, channel, operation, state change and reason. The
  application has no path that updates or deletes a history row, and the grant template of the database module (`migrate` applies it to
  the application account) gives that account no `UPDATE` or `DELETE` on it. A mistake is marked with a `CORRECT` row. An operation
  that changes an existing row also stores the full row as it was before the change (`before_data`, returned by `ledger_history` with
  `include_before`). One exception: a `ledger_record_deliverable` that changes nothing of the target entity except its `updated_at` and
  version (the owner and the status stay) writes no extra history row of the target entity and so stores no `before_data` of it; the
  deliverable row is its history.
- **Retries are safe.** Every write tool takes a required `operationKey` (1 to 128 characters, for example a UUID). Repeating the same
  request with the same key returns the stored result and writes nothing twice. The keys belong to the author (as the keys of
  `artifact_begin` do): another author may use the same key for a write of its own. A key of the author that is already bound to
  another operation, payload or channel is refused with `OPERATION_CONFLICT`. The server never makes a key up: a missing or empty
  key is refused with `VALIDATION_FAILED` (path `operationKey`). `ledger_batch` takes one key for the whole batch and the batch is
  one request: the same key with the same operations returns the stored results, the same key with other operations (one added,
  removed, changed or moved) or under another tool is `OPERATION_CONFLICT`. `artifact_chunk` and
  `artifact_finish` take no `operationKey`: an upload starts with the `operationKey` of `artifact_begin`, a chunk is idempotent by its
  `uploadId`, `sequence` and `chunkSha256`, and finish by its `attachmentId`.
- **Optimistic locking.** Edits and transitions carry `expected_version`; a stale version is `VERSION_CONFLICT`.
- **Projects and ids.** An issue belongs to a project and gets the id `<CODE>-I-0001`, `<CODE>-I-0002`, and so on (imported 1.x rows
  keep `<CODE>-D-0001`). A specification without an id gets `<CODE>-S-0001`, and a task without an id gets `<CODE>-T-0001`. An id that
  a caller chooses for a specification, task or milestone criterion is 1 to 120 characters without whitespace, in any case and with any
  characters (`:`, `/`, `#`, `.`, `_`, `-` and so on); an id that equals an existing one when the case is ignored is refused
  (`DUPLICATE_KEY`), and an id is always compared exactly. An issue id that a caller chooses, and a spec or task id in the numbered form
  `<CODE>-S-0001` or `<CODE>-T-0001`, must have a number that is not above the next number of the project: a free number below it is
  accepted, and a number above it is refused with `ID_NUMBER_AHEAD` (`ID_COUNTER_CHANGED` tells you to send the request again if
  another request took the number meanwhile). Numbered ids use at least 4 digits with no leading extra zeros: `<CODE>-I-00002` and
  number 0 are refused (`VALIDATION_FAILED` at `id`).
- **One issue, one root cause, many observed cases.** Each observation of an issue (symptom, log, field note, report) is a case under it
  (`ledger_record_case`). A merged issue (`MERGED`, `merged_into`) keeps its cases; `ledger_issue` shows them under the issue it was
  merged into, and a `MERGED` issue takes no new cases.
- **Common codes.** Issue category, issue severity, deliverable kind and relation kind are the four groups `ISSUE_CATEGORY`,
  `ISSUE_SEVERITY`, `DELIVERABLE_KIND` and `RELATION_KIND`, with Korean and English names. The active values are part of the tool
  definitions. A retired code stays on existing rows and is refused when newly assigned. A severity is required on every issue. Only a
  write that stores a common code checks it; a transition does not, so an imported issue that still holds the retired `UNRATED` can be
  progressed and verified, and is rated by an edit (`ledger_issue_save`). A relation kind has a direction, which is part of its code:
  `caused_by` and `blocks` are directed (`GIVEN`), keep `a` and `b` in the order the caller gave them, and so (a, b) and (b, a) are two
  relations; `same_site`, `same_family`, `same_work_unit`, `same_fix_unit` and `regression_of` have no direction (`CANONICAL`) and are
  one relation in either order (`ledger_codes` shows the direction, and `code-set` sets it).
- **Creation provenance is immutable.** New rows receive `created_by` automatically; edits and reviewer transitions keep it.
- **Time is UTC.** Timestamps are stored and transmitted in UTC (ISO-8601 with `Z`); the server converts no time zone. The JVM and
  the database session must run in UTC, and a server that finds otherwise does not start (`ledger.schema.require-utc`). Each program
  sets the default time zone of its own JVM to UTC when it starts, so a command given as in this README needs no
  `-Duser.timezone=UTC` whatever time zone the host has (the service definitions of the installers pass the option as well).
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

A direct MCP caller is authenticated on HTTP by the configured `Bearer` token `ledger.mcp.token`, paired with the host-stamped `_hostAttestation`.

## MCP tools

The server speaks MCP over Streamable HTTP at `/mcp` (default `127.0.0.1:8765`) and answers `/healthz`. Callers present `ledger.mcp.token`. Every tool returns the `ApiResult.v1` envelope (`schemaVersion` `"1.0.0"`,
`ok`, `data`, `error`). Every tool input is closed and flat (no raw SQL query argument, no untyped map): an unknown argument is
refused, an object or an array argument must be sent as that JSON value (not as text), and the text `null` is a text, not a missing
argument. A JSON `null` on an optional field counts as not given; an optional text field is cleared with an empty string.

Write tools (authenticated author and a live grant are needed). Every write tool except `ledger_whoami` takes the required
`operationKey`:

| Tool | Channel | What |
|---|---|---|
| `ledger_whoami` | author | verify caller identity and list live project grants (read only) |
| `ledger_issue_save` | author | create or edit an issue with explicit parameters (`project_id`, `title`, `category`, `severity`, `symptom`, `root_cause`, `owner_actor_id`, `id`, `expected_version`, `reason`) |
| `ledger_spec_save` | author | create or edit a specification (`project_id`, `parent_id`, `title`, `summary`, `acceptance_criteria`, `priority`, `owner_actor_id`, `origin_ref`, `origin_quote`, `id`, `expected_version`, `reason`) |
| `ledger_task_save` | author | create or edit a task (`project_id`, `title`, `purpose`, `success_condition`, `owner_actor_id`, `origin_ref`, `origin_quote`, `id`, `expected_version`, `reason`) |
| `ledger_criterion_save` | author | create or edit a milestone criterion (`project_id`, `id`, `milestone_name`, `goal_label`, `code`, `criterion_text`, `expected_version`, `reason`) |
| `ledger_transition` | author | change author-side status or state (`OPEN`, `RESOLVED`, `NOT_AN_ISSUE`, `DISPUTED`, `MERGED`, `DRAFT`, `PLANNED`, `ACTIVE`, `BLOCKED`, `COMPLETED`, `CANCELLED`, `UNKNOWN`) |
| `ledger_record_deliverable` | author | record or amend a deliverable (`document`, `commit`, `media`, `scene`, `dataset`, `design`, `artifact`, `other`) on an `issue`, `spec` or `task`, optionally marking it `RESOLVED` or `COMPLETED` (`mark_done`) |
| `ledger_record_case` | author | add an observed case under an issue (`MERGED` issues are refused and name their target) |
| `ledger_link` | author | link issues, specs, tasks and criteria (`issue_task`, `issue_spec`, `spec_task`, `criterion_task`, `criterion_issue`, `criterion_spec`, or issue `RELATION_KIND` codes), or remove a link |
| `ledger_correct` | author | mark a history row as wrong |
| `ledger_batch` | author | up to 200 of the operations above in one transaction: all commit or none; one `operationKey` for the batch |
| `ledger_review` | reviewer | independent verification (`VERIFIED`, `MET`, `NOT_MET`) and reopening (`evidence_uri` required) |
| `ledger_review_correct` | reviewer | mark a reviewer verification row as wrong |

Attachment tools (authenticated author with at least one live grant; see Attachments below):

| Tool | What |
|---|---|
| `artifact_begin` | start the upload of a bundle of 1..64 files (`files`, required `operationKey`, optional `purpose` = `evidence`) |
| `artifact_chunk` | send the next chunk (at most 262144 bytes, base64) with `chunkSha256`; idempotent by `uploadId`, `sequence` and `chunkSha256` (no `operationKey`) |
| `artifact_finish` | verify every declared file size and SHA-256 and seal the attachment bundle; idempotent by `attachmentId` (no `operationKey`) |
| `artifact_status` | inspect the state, size, SHA-256, expiry and progress of an `uploadId` or `attachmentId` (never a path or storage key) |
| `artifact_read` | read one decrypted, integrity-checked page of file `seq` in a sealed attachment |

Read tools (no attestation and no grant; structured queries that replace the raw SQL tools of 1.x):

| Tool | What |
|---|---|
| `ledger_get` | status/state and version of many ids at once (issues, specs, tasks and criteria mixed); source of `expected_version` |
| `ledger_issue` | one issue with deliverables, tasks, specs, criteria, cases (including merged issues), relations, `owner_actor_id` and recent history |
| `ledger_spec` | one specification with summary, acceptance criteria, parent/child specs, deliverables, tasks, issues, criteria and recent history |
| `ledger_task` | one task with purpose, success condition, origin, owner, deliverables, issues, specs, criteria and recent history |
| `ledger_criterion` | one milestone criterion with milestone, goal label, code, criterion text, state and linked tasks, issues and specs |
| `ledger_history` | append-only history and receipts by ids, id range, entity, project, operation, author or time range; `include_before` adds `before_data` |
| `ledger_search` | issues, specs, tasks and criteria by keywords, case-insensitive, `match` is `ANY` (default) or `ALL` |
| `ledger_issues` | issues of a project, filtered by status, severity, category, attention kind and owner |
| `ledger_specs` | specifications of a project, filtered by status, parent id, priority, owner and attention kind |
| `ledger_tasks` | tasks of a project, filtered by state, owner and attention kind |
| `ledger_criteria` | milestone criteria of a project, filtered by state and `milestone_name` |
| `ledger_attention` | items needing action: `OPEN_WITH_DELIVERABLE`, `RESOLVED_WITHOUT_DELIVERABLE`, `OPEN_NO_OWNER`, `SPEC_COMPLETED_NOT_VERIFIED`, `TASK_UNKNOWN`, `DONE_NOT_VERIFIED` |
| `ledger_stats` | counts per state and severity of one project or of all; `bucket` = `day` adds new items per UTC day |
| `ledger_projects` | projects with id, code and title |
| `ledger_codes` | common codes; retired codes included with `include_inactive` |

The list read tools take `max_rows` (1 to 1000, default 50) and `cursor`; their answers carry `truncated` and `next_cursor`. `ledger_search` applies `created_from` and `created_to` to issues, specs and tasks; a criterion has no creation time, so a search with either bound leaves criteria out (a search for criteria only with a bound returns nothing).
The tools `execute_sql_reader` and `search_objects_reader` of 1.x were removed: use the read tools above.

When a common code changes, the server sends `notifications/tools/list_changed`, so a connected client reloads the tool
definitions without a restart. A change made by another process (the admin web, `ledger-cli`) is noticed within
`ledger.mcp.code-poll-interval`. A new tool is registered when the server starts.

`ledger_record_case` takes `issue_id`, `observation` (1 to 4000 characters) and `reason`, and optionally `evidence`,
`source_quote` and `observed_at` (ISO-8601 with `Z` or an offset). In `ledger_batch` the same arguments go in an operation
`{"op": "record_case", ...}`:

```json
{"issue_id": "WEB-I-0007", "observation": "checkout returns 500 for a gift card",
 "evidence": "request 7f3a, api/checkout.ts:88", "source_quote": "I can't pay with my gift card",
 "observed_at": "2026-11-05T10:00:00Z", "reason": "second report of the same failure"}
```

## Attachments

A review report (or any other evidence) is kept as an attachment, never as text in the database and never as a local file that
can be edited without a trace. The history row of a record points to it with the optional `attachment_id` of `ledger_transition`,
`ledger_review`, `ledger_review_correct`, `ledger_correct`, `ledger_record_deliverable`, `ledger_record_case` and the same operations of
`ledger_batch`. The record is written only when the attachment exists and was uploaded by the author who writes the record.

- **Upload.** Uploads are made through the five MCP tools above: `artifact_begin` (with a required `operationKey` and an
  ordered `files` list of 1..64 files), `artifact_chunk` for each chunk of at most 262144 bytes, `artifact_finish` (with
  `attachmentId`), and `artifact_status` and `artifact_read` (with optional `seq`, default `0`) to inspect or read a file.
  To upload local files without sending raw bytes through the model context, measure them locally with
  `ledger-cli attachments-prepare <file> [--name=<name>]` and stream chunks directly to `/mcp` with
  `ledger-cli attachments-send <file> --upload-id=<id>` (uses `uploadId` capability without session attestation).
- **Storage.** The database holds only bundle/file metadata and storage copy revisions (`upload_staging`, `attachment`,
  `attachment_file`, `attachment_storage`; no body column, no foreign key). Each file copy is under `ledger.attachments.directory`,
  named by 128 random bits, with no extension and no original name, encrypted with AES-256-GCM in segments (one per chunk, the
  storage key and the position are authenticated), and read only once finished. An attachment is never changed or deleted: the
  application account of the database has `SELECT` and `INSERT` on it only.
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
  `orphan-grace` has passed; `--purge-orphans` is the only way `orphans/` is emptied, and it never deletes a file whose row exists again (audited). The admin web shows the same result
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
| `ledger-attestation` | `_hostAttestation` wire helpers and RFC 8785 canonical JSON utilities shared by the hook and the server (JDK only) |
| `ledger-files` | owner-only POSIX/ACL file and directory protection and `NOFOLLOW_LINKS` inspection, shared by the server, the CLI and the hook (JDK only) |
| `ledger-database` | Spring Data JDBC repositories, the schema files `V{n}__*.sql` and their runner, the loader of the registered JDBC driver |
| `ledger-application` | the application services: write gate, receipts, history, the write and read operations, and session grants |
| `ledger-mcp-server` | the Spring Boot MCP server (`ledger-mcp-server.jar`) |
| `ledger-admin-web` | the Spring Boot admin web for the owner (`ledger-admin-web.jar`) |
| `ledger-cli` | the owner command line tool (`ledger-cli.jar`) |
| `ledger-seed` | the seed bundle of a new installation, the default defect categories and the install defaults in one H2 file, and its reader; only `ledger-cli` uses it |
| `ledger-migrate-1x` | the separate tool that moves a 1.x installation to 2.0 (`ledger-migrate-1x.jar`, see Upgrading from 1.x); it uses `ledger-domain`, `ledger-database` and `ledger-application`, and the classes (the plain jar, none of its libraries) of `ledger-cli` for its command line machinery and secret store, so that the secret store is the one of the services; no other module uses it |
| `ledger-hook-client` | the light client of the hook (`ledger-client-hook.jar`, `java.base` only), the wire format, the state folder and the address file |
| `ledger-client-hook` | the resident program of the hook (`ledger-hook-resident.jar`, a Spring Boot application) |
| `ledger-system-test` | test code only: the system test of the packaged product, which starts the built jars against the local PostgreSQL |
| `ledger-arch-checks` | test code only: cross-module architecture, build-script, SQL/transaction boundary, and module dependency checks |

## Configuration

Settings are Spring Boot properties: in a file, on the command line (`--name=value`) or from the secret store. Secrets are never
put in a file or in an environment variable; `ledger-cli secret-set <name>` reads a secret with hidden input and stores it as one file
of the secret directory, in the folder of the use of its name, which the processes read as a Spring configuration tree. A secret is
stored under the name of its property, and a name that is no secret of the product is refused (`UNKNOWN_SECRET_NAME`). There is no
command that prints a stored secret.

The secret directory (`<settings folder>/secrets`) has one folder per use, and a process imports only the folders it needs, so the
MCP server never has the secrets of the admin web in its settings and the admin web never has the token of the agents under that name:

| Secret (the file name is the property) | Read by | Folder in the secret directory |
|---|---|---|
| `ledger.jdbc.password` | MCP server, admin web, `ledger-cli`, `ledger-migrate-1x` | `common` |
| `ledger.admin.token`, `ledger.admin.agent-token`, `spring.security.oauth2.client.registration.<id>.client-secret` (OpenID Connect) | admin web | `admin` |
| `ledger.mcp.token` | MCP server, `ledger-cli mcp-check` | `mcp` |
| `ledger.import.source.password` | `ledger-migrate-1x` | `tool` |

The key files of the attachments are not in the secret directory: they are in `<settings folder>/attachment-keys`, below no
configuration tree (Spring Boot makes every file below a tree a property of the process).

| Key | Used by | Meaning |
|---|---|---|
| `ledger.jdbc.url` | all | JDBC URL of the ledger database |
| `ledger.jdbc.username` | all | database account |
| `ledger.jdbc.password` | all | database password (secret) |
| `ledger.jdbc.pool-size` | all | connection pool size, default 10 |
| `ledger.jdbc.connection-timeout-ms` | all | maximum wait for a connection from the pool in milliseconds, default 30000 |
| `ledger.jdbc.idle-timeout-ms` | all | how long an idle connection stays in the pool in milliseconds, default 600000 |
| `ledger.jdbc.keepalive-time-ms` | all | how often idle pooled connections are validated in milliseconds (`0` disables, otherwise at least 30000), default 0 |
| `ledger.jdbc.driver` | all | the registered driver, written by `driver-register` (jar, class, SHA-256), read at start |
| `ledger.schema.name` | all | schema that holds the tables; created by `migrate` when missing; absent means the default schema of the account |
| `ledger.schema.require-utc` | servers, CLI | refuse to start when the JVM or the database session is not UTC, default true |
| `ledger.mcp.token` | MCP server, `mcp-check` | the bearer token of the agents, at least 16 characters (secret) |
| `ledger.mcp.allowed-hosts` | MCP server | accepted `Host` values, default `127.0.0.1` and `localhost` |
| `ledger.mcp.allowed-origins` | MCP server | accepted browser origins, default none |
| `ledger.mcp.max-request-size` | MCP server | largest request body, default 1MB |
| `ledger.mcp.code-poll-interval` | MCP server | how often a code change of another process is looked up, default 5s |
| `ledger.mcp.session-idle-timeout` | MCP server | how long an inactive Streamable HTTP MCP session stays in memory before eviction, default 30m |
| `ledger.mcp.max-sessions` | MCP server | maximum concurrent Streamable HTTP MCP sessions held in memory, default 1000 |
| `ledger.mcp.keep-alive-interval` | MCP server | Streamable HTTP SSE keep-alive ping interval (`0s` disables pings for POST-only clients), default 30s |
| `ledger.admin.token` | admin web | the admin token, at least 16 characters (secret): the bearer of the read-only JSON endpoints under `/api`; with `ledger.admin.sign-in=token` it is also the password of the login page |
| `ledger.admin.agent-token` | admin web | required: the agents' bearer token, equal to `ledger.mcp.token`, and distinct from `ledger.admin.token`; the admin web refuses it with `AGENT_TOKEN_FORBIDDEN` (secret) |
| `ledger.admin.allowed-hosts` | admin web | accepted `Host` values (DNS rebinding guard), default `127.0.0.1` and `localhost` |
| `ledger.admin.driver-file` | admin web | the driver registration file the driver page writes, default `/etc/veridger/jdbc-driver.yaml` |
| `ledger.admin.driver-directory` | admin web | the one directory a driver jar may be taken from, default `/etc/veridger/drivers` |
| `ledger.admin.sign-in` | admin web | how the owner signs in, exactly one of `token`, `oidc` or `passkey`; the login page offers only that one; absent means `passkey` for a new installation, and an installation with `ledger.admin.oidc.*` settings and no choice is refused at start (nothing is guessed) |
| `ledger.admin.oidc.owner-email` | admin web | with `ledger.admin.sign-in=oidc`: the one e-mail address of the owner, which the provider must have verified; the provider itself is `spring.security.oauth2.client.*` |
| `ledger.admin.oidc.issuer` | admin web | with `ledger.admin.sign-in=oidc`: the issuer (`iss`) the address counts for: an address is only meaningful at the issuer that verified it |
| `ledger.admin.passkey.rp-id` | admin web | the host name the passkeys are bound to; default: the one non-loopback host of `ledger.admin.allowed-hosts`, else `localhost` |
| `ledger.admin.passkey.origins` | admin web | the origins the browser shows (scheme, host and port): `https` except `http://localhost`; default `https://<rp-id>`, or `http://localhost:<server.port>` for `localhost` |
| `ledger.admin.lockout.window` | admin web | the period in which credential checks (admin token, passkey sign-in, one-time code) are counted, default 60s |
| `ledger.admin.lockout.global-max-failures` | admin web | credential checks of all callers together within the window before the delay starts, default 50 |
| `ledger.admin.lockout.global-delay` | admin web | the wait before a credential is compared once the budget is used, default 1s; it slows down and never locks or refuses: the right credential always signs in |
| `ledger.admin.mcp.health-url` | admin web | the `/healthz` URL of the MCP server shown on the overview; absent means not configured |
| `ledger.admin.mcp.timeout` | admin web | timeout of that check, default 2s |
| `ledger.admin.update.enabled` | admin web | enable the `/updates` release checker and staging button, default true |
| `ledger.admin.update.repository` | admin web | GitHub repository (`owner/repo`) queried for releases, default `jaeseongs95/veridger` |
| `ledger.admin.update.directory` | admin web | directory where verified update JARs and `request.properties` are staged for the host updater, default `/etc/veridger/update` |
| `ledger.attachments.directory` | servers, CLI | where the files of the attachments are kept (the installer sets it to `<data folder>/attachments`); when it is not set the attachment tools are not registered |
| `ledger.attachments.key-directory` | servers, CLI | where the key files are kept, outside `directory` (the start fails when it is inside); the installer sets it to `<settings folder>/attachment-keys`, beside the secret directory and not inside it |
| `ledger.attachments.active-key-id` | servers, CLI | the key that new uploads are written with; `setup` writes it into `local.yaml`; the server does not start without the key file |
| `ledger.attachments.max-file-bytes` | servers, CLI | the largest file, default 64MB |
| `ledger.attachments.staging-budget-bytes` | servers, CLI | what all open uploads may declare together, default 512MB |
| `ledger.attachments.attachment-budget-bytes` | servers, CLI | what all kept attachments (and the open uploads) may hold together, default 10GB; over it a new `artifact_begin` is refused and nothing is deleted |
| `ledger.attachments.max-open-per-owner` | servers, CLI | how many uploads of one author may be open, default 4 |
| `ledger.attachments.disk-margin-bytes` | servers, CLI | the space that has to stay free on the disk of `directory` after a file is written, default 64MB (the size of the largest file): when `usable - size` is below it a begin or a chunk is refused with `ARTIFACT_STORAGE_FULL` and the owner has to free the disk |
| `ledger.attachments.upload-ttl` | servers, CLI | how long an upload lives after its last chunk, default 10m |
| `ledger.attachments.orphan-grace` | servers, CLI | how long a file without a row (or a replaced file) waits before `attachments-check` moves it to `orphans/`, default 24h |
| `ledger.attachments.reaper-interval` | MCP server | how often expired uploads are removed, default 1m |
| `ledger.secrets.home` | `ledger-cli` | the directory of the installation that holds the settings, the driver registration, the driver jars and the secret directory, default `/etc/veridger` on Linux and `C:/ProgramData/veridger/etc` on Windows, where the installer of the host puts it |
| `ledger.secrets.directory` | `ledger-cli` | the secret directory (it holds the folders `common`, `admin`, `mcp` and `tool` and is itself no configuration tree), default `secrets` below `ledger.secrets.home` (`/etc/veridger/secrets` on Linux) |
| `ledger.secrets.instance` | `ledger-cli` | the instance name that the default paths follow, default `veridger` |
| `ledger.cli.driver.directory` | `ledger-cli` | the directory `driver-register` takes a jar from, default `drivers` below `ledger.secrets.home` (`/etc/veridger/drivers` on Linux) |
| `ledger.cli.driver.file` | `ledger-cli` | the registration file `driver-register` writes, default `jdbc-driver.yaml` in `ledger.secrets.home` (`/etc/veridger/jdbc-driver.yaml` on Linux) |

The servers listen on loopback only (MCP `127.0.0.1:8765`, admin web `127.0.0.1:8865`), fixed in the code of both: there is no setting
for the address. To publish them, the operator puts a reverse proxy of their choice, which terminates HTTPS, on the same host and
lists the one host name that it passes in the allowed hosts of the service (`--mcp-host`, `--admin-host`). The admin web trusts
`X-Forwarded-For` and `X-Forwarded-Proto` from the loopback address only. The servers read the driver registration file and the secret directory
through `spring.config.import`, which the installers put into the service definition (see Installation); `ledger-cli` reads them by default.

## Installation

Two installers cover three targets: a personal PC and a Windows server run `deploy\install.cmd`, a Linux server runs
`sudo deploy/install.sh`. The targets differ by their options only, and on every one of them both servers listen on the loopback
address only (see Reaching the servers from outside). macOS is not supported; a Linux desktop that is a personal PC follows the
Linux procedure. The build needs JDK 25 and the host needs a Java 25 runtime at a path that every account can execute (not
under `/home`: the service account runs it too).

What every target has in common:

- **One service account.** The MCP server and the admin web run as one operating system account that belongs to no person: on
  Linux the system user `veridger` (no login, not root), on Windows the predefined account LocalService (no password). Its purpose
  is to keep the secrets of the ledger, the admin token and the database password, away from the account of the person who works on
  the machine and from the agents that run under it: the secret directory is readable by the service account only (on Windows: by the installing user and, read only, by LocalService, and by nobody else). The secrets are
  kept in one folder per use and each service imports only its own and the common one, so the process of the MCP server no longer holds
  the admin token or the OpenID Connect client secret in its settings (and no key file of the attachments is a property of either
  service). What this does not do: the two services run as one operating system user, so the file permissions do not keep one service from
  reading the folder of the other; the personal edition trusts the whole installation target, and the change is that no process
  carries a secret of another use in its environment. The two
  services of an installation, called an instance, are named after it: the instance is `veridger` unless `--name=<name>` gives
  another name, and its services are `<name>` and `<name>-admin` (`veridger` and `veridger-admin` by default). The Linux user, the
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
  starting a command as that user (`runuser`) and the service managers (`systemctl`, WinSW and `sc.exe`).
  The Java 25 check is the JVM's own: the jars are compiled for Java 25, and no other runtime loads them. Nothing is downloaded or
  installed. Running it again with newer jars is the upgrade: files and services are replaced, the schema and the grants are applied
  again, and secrets, tokens and data are kept. A run that stopped half way is repaired the same way: run it again with the same
  options (the commands that store secrets do not need the database). The settings directory is `ledger.secrets.home` of the tool; the
  services read the same sources through `spring.config.import`.

The options of `deploy/install.sh` are given as `--name=value`; the ones it does not read itself go on to `render-deploy`:

| Option | Meaning |
|---|---|
| `--name=<name>` | the name of the instance, default `veridger`: lower-case letters, digits and hyphens, at most 23 characters, not ending with `-admin`; Java validates it before anything is created |
| `--java=<path>` | the Java 25 runtime, default the `java` on the `PATH` of root; the service user runs it too, so it is at a path every account can execute (not under `/home`), which the script checks right after it creates the service user and before it makes anything else |
| `--jars=<dir>` | optional: the directory that holds the three jars; when omitted, the installer downloads the release jars and verifies `SHA256SUMS` automatically |
| `--version=<tag>` | optional: release tag to download when `--jars` is omitted (`latest` or `vX.Y.Z`), default `latest` |
| `--repo=<owner/name>` | optional: GitHub repository to download releases from when `--jars` is omitted, default `jaeseongs95/veridger` |
| `--driver-jar=<file>` | the JDBC driver jar |
| `--driver-class=<class>` | the driver class, which must accept the JDBC URL (for the PostgreSQL JDBC driver, `org.postgresql.Driver`) |
| `--owner-account=<account>` | the DDL account (its password is asked for) |
| `--app-account=<account>` | the application account |
| `--jdbc-url=<url>` | the JDBC URL of the ledger database (no password in it) |
| `--schema=<name>` | `ledger.schema.name`, optional |
| `--mcp-port=<n>` | the loopback port of the MCP server, default 8765 |
| `--admin-port=<n>` | the loopback port of the admin web, default 8865 |
| `--mcp-host=<host>` | optional: the one host name (lower case, no port) that the reverse proxy of the operator passes in the `Host` header for the MCP server; it is allowed in the `Host` check of the MCP server besides `127.0.0.1` and `localhost`, which are always allowed. Without it the server is reached on loopback only |
| `--admin-host=<host>` | optional: the same for the admin web, and a name other than `--mcp-host`. With it, the installer also writes `ledger.admin.passkey.rp-id` (the name) and `ledger.admin.passkey.origins` (`https://<name>`) into `admin.yaml`, so the passkeys of a remote installation work without a hand edit (set `rp-id` and `origins` in `local.yaml` for another arrangement) |
| `--stdin` | read the passwords from standard input instead of the terminal: first the one of the application account (only while it is not stored), then the DDL one; the convention of `docker login --password-stdin` |

`render-deploy` and `prepare-host` take five more options that the installers set themselves: `--platform=<platform>`
(`linux`, the default, or `windows`), `--home=<path>` (Windows: the installation directory), `--java=<path>` (the Java executable),
`--root=<directory>` (put the files below a directory instead of at their paths, for a look before they are put in place) and, for
`render-deploy` only, the flag `--check` (validate every option and change nothing; the installers run it first, so that a bad value,
the name included, stops them while nothing exists yet). `prepare-host` also takes `--jars=<dir>`, `--driver-jar=<file>` and, on
Windows, `--winsw=<file>`. Without `--root` the platform has to be the platform of the host that runs the command: the paths of the
other one are no paths of this host and would end up below the working directory, so the commands refuse them (naming `--platform`)
before anything is read or written.

### Linux server

Run as root; the user `veridger` is created when it is missing, `prepare-host` (Java) creates `/opt/<name>` (root, 0755),
`/etc/<name>` (the service user, 0750), its `drivers` folder (root, 0755), its `secrets` folder with the folders `common`, `admin`, `mcp` and `tool` and the folder `attachment-keys` (the service user, 0700), copies the
jars and the driver and renders the units into `/etc/systemd/system`, which are then enabled; the secrets are stored by the service
user. A file that the installer did not render and that a rendered file replaces is kept as `<file>.backup`; to move a 1.x installation,
see Upgrading from 1.x.

```sh
sudo deploy/install.sh --jars=dist --driver-jar=<driver-jar> --driver-class=<driver-class> --owner-account=<ddl-account> --app-account=<app-account> --jdbc-url=<jdbc-url>
```

The installer enables and restarts the two services (`<name>` and `<name>-admin`, `veridger` and `veridger-admin` by default) and installs the least-privilege host updater (`veridger-updater.sh` triggered by a systemd `.path` unit); check them with `systemctl status veridger veridger-admin`. When `--jars` and required options are omitted in an interactive terminal, `deploy/install.sh` downloads and verifies `SHA256SUMS` and prompts for missing values.

### Reaching the servers from outside

Both servers listen on `127.0.0.1` only, and no setting changes that. To reach them from another machine, the operator runs a
reverse proxy and a TLS certificate on the same host, with a tool of the operator's choice; the installers write no proxy
configuration, order no certificate and install no renewal. The proxy connects to `127.0.0.1:8765` (MCP server) and
`127.0.0.1:8865` (admin web) and passes the `Host` header of the client on. Give each service the one host name that the proxy
passes for it, `--mcp-host=<host>` and `--admin-host=<host>`: the name is allowed in the `Host` check of that service, besides
`127.0.0.1` and `localhost`, which are always allowed. With a name for the admin web, the installer also writes the passkey settings
for it (see the options above). The admin web trusts `X-Forwarded-For` and `X-Forwarded-Proto` from the loopback address only, so the
proxy has to overwrite them with the address and the scheme of the connection it received (for redirect and WebAuthn origin checks and
request logging; credential throttling uses `ledger.admin.lockout.global-delay` and never locks per client address). Long-lived MCP connections (Streamable
HTTP) need a timeout of the proxy that is long enough. The certificate, its renewal and the firewall are the operator's too.

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
| `"<name>"` | optional, in quotes: the name of the instance, default `veridger` |
| `"<more options>"` | optional, in quotes, after the name: more options of `render-deploy`, for example the host names of a server |

```bat
deploy\install.cmd dist <winsw-exe> <driver-jar> <driver-class> <ddl-account> <app-account> "<jdbc-url>"
```

A server that is reached from outside adds the host name of each service and puts a reverse proxy that you have prepared in front of the
two loopback ports (see Reaching the servers from outside):

```bat
deploy\install.cmd dist <winsw-exe> <driver-jar> <driver-class> <ddl-account> <app-account> "<jdbc-url>" "veridger" "--mcp-host=<mcp-host> --admin-host=<admin-host>"
```

For an interactive installation that downloads and verifies `SHA256SUMS` automatically, run `deploy\install.cmd --download`. The installer also registers the host updater script (`veridger-updater.cmd`) as a Scheduled Task for Admin Web updates.

Everything lives in `C:\ProgramData\<name>`. Both services run as LocalService, restart after a failure (the WinSW
configuration), and write their logs to the `logs` folder. `prepare-host` closes the folders to every account but SYSTEM and
Administrators and gives LocalService its access in the same step (Java, with ACLs, no `icacls`): it reads and runs the jars and
changes the settings, the logs and the data. The secrets are the installing user's: the folder of the secrets, its folders of the uses, the folder `attachment-keys` and each secret made in
them are created with an ACL for the installing user (everything) and for LocalService (read only), and for nobody else, so the
services read them and the accounts the agents run as cannot. Nothing is handed over to LocalService (the installing user stays the owner and
no privilege to take ownership is used), so the owner commands of `ledger-cli` keep working for the installing user, and a re-run of the
installer finds everything as it was; run it again as the same user (the personal edition has one user). The admin web is where the
owner works too (see Admin web: sign in with a passkey or OpenID Connect, and register the first passkey with `passkey-code`). On a
personal PC open the admin web at `http://localhost:8865` (the port is `--admin-port=<n>`): the admin web sets a `Secure` session
cookie, which the major browsers accept from `localhost` because they treat it as a secure context, and a passkey works on `localhost`
without HTTPS.

### First setup

The installers call `setup`, and you run it yourself after an upgrade or against a database that you prepared by hand. A new ledger
has to start with some data, so `ledger-cli` carries a seed bundle: the file `ledger-seed.mv.db`, which the build writes with a Java
program from the text files `src/seed/issue-categories.tsv` and `src/seed/install-defaults.properties` of the module `ledger-cli`
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

The defaults of an installation are written in one place, the seed bundle. `render-deploy` and `prepare-host` take the name
and the ports from it when an option is not given, and the installers ask for them with `install-default <name>` (`instance-name`,
`mcp-port` or `admin-port`), so no script holds a second copy.

### After the installation

1. Check the MCP server with `ledger-cli mcp-check`; without a registered driver the MCP server stops and says why, and the admin web
   starts in a setup mode that offers only the login page, which tells you to register the driver with `ledger-cli driver-register`
   (without a database there is no passkey to sign in with, so the driver page of the admin web is a console page).
2. Set up the owner side: `project-add` and `code-set`; when you come from 1.x, see Upgrading from 1.x instead of creating projects by hand. On Linux run
   `ledger-cli` as the service user, for example `sudo -u <name> java -jar /opt/<name>/ledger-cli.jar --ledger.secrets.instance=<name> project-add ...`.
3. Install the client hooks (see Client hooks) and issue session grants directly with `grant` or on the grants page of the admin web.

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
ledger asks for ANSI SQL on any database whose driver you register (PostgreSQL is the database that is tested; other products are not tested). And the ledger migrates by this explicit command and not when a server
starts, because the servers never hold the password of the DDL account: a server only checks the schema at start and, when files are
pending, refuses to start and names `ledger-cli migrate`, so a server that is taken over cannot change the schema.

## ledger-cli

`java -jar ledger-cli.jar <command> [arguments]`. An option is written `--name=value`. A Spring property (a name with a dot, such as
`--ledger.jdbc.url=...`) is passed the same way. The tool runs as the user that owns the secret directory (on Linux the
service user), and every owner operation is recorded with that user.

| Command | Syntax | What |
|---|---|---|
| `secret-set` | `secret-set <name> [--stdin] [--if-absent]` | store a secret from hidden input, or with `--stdin` from the first line of standard input (never from an argument or the environment); `--if-absent` keeps an existing secret and asks for nothing; the line read with `--stdin` is UTF-8 and a line that is not is refused (`INVALID_ENCODING`): Windows PowerShell 5.1 encodes what it pipes to a program with `$OutputEncoding`, which is ASCII unless it is changed, so set `$OutputEncoding = [System.Text.UTF8Encoding]::new($false)` (UTF-8 without a byte order mark) before piping a secret that has other characters |
| `driver-register` | `driver-register --jar=<path> --class=<driver-class>` | register the JDBC driver; needs no database |
| `token-generate` | `token-generate <name> [--print] [--copy-to=<name>]` | store a random token (32 bytes from `SecureRandom`) as a secret; an existing secret is kept; `--print` shows a token that was just created, once; `--copy-to` stores the same token under a second name, once |
| `migrate` | `migrate [--owner-account=<account>] [--app-account=<account>] [--stdin]` | apply the pending schema files, as the DDL account when `--owner-account` is given (its password is typed, or with `--stdin` read from standard input, and not stored), and then the grants of `dml-grants.sql` to the application account |
| `setup` | `setup [--owner-account=<account>] [--app-account=<account>] [--stdin]` | the first setup: migrate as `migrate` does, check that the application account can use the schema (exit code `3` and the missing step when it cannot), add the seed codes that are missing (a code that exists is never changed) and compare the seed with the database by counts and digest; running it again changes nothing |
| `passkey-code` | `passkey-code [--remove-existing]` | issue the one-time code that enrols a passkey of the owner in the admin web (shown once, valid 15 minutes, stored only as its hash; a new code ends the earlier unused one); `--remove-existing` closes every live passkey first, for an owner who lost them all |
| `project-add` | `project-add <id> <code> <title>` | add a project |
| `code-set` | `code-set <group> <code> <name-ko> [<description-ko>] [--name-en=<text>] [--description-en=<text>] [--sort=<n>] [--inactive] [--direction=<direction>]` | add or update a common code; `--direction` (`GIVEN` or `CANONICAL`) is for the group `RELATION_KIND` (see the `ledger_link` tool) |
| `codes` | `codes [<group>]` | list the common codes |
| `grant` | `grant <author> <project> <scope>... [--note=<text>] [--expires=<ISO-8601 with Z or an offset>]` | grant scopes on a project directly to an author (`<vendor>:<session-id>`) |
| `revoke` | `revoke <author> [<project> [<scope>]]` | revoke live grants |
| `grants` | `grants [<author>]` | list live grants |
| `attachments-check` | `attachments-check [--purge-orphans]` | check the attachments against their files and keys: a file that no row names, and a file that `attachments-rekey` replaced, is moved to `orphans/` and never deleted; a file in `orphans/` whose row exists again is moved back; the report gives the counts and the ids (never a storage key or a path) of the attachments that are missing, damaged or pointed to by no history row, and the exit code is `1` when one is missing or damaged; a key file that is missing or a disk that cannot be read stops the check with that error and is never reported as a damaged file; `--purge-orphans` first deletes the files in `orphans/` that no row names (a file whose row exists again is not deleted, the check moves it back) and records the number in the audit trail |
| `attachments-rekey` | `attachments-rekey --from=<keyId>` | write the attachment files of the key `<keyId>` again under the active key `ledger.attachments.active-key-id` (a different key); the old files are moved to `orphans/` by `attachments-check` after `ledger.attachments.orphan-grace` |
| `attachments-key-create` | `attachments-key-create` | create a new 256-bit key file in the key directory (readable by the service account only; on Windows by the installing user and, read only, by LocalService) and print its key id, never the key; set `ledger.attachments.active-key-id` to it to write new uploads with it |
| `attachments-prepare` | `attachments-prepare <file> [--name=<name>]` | measure one regular file in a single streaming pass and print JSON (`fileName`, `size`, `sha256`, `mediaType`) for `artifact_begin`; connects to nothing |
| `attachments-send` | `attachments-send <file> --upload-id=<id> [--url=<url>] [--ledger.mcp.token=<token>]` | send the chunks of one file directly to the running MCP server using `artifact_status` and `artifact_chunk` (`uploadId` capability without session attestation); resumes from server progress if interrupted and needs no database |
| `mcp-check` | `mcp-check [--url=<url>] [--ledger.mcp.token=<token>]` | connect to a running MCP server and check its tools |
| `render-deploy` | `render-deploy --java=<path> --jdbc-url=<url> --app-account=<account> [--name=<name>] [--platform=<platform>] [--home=<path>] [--schema=<name>] [--mcp-port=<n>] [--admin-port=<n>] [--mcp-host=<host>] [--admin-host=<host>] [--root=<directory>] [--check]` | write the service definitions (systemd units, or WinSW configurations on Windows) and the settings; the installers call it |
| `prepare-host` | `prepare-host --jars=<dir> --driver-jar=<file> [--winsw=<file>] --java=<path> --jdbc-url=<url> --app-account=<account> [--name=<name>] [--platform=<platform>] [--home=<path>] [--schema=<name>] [--mcp-port=<n>] [--admin-port=<n>] [--mcp-host=<host>] [--admin-host=<host>] [--root=<directory>]` | prepare the files of the host (run as root, or elevated on Windows): the folders with owner and mode, or their ACLs (on Windows the access of LocalService and the secret folders of the installing user), the copies of the jars and the driver (on Windows also of WinSW), and what `render-deploy` writes; the installers call it |
| `install-default` | `install-default <name>` | print one default of the installation from the seed bundle (`instance-name`, `mcp-port` or `admin-port`); the installers ask for their defaults with it |

## Admin web

The admin web (`ledger-admin-web.jar`) is a server-rendered console for the owner, on `127.0.0.1:8865` by default. It runs as
the service account of the installation and never as root.

- **Sign-in, one of three** (`ledger.admin.sign-in`). With `passkey` (the default of a new installation) the owner signs in with a
  passkey. With `token` (the personal edition, one person on their own computer or server) the owner types the admin token
  `ledger.admin.token` into the login page of the application. With `oidc` the owner signs in with OpenID Connect, and the owner is **one e-mail address**
  (`ledger.admin.oidc.owner-email`, verified by the provider at `ledger.admin.oidc.issuer`); another address, or one the provider did not
  verify, is refused. Only one of the three is offered: with `oidc` or `passkey` the token form is absent and a posted token
  finds no sign-in. The admin token `ledger.admin.token` also stays the bearer of the read-only JSON endpoints. Once signed in, owner
  changes apply directly under session and CSRF protection without per-action passkey re-approval. The agents' token is refused, so an agent that holds the MCP token cannot grant itself
  anything. Credential checks (passkey, one-time code, token) are slowed down once more than a budget of them arrive within a window
  (`ledger.admin.lockout.*`); nothing is ever locked, because a lock that anybody can trigger with wrong credentials locks the owner out as
  well. There is no limit per client either: the public reaches the services through the operator's reverse proxy on the same host, and
  the address that proxy forwards is a record of the request and not an identity (depending on the proxy, every user arrives as one
  address), so a lock by address would lock the owner with the attacker, and the personal edition, one person, has nobody to tell apart. A token
  made by `token-generate` (256 bits from `SecureRandom`) and the one-time code (100 bits) cannot be guessed, so a delay is all that is needed.
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
- **Pages.** Overview (Focused Governance Cockpit with live verification/hygiene/active/blocked action tiles, hydrated attention queue, non-zero localized work & gate breakdowns, and quiet MCP status pill that expands only when down), projects, issues (including `taskId` filtering), specifications, tasks, release gates (milestone progress rollups, linked-work counts, and `/criteria/detail` detail pages), history (read only, with entity deep-links, readable diff lines, and a native modal JSON viewer), attachments, common codes (searchable, with localized group/state filters and modal add, edit and deactivate/reactivate), grants (searchable session-centric cards with 1-click modal session granting, atomic per-project scope sync, and UTC expiry presets), and the JDBC driver comparison page. Filter dropdowns, badges, and cells render localized Korean/English names for enums and common codes, and deliverable lists use responsive cards. Projects, grants and common codes keep their main views read-first and manage add, edit and revoke/deactivate actions in native modal dialogs with inline search bars. The console uses a 3-domain fixed sidebar with a keyboard-accessible mobile drawer, automatic and manual Light/Dark themes, and inline HTML form validation recovery (while `/driver` never echoes refused paths or class names). Times are sent as UTC and shown in the local
  time of the browser.
- **Grants.** Sessions are grouped into per-author cards showing host badges and session IDs separately while preserving the stored `<vendor>:<session-id>` identifier. The owner selects the host (`codex`, `claude`, `gemini`, or custom) and enters the session ID in separate fields (pasting `<vendor>:<session-id>` auto-splits the fields) with no key registration or per-session token issuance, adjusts all scopes of a project in one atomic sync, or revokes grants through confirmation modals.
- **Updates (`/updates`).** The owner can check GitHub Releases for newer versions and stage verified updates with one button. The admin web never executes OS commands directly: it downloads release JARs only from allowed hosts, verifies `SHA256SUMS`, blocks releases requiring schema migrations or major upgrades for manual `ledger-cli setup`, and stages `request.properties` inside `ledger.admin.update.directory` so `veridger-updater.sh` or `veridger-updater.cmd` replaces the JARs, restarts the services, checks `/healthz`, and rolls back automatically on failure.

## Client hooks

The hook is two programs that lie next to each other in the plugin folder. The hosts run the **light client**,
`ledger-client-hook.jar`, which serves all clients; the first argument selects the client:
`java -jar ledger-client-hook.jar claude`, `codex` or `antigravity`. It loads nothing but `java.base` (no Spring, no Jackson, no
logging library) and, for every call, carries the input of the host over the loopback to the **resident program**,
`ledger-hook-resident.jar` (a Spring Boot application), and prints what that answers. What the host sees did not change: the host
JSON on the standard input, only the answer on the standard output (no JVM line, no log line), the exit codes and what a failure
means. The plugin directories are in `clients/claude`, `clients/codex` and `clients/antigravity` (see the README of each). For guided installation or upgrades, run `deploy/install-hook.sh` (Linux) or `deploy\install-hook.cmd` (Windows), which download and verify `SHA256SUMS` for both hook JARs, safely merge Codex, Claude, and Antigravity hook configurations with backups, and run a live smoke test. The hook
needs a Java 25 runtime on the `PATH` of the client host; with no hook, or a failing hook, a write is refused with
`HOST_ATTESTATION_REQUIRED`.

The hook stamps only the thirteen write tools and five attachment tools, named `mcp__<server>__ledger_<op>` or `mcp__<server>__<server>_ledger_<op>`
(Codex Apps: `mcp__codex_apps__app_<app>_ledger_<op>`). The value of `_hostAttestation` is `<vendor>:<session-id>` (for example
`claude:1f2e3d4c` or `codex:9a8b7c6d`). If the model supplies `_hostAttestation` itself, the hook refuses the call (`RESERVED_FIELD`).

- The resident program is started by the first call and needs no installation step: when the address file is missing, names a process
  that is gone (the process id and the start time must both match, so a reused id is not taken for it) or does not answer, the client
  starts `java -jar` of a copy of the program, and only one starts even when several calls arrive together (a lock on the state
  folder; the program holds a second lock of its own). The first call takes about a second, the later ones about a tenth of a second
  (measurements in the release notes). The program keeps running after the call and ends by itself after 15 minutes without a call
  (`LEDGER_HOOK_RESIDENT_IDLE_TIMEOUT`, an ISO-8601 duration such as `PT30M`), or with `java -jar ledger-client-hook.jar stop`.
- Its state folder, `~/.veridger/resident/<id>/`, holds the address file (the port,
  the process id and start time, the build, and a secret that is new for every start), the two lock files and `resident.log`, which
  has the output of the program and never a secret. The program listens on `127.0.0.1` only, at a port that the system chooses. Every
  request must carry the secret of the address file; it is compared in constant time and a request without it is refused with 401, so
  another account of the same PC cannot call the program. The folder and its files are owner-only (POSIX permissions, or an ACL for
  the owner, chosen by what the file system can do).
- The program runs from a copy in `~/.veridger/resident-runtime/<build>/`, so a running program never locks the jars of the
  plugin folder: the plugin can be updated or removed at any time. A client whose program file is another build (an updated plugin)
  asks the running program to end, ends it if it does not, and starts the new one.
- If the program cannot be reached or started, the call fails the way an unanswerable call always failed: exit code 2 and, on the
  standard error, `ledger: the hook program is not available` with the reason; the server then refuses the write with
  `HOST_ATTESTATION_REQUIRED`. Read `resident.log` in the state folder; check that `ledger-client-hook.jar` and
  `ledger-hook-resident.jar` are in the same folder and that `java` is JDK 25 or later. `java -jar ledger-client-hook.jar stop` ends a
  program that behaves oddly; the next call starts a new one. The folders `resident` and `resident-runtime` can be deleted while no
  program runs.
- The server validates the `_hostAttestation` author format (`HOST_ATTESTATION_REQUIRED`, `AUTHOR_ID_FORMAT`) before checking the
  session's live grants (`session_grant`).
- Limits: network-level authentication relies on `ledger.mcp.token` (plus `Host` and `Origin` allow lists), while the client hook
  prevents the model from spoofing `_hostAttestation` locally. Keep `ledger.mcp.token` restricted to trusted owner hosts.

## Upgrading from 1.x

1.x was a DBHub and Node implementation on PostgreSQL functions; 2.0.0 replaces all of it. Existing data is moved, not converted in
place. The 1.x schema stays and can be used to go back. See the [release notes](docs/release-notes/2.0.0.md) for the contract changes.

A caller of 1.x has to make these changes to its tool calls; 2.0 accepts no old form next to the new one:

- `request_id` and `batch_request_id` are replaced by the required `operationKey` (1 to 128 characters) on every write tool; the old
  keys are refused as unknown arguments, a call without `operationKey` is refused, and the server never makes a key up. An operation inside
  `ledger_batch` carries no key of its own.
- The `author_id` argument is gone; the author is the host attestation.
- 1.x `fields` maps are replaced by flat tool parameters, and an object or array argument (`ops`, `files`) must be sent as a JSON object or array, not as a JSON document in a string.
- The text `null` is a text, not a missing argument; omit an argument that has no value.

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
| `stop-1x` | `stop-1x [--name=<instance>] [--platform=<platform>] [--unit-dir=<dir>] [--env=<file> [--mcp-host=<host>] [--admin-host=<host>]]` | first step: stop and disable the two services of 1.x, `<name>` and `<name>-admin`, and keep each definition as a backup next to it (`<file>.1x-backup`); with `--env` it first checks the public host names of 1.x |
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
     `127.0.0.1`, `localhost` and the one name of `--mcp-host` and the one name of `--admin-host` only (the installer takes one host name for each
     service), so a name of the file that the clients still use and that is not carried would be refused with HTTP 403 right after the migration.
     With `--env=<file>` the command therefore tells, before it stops anything, each public host name of the file that the option does not carry
     as a name that 2.0 no longer accepts, naming the host names and the option; it refuses nothing for them and goes on. Give the one name that
     your proxy passes to the option (`--mcp-host=<name>`, one host name of the installer's form: lower case, no port, no list; `--mcp-host` can be given
     without `--admin-host`), and give the same options to the installer. The loopback names need no option, and nothing else of the file
     is printed. The two host options without `--env` are refused.
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
   sudo sh -c 'runuser -u veridger -- java -jar dist/ledger-migrate-1x.jar import-secrets-1x --replace --stdin < /etc/pg-ledger-mcp/env'
   ```

   - **What it reads.** The 1.x environment file, given with `--stdin` (as above) or with `--env=<file>` for an operator who can read the file as the
     service user; exactly one of the two. Only the token of the agents is read, and its value is checked before anything is written (at least
     16 characters). It uses no database.
   - **What it changes.** The installer has generated tokens of its own already, so without `--replace` a secret that exists is kept. With `--replace`
     the two secrets of the agents take the 1.x value: `DBHUB_AUTH_TOKEN` of 1.x becomes `ledger.mcp.token` and `ledger.admin.agent-token`.
     The clients of 1.x keep working. Only the names of the secrets are printed, never a value.
   - **The admin token is not imported.** `PGLEDGER_ADMIN_TOKEN` of 1.x is not carried over (when the 1.x environment has it, the command says so): the
     admin token of 2.0 is the `ledger.admin.token` that the installer made with `token-generate` (256 bits from `SecureRandom`, shown once), because
     a token chosen by hand may be weaker and the limit on credential checks of 2.0 is a delay that counts on a token that cannot be guessed. It stays
     the bearer of the read-only endpoints (it signs a browser in only with `ledger.admin.sign-in=token`).
   - For an instance with another name the service user is that name (`runuser -u <name>`), and the tool takes `--ledger.secrets.instance=<name>`.

   The services read their secrets at start, so restart the two services of 2.0:

   ```sh
   sudo systemctl restart veridger veridger-admin
   ```

6. **Import the ledger with `import-1x`.** Store the password of a read-only account of the 1.x database as the service user, then run the import
   as the DDL account; the settings of the 1.x source are described in the table below:

   ```sh
   sudo runuser -u veridger -- java -jar dist/ledger-cli.jar secret-set ledger.import.source.password
   sudo runuser -u veridger -- java -jar dist/ledger-migrate-1x.jar import-1x --owner-account=<ddl-account> --ledger.import.source.url=<jdbc-url> --ledger.import.source.username=<account>
   ```

   The import writes the whole ledger; `--owner-account=<account>` runs it as the DDL account, whose
   password is typed with hidden input and is not stored (`--stdin` takes it from the first line of standard input instead and needs `--owner-account`).
   It reads the 1.x schema read only and writes the new schema in one transaction; see What the import does below.
7. **Review imported grants.** The grants of 1.x are copied directly and work as soon as the client hook stamps the session author id.
8. **Check.** Run `ledger-cli mcp-check --url=<its public URL>` and `import-1x --verify-only` (it needs the settings of the 1.x source and
   changes nothing), and run `ledger-cli passkey-code` and register your first passkey in the admin web (the admin token of the installer signs a
   browser in only with `ledger.admin.sign-in=token`; the default is `passkey`). Clients: the tools `execute_sql_reader` and `search_objects_reader` were removed; the read tools replace them. The results of the write
   tools keep their keys without the `rows[0].result` wrapping. Install the hook (the two jars of the plugin folder and a Java 25 runtime) on every client host before the server is switched.
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
    sudo runuser -u veridger -- java -jar dist/ledger-migrate-1x.jar retire-1x --ledger.import.source.username=<owner-account>
    ```

    It checks that the ledger of 2.0 still holds what 1.x held (2.0 may have been used since: the rows it added and the counters it
    moved on are not looked at, a row it edited is accepted, and history, cases and every row still at its imported version must be as
    imported; `import-1x --verify-only` is stricter and reports what 2.0 added or changed as a difference), that no other session runs as a 1.x
    role or holds a lock in the 1.x schema and that the account owns the objects; it prints every object in the order it is dropped and
    the SHA-256 of that list, and changes nothing. Read the list, then run `retire-1x --confirm=<sha256> --backup-tested`:

    ```sh
    sudo runuser -u veridger -- java -jar dist/ledger-migrate-1x.jar retire-1x --ledger.import.source.username=<owner-account> --confirm=<sha256> --backup-tested
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
- The grants of 1.x are copied without a session key (step 7). The request receipts of 1.x are not carried over: every write of 2.0 needs an
  `operationKey` and refuses the old `request_id` as an unknown argument, so a 1.x answer that was lost cannot cause a second write
  in 2.0. The `request_id` of a 1.x history row is kept as its `operation_key`. The history rows that come from 1.x
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
(an installation with `ledger.admin.oidc.*` settings and no `ledger.admin.sign-in` is refused at start with a message that names it),
and give the one e-mail address of the owner, `ledger.admin.oidc.owner-email`, with the issuer it counts for,
`ledger.admin.oidc.issuer`. If you sign in with a passkey (`ledger.admin.sign-in=passkey`, the default), enroll it once with
`passkey-code`; once signed in, owner mutations are protected by the session and CSRF token without per-action passkey re-approval.

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

The connection is set with Gradle properties, on the command line or in `gradle.properties`. The JDBC URL and account names below are
the defaults; the two password file properties have no repository default (no machine path is hardcoded in `build.gradle.kts`):

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

- `ledger-domain`, `ledger-attestation`, `ledger-files`, `ledger-database`, `ledger-application`, `ledger-mcp-server`, `ledger-admin-web`,
  `ledger-cli`, `ledger-client-hook`, `ledger-migrate-1x`: the modules above, each with `src/main` and `src/test`; `ledger-system-test` and `ledger-arch-checks` have `src/test` only.
- `clients/claude`, `clients/codex`, `clients/antigravity`: the manifests and README of each client plugin; the build stages them with
  the hook jar into `ledger-client-hook/build/client-plugins`, with both jars of the hook.
- `docs/`: the test case catalogue, the release note format and the release notes.
- `gradle/`, `build.gradle.kts`, `settings.gradle.kts`: the build.
- `deploy/`: the two installers, `install.sh` (Linux) and `install.cmd` (Windows).
- The 1.x implementation (DBHub, Node and PostgreSQL functions) is not part of 2.x or later and is no longer in the tree. Its source is available at the tag `v1.1.0`.

## License

From version 2.0, veridger is licensed under the GNU Affero General Public License v3.0 or (at your option) any later version (`AGPL-3.0-or-later`); see [LICENSE](LICENSE), [ADDITIONAL-TERMS.md](ADDITIONAL-TERMS.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Copyright (C) 2026 Jaeseong Song

`AGPL-3.0-or-later` applies to 2.0.0 and to every release published after it, including any later maintenance release of the 1.x line. The MIT License continues to apply only to the copies already published under it, that is v1.0.0 and v1.1.0.

Under section 13 of the license, whoever runs a modified version as a network service must offer the users of that service the corresponding source code.
The admin web shows a link to the source code in the footer of its pages; its address is the setting `ledger.source.url`, by default the
public repository of the project. Whoever runs a modified version sets `ledger.source.url` to the place where the corresponding source of that
modified version is offered. The setting takes an `http` or `https` address; the answers of the MCP server do not carry it.
