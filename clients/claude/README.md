# veridger plugin for Claude Code

[한국어](README.ko.md)

This directory is a Claude Code plugin. It stamps the host session author on the ledger write calls that a Claude Code session makes. The three clients (Claude Code, Codex and Antigravity) use the same executable jar, `ledger-client-hook.jar`; the first argument selects the client.

## How it works

The `PreToolUse` hook of `hooks/hooks.json` runs `java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Xlog:disable -jar "${CLAUDE_PLUGIN_ROOT}/lib/ledger-client-hook.jar" claude` before each call of a ledger write tool. The hook handles only the thirteen write tools (`issue_save`, `spec_save`, `task_save`, `criterion_save`, `transition`, `record_deliverable`, `record_case`, `link`, `correct`, `batch`, `review`, `review_correct` and `whoami`) and the five attachment tools (`artifact_begin`, `artifact_chunk`, `artifact_finish`, `artifact_status` and `artifact_read`), named `mcp__<server>__<tool>` or `mcp__<server>__<prefix>_<tool>` with `<tool>` being `ledger_<op>` or `artifact_<op>`. The second form is used by a gateway that puts the name of the upstream server in front of the tool; `<prefix>` must be `<server>`, `ledger`, or `veridger`. The read tools and every other tool pass unchanged.

For a write or attachment call the hook adds the reserved argument `_hostAttestation` with the value `claude:<session_id>` and returns the changed input as `updatedInput`. The hook gives no permission decision, so the permission policy of Claude Code applies unchanged. The call is denied when the model supplies `_hostAttestation` itself or when the `session_id` of the host is not valid; malformed JSON blocks the call with exit code 2.

## Requirements

The hook command looks up `java` on the `PATH`, so the host that runs Claude Code needs JDK 25 or later on its `PATH`. Without `java` the command fails, and with an older JDK the JVM refuses the jar with `UnsupportedClassVersionError`. In both cases no `_hostAttestation` is added, and the server refuses the write with `HOST_ATTESTATION_REQUIRED`. A failing hook therefore never lets a write without a host-stamped author be stored.

## Installation

The jar is not committed to the repository. `./gradlew :ledger-client-hook:assemble` creates `ledger-client-hook/build/client-plugins/claude/`, a plugin directory that holds the light client as `lib/ledger-client-hook.jar` and the resident program as `lib/ledger-hook-resident.jar`; both jars must stay next to each other. Copy this directory to the plugin location of Claude Code.

## Granting access to a session

1. Configure Claude Code's MCP client to authenticate with the server's `ledger.mcp.token` Bearer token.
2. In the admin console (`/grants`) or with `ledger-cli grant`, grant project scopes to `claude:<session_id>`.
3. No per-session key registration or per-session Bearer token is needed; the session can call write tools immediately once granted.

## The resident program

The light client forwards the input of the host over the loopback to the resident program, `ledger-hook-resident.jar` in the same folder, and prints what that answers; the contract with the host is the one of the hook before: the host JSON on the standard input, only the answer on the standard output (no JVM and no log line), the exit codes, and what a failure means.

- The first call starts the program when none runs (about one second); the later calls take about a tenth of a second. It keeps running after the call and ends by itself after 15 minutes without a call (`LEDGER_HOOK_RESIDENT_IDLE_TIMEOUT`, an ISO-8601 duration such as `PT30M`), or when you run `java -jar lib/ledger-client-hook.jar stop`.
- It listens on `127.0.0.1` only. Every request must carry a secret that is new for every start and that lies only in the address file of its state folder, `~/.veridger/resident/<id>/`; the folder and its files are owner-only. Its output is in `resident.log` of that folder.
- It runs from a copy in `~/.veridger/resident-runtime/<build>/`, so an update or the removal of the plugin is never blocked by a running program. A client of another build (an updated plugin) replaces the running program.
- If the program cannot be reached or started, the call fails with exit code 2 and the line `ledger: the hook program is not available`, followed by the reason, on the standard error; the server then refuses the write with `HOST_ATTESTATION_REQUIRED`. Read `resident.log` and check that both jars are in `lib` and that `java` is JDK 25 or later.

## Limits

The hook prevents the model from spoofing `_hostAttestation` locally, while `ledger.mcp.token` protects the MCP endpoint on the network. Keep `ledger.mcp.token` restricted to trusted owner hosts.

## References

- [PreToolUse contract of Claude Code](https://code.claude.com/docs/en/hooks#pretooluse)
- [Client hooks in the main README](../../README.md)
