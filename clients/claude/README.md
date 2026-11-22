# pg-ledger-mcp plugin for Claude Code

[한국어](README.ko.md)

This directory is a Claude Code plugin. It proves the author of the ledger write calls that a Claude Code session makes. The three clients (Claude Code, Codex and Antigravity) use the same executable jar, `ledger-client-hook.jar`; the first argument selects the client.

## How it works

The `PreToolUse` hook of `hooks/hooks.json` runs `java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Xlog:disable -jar "${CLAUDE_PLUGIN_ROOT}/lib/ledger-client-hook.jar" claude` before each call of a ledger write tool. The hook handles only the thirteen write tools (`issue_save`, `spec_save`, `task_save`, `criterion_save`, `transition`, `record_deliverable`, `record_case`, `link`, `correct`, `batch`, `review`, `review_correct` and `whoami`) and the five attachment tools (`artifact_begin`, `artifact_chunk`, `artifact_finish`, `artifact_status` and `artifact_read`, which carry no `operationKey` in the claim, like `whoami`), named `mcp__<server>__<tool>` or `mcp__<server>__<server>_<tool>` with `<tool>` being `ledger_<op>` or `artifact_<op>`. The second form is used by a gateway that puts the name of the upstream server in front of the tool; the prefix must equal the server name exactly. The read tools and every other tool pass unchanged.

For a write call the hook adds the reserved argument `_hostAttestation` and returns the changed input as `updatedInput`. Its value is a signature, made with the Ed25519 session key of the author `claude:<session_id>` for the MCP server of the call. The signature covers the tool, the `operationKey` of the call, the SHA-256 digest of the canonical form (RFC 8785) of all arguments, and the time. The hook reads the `operationKey` of the call from its arguments and never makes one up (a call without it is refused by the server). The hook gives no permission decision, so the permission policy of Claude Code applies unchanged. The call is denied when the model supplies `_hostAttestation` itself or when the `session_id` of the host is not valid; malformed JSON blocks the call with exit code 2.

## Requirements

The hook command looks up `java` on the `PATH`, so the host that runs Claude Code needs JDK 25 or later on its `PATH`. Without `java` the command fails, and with an older JDK the JVM refuses the jar with `UnsupportedClassVersionError`. In both cases no `_hostAttestation` is added, and the server refuses the write with `HOST_ATTESTATION_REQUIRED`. A failing hook therefore never lets a write without a proven author be stored.

## Installation

The jar is not committed to the repository. `./gradlew :ledger-client-hook:assemble` creates `ledger-client-hook/build/client-plugins/claude/`, a plugin directory that holds the light client as `lib/ledger-client-hook.jar` and the resident program as `lib/ledger-hook-resident.jar`; both jars must stay next to each other. Copy this directory to the plugin location of Claude Code.

## Registering the key of a session

1. The first write call of an author for a server creates the key pair of that author and server. The keys are kept in `~/.pg-ledger-mcp/session-keys`, or the absolute path in `LEDGER_HOOK_KEY_DIR`. The hook creates a new key folder and new key files owner-only (on Windows, with an ACL for its owner only), and reads an existing folder and key file whoever may reach or own them (a link is followed): the personal edition trusts the whole PC. A directory where the key file belongs, and an empty key file, are refused.
2. `java -jar lib/ledger-client-hook.jar session-key <server> <author>` prints the server, the author, the public key and its fingerprint. `<server>` is the name of the MCP server as the host shows it in the tool name, for example `ledger` for `mcp__ledger__ledger_issue_save`.
3. The owner compares the fingerprint with the one the session showed and registers the public key with `key-register` of `ledger-cli`, or on the grants page of the admin web. Only then do the grants of the author work.

The key belongs to one server and one author. A signature made for a test server, or for a server with a similar name, never verifies on the production server; each server gets its own registration. The server checks the signature, the tool, the `operationKey`, the arguments and the time before it looks at any grant.

## The resident program

The light client does not sign by itself. It sends the input of the host over the loopback to the resident program, `ledger-hook-resident.jar` in the same folder, and prints what that answers; the contract with the host is the one of the hook before: the host JSON on the standard input, only the answer on the standard output (no JVM and no log line), the exit codes, and what a failure means.

- The first call starts the program when none runs (about one second); the later calls take about a tenth of a second. It keeps running after the call and ends by itself after 15 minutes without a call (`LEDGER_HOOK_RESIDENT_IDLE_TIMEOUT`, an ISO-8601 duration such as `PT30M`), or when you run `java -jar lib/ledger-client-hook.jar stop`.
- It listens on `127.0.0.1` only. Every request must carry a secret that is new for every start and that lies only in the address file of its state folder, `~/.pg-ledger-mcp/resident/<id>/`; the folder and its files are owner-only. Its output is in `resident.log` of that folder.
- It runs from a copy in `~/.pg-ledger-mcp/resident-runtime/<build>/`, so an update or the removal of the plugin is never blocked by a running program. A client of another build (an updated plugin) replaces the running program.
- If the program cannot be reached or started, the call fails with exit code 2 and the line `ledger: the hook program is not available`, followed by the reason, on the standard error; the server then refuses the write with `HOST_ATTESTATION_REQUIRED`. Read `resident.log` and check that both jars are in `lib` and that `java` is JDK 25 or later.

## Limits

Another process of the same operating system user can read the key file, so isolation inside one user needs separate accounts.

## References

- [PreToolUse contract of Claude Code](https://code.claude.com/docs/en/hooks#pretooluse)
- [Client hooks in the main README](../../README.md)
