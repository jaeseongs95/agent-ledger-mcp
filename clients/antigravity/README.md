# pg-ledger-mcp plugin for Antigravity

[한국어](README.ko.md)

This directory is an Antigravity plugin. It proves the author of the ledger write calls that an Antigravity conversation makes. The three clients (Claude Code, Codex and Antigravity) use the same executable jar, `ledger-client-hook.jar`; the first argument selects the client. The plugin contains no MCP connection settings and no credentials.

## How it works

`plugin.json` and `hooks.json` are at the root of the plugin. The command of a plugin hook runs in the plugin root, so `hooks.json` runs `java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Xlog:disable -jar "./lib/ledger-client-hook.jar" antigravity` before a tool call. The matcher selects `call_mcp_tool` and the direct MCP names of the write tools (`mcp:...ledger_<op>`); the hook checks the tool name again. The hook handles only the thirteen write tools (`issue_save`, `spec_save`, `task_save`, `criterion_save`, `transition`, `record_deliverable`, `record_case`, `link`, `correct`, `batch`, `review`, `review_correct` and `whoami`) and the five attachment tools (`artifact_begin`, `artifact_chunk`, `artifact_finish`, `artifact_status` and `artifact_read`) and signs only names that show the server. The attachment tools carry no `operationKey` in the claim, like `whoami`:

- a direct call, `mcp:<server><:|/|_>[<server>_]ledger_<op>` or `mcp:<server><:|/|_>[<server>_]artifact_<op>`
- a `call_mcp_tool` call whose `ServerName` is a server name (1 to 100 characters of `A-Za-z0-9._-`) and whose `ToolName` is `ledger_<op>`, `artifact_<op>`, `<ServerName>_ledger_<op>` or `<ServerName>_artifact_<op>`

A name without a server, such as `mcp:ledger_issue_save`, is not signed. Other tools and the read tools pass unchanged.

For a write call the hook adds the reserved argument `_hostAttestation`. Its value is a signature, made with the Ed25519 session key of the author `gemini:<conversationId>` for the MCP server of the call. The signature covers the tool, the `operationKey` of the call, the SHA-256 digest of the canonical form (RFC 8785) of all arguments, and the time. The hook reads the `operationKey` of the call from its arguments and never makes one up (a call without it is refused by the server). For a direct call the hook answers with `overwrite`, which Antigravity merges into the arguments of the tool; for a `call_mcp_tool` call it returns the corrected `Arguments` only. `Arguments` is an object or a string that holds a JSON object, and its type is kept. The other arguments are kept.

The hook answers `allow` and never sets `auto_approve` or `permissionOverrides`, so the permission policy of Antigravity applies unchanged. The call is denied when the model supplies `_hostAttestation` itself or when the `conversationId` is not valid. Malformed JSON is also denied explicitly, with exit code 0. Whether Antigravity gives a subagent the `conversationId` of its parent is not confirmed; the hook does not guess it and adds no identity or permission layer for subagents.

## Requirements

The hook command looks up `java` on the `PATH`, so the host that runs Antigravity needs JDK 25 or later on its `PATH`. Without `java` the command fails, and with an older JDK the JVM refuses the jar with `UnsupportedClassVersionError`. In both cases no `_hostAttestation` is added, and the server refuses the write with `HOST_ATTESTATION_REQUIRED`. A failing hook therefore never lets a write without a proven author be stored. This refusal of the server is the only defence, because Antigravity ignores a hook process that exits with an error.

## Installation

The jar is not committed to the repository. `./gradlew :ledger-client-hook:assemble` creates `ledger-client-hook/build/client-plugins/antigravity/`, a plugin directory that holds the light client as `lib/ledger-client-hook.jar` and the resident program as `lib/ledger-hook-resident.jar`; both jars must stay next to each other. Copy this directory to the plugin location of Antigravity.

To use the hook as a project hook, merge it into the named hook group of `<workspace>/.agents/hooks.json` and replace the path of the jar with its absolute path. The global file is `~/.gemini/config/hooks.json`. Do not overwrite an existing configuration and do not install the hook twice.

## Registering the key of a session

1. The first write call of an author for a server creates the key pair of that author and server. The keys are kept in `~/.pg-ledger-mcp/session-keys`, or the absolute path in `LEDGER_HOOK_KEY_DIR`. The hook creates a new key folder and new key files owner-only (on Windows, with an ACL for its owner only), and reads an existing folder and key file whoever may reach or own them (a link is followed): the personal edition trusts the whole PC. A directory where the key file belongs, and an empty key file, are refused.
2. `java -jar lib/ledger-client-hook.jar session-key <server> <author>` prints the server, the author, the public key and its fingerprint. `<server>` is the name of the MCP server as Antigravity shows it in the tool name or in `ServerName`.
3. The owner compares the fingerprint with the one the session showed and registers the public key with `key-register` of `ledger-cli`, or on the grants page of the admin web. Only then do the grants of the author work.

The key belongs to one server and one author. A signature made for a test server, or for a server with a similar name, never verifies on the production server; each server gets its own registration. The server checks the signature, the tool, the `operationKey`, the arguments and the time before it looks at any grant.

## The resident program

The light client does not sign by itself. It sends the input of the host over the loopback to the resident program, `ledger-hook-resident.jar` in the same folder, and prints what that answers; the contract with the host is the one of the hook before: the host JSON on the standard input, only the answer on the standard output (no JVM and no log line), the exit codes, and what a failure means.

- The first call starts the program when none runs (about one second); the later calls take about a tenth of a second. It keeps running after the call and ends by itself after 15 minutes without a call (`LEDGER_HOOK_RESIDENT_IDLE_TIMEOUT`, an ISO-8601 duration such as `PT30M`), or when you run `java -jar lib/ledger-client-hook.jar stop`.
- It listens on `127.0.0.1` only. Every request must carry a secret that is new for every start and that lies only in the address file of its state folder, `~/.pg-ledger-mcp/resident/<id>/`; the folder and its files are owner-only. Its output is in `resident.log` of that folder.
- It runs from a copy in `~/.pg-ledger-mcp/resident-runtime/<build>/`, so an update or the removal of the plugin is never blocked by a running program. A client of another build (an updated plugin) replaces the running program.
- If the program cannot be reached or started, the call fails with exit code 2 and the line `ledger: the hook program is not available`, followed by the reason, on the standard error; the server then refuses the write with `HOST_ATTESTATION_REQUIRED`. Read `resident.log` and check that both jars are in `lib` and that `java` is JDK 25 or later.

## Limits

Another process of the same operating system user can read the key file, so isolation inside one user needs separate accounts. The real shape of `Arguments` and the reception of the corrected arguments by the server need their own confirmation in the native client, and so do a security review and the permission review before production use.

## References

- [PreToolUse input, output and configuration paths](https://antigravity.google/docs/hooks/#pretooluse)
- [Plugin layout, command directory and failure handling](https://antigravity.google/docs/build-plugins/#lifecycle-hooks)
- [Client hooks in the main README](../../README.md)
