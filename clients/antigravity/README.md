# agent-ledger-mcp plugin for Antigravity

[한국어](README.ko.md)

This directory is an Antigravity plugin. It stamps the host session author on the ledger write calls that an Antigravity conversation makes. The three clients (Claude Code, Codex and Antigravity) use the same executable jar, `ledger-client-hook.jar`; the first argument selects the client. The plugin contains no MCP connection settings and no credentials.

## How it works

`plugin.json` and `hooks.json` are at the root of the plugin. The command of a plugin hook runs in the plugin root, so `hooks.json` runs `java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Xlog:disable -jar "./lib/ledger-client-hook.jar" antigravity` before a tool call. The matcher selects `call_mcp_tool` and the direct MCP names of the write tools (`mcp:...ledger_<op>`); the hook checks the tool name again. The hook handles only the thirteen write tools (`issue_save`, `spec_save`, `task_save`, `criterion_save`, `transition`, `record_deliverable`, `record_case`, `link`, `correct`, `batch`, `review`, `review_correct` and `whoami`) and the five attachment tools (`artifact_begin`, `artifact_chunk`, `artifact_finish`, `artifact_status` and `artifact_read`) and stamps only names that show the server:

- a direct call, `mcp:<server><:|/|_>[<server>_]ledger_<op>` or `mcp:<server><:|/|_>[<server>_]artifact_<op>`
- a `call_mcp_tool` call whose `ServerName` is a server name (1 to 100 characters of `A-Za-z0-9._-`) and whose `ToolName` is `ledger_<op>`, `artifact_<op>`, `<ServerName>_ledger_<op>` or `<ServerName>_artifact_<op>`

A name without a server, such as `mcp:ledger_issue_save`, is not stamped. Other tools and the read tools pass unchanged.

For a write or attachment call the hook adds the reserved argument `_hostAttestation` with the value `gemini:<conversationId>`. For a direct call the hook answers with `overwrite`, which Antigravity merges into the arguments of the tool; for a `call_mcp_tool` call it returns the corrected `Arguments` only. `Arguments` is an object or a string that holds a JSON object, and its type is kept. The other arguments are kept.

The hook answers `allow` and never sets `auto_approve` or `permissionOverrides`, so the permission policy of Antigravity applies unchanged. The call is denied when the model supplies `_hostAttestation` itself or when the `conversationId` is not valid. Malformed JSON is also denied explicitly, with exit code 0. Whether Antigravity gives a subagent the `conversationId` of its parent is not confirmed; the hook does not guess it and adds no identity or permission layer for subagents.

## Requirements

The hook command looks up `java` on the `PATH`, so the host that runs Antigravity needs JDK 25 or later on its `PATH`. Without `java` the command fails, and with an older JDK the JVM refuses the jar with `UnsupportedClassVersionError`. In both cases no `_hostAttestation` is added, and the server refuses the write with `HOST_ATTESTATION_REQUIRED`. A failing hook therefore never lets a write without a host-stamped author be stored. This refusal of the server is the only defence, because Antigravity ignores a hook process that exits with an error.

## Installation

The jar is not committed to the repository. `./gradlew :ledger-client-hook:assemble` creates `ledger-client-hook/build/client-plugins/antigravity/`, a plugin directory that holds the light client as `lib/ledger-client-hook.jar` and the resident program as `lib/ledger-hook-resident.jar`; both jars must stay next to each other. Copy this directory to the plugin location of Antigravity.

To use the hook as a project hook, merge it into the named hook group of `<workspace>/.agents/hooks.json` and replace the path of the jar with its absolute path. The global file is `~/.gemini/config/hooks.json`. Do not overwrite an existing configuration and do not install the hook twice.

## Granting access to a session

1. Configure Antigravity's MCP client to authenticate with the server's `ledger.mcp.token` Bearer token.
2. In the admin console (`/grants`) or with `ledger-cli grant`, grant project scopes to `gemini:<conversationId>`.
3. No per-session key registration or per-session Bearer token is needed; the session can call write tools immediately once granted.

## The resident program

The light client forwards the input of the host over the loopback to the resident program, `ledger-hook-resident.jar` in the same folder, and prints what that answers; the contract with the host is the one of the hook before: the host JSON on the standard input, only the answer on the standard output (no JVM and no log line), the exit codes, and what a failure means.

- The first call starts the program when none runs (about one second); the later calls take about a tenth of a second. It keeps running after the call and ends by itself after 15 minutes without a call (`LEDGER_HOOK_RESIDENT_IDLE_TIMEOUT`, an ISO-8601 duration such as `PT30M`), or when you run `java -jar lib/ledger-client-hook.jar stop`.
- It listens on `127.0.0.1` only. Every request must carry a secret that is new for every start and that lies only in the address file of its state folder, `~/.agent-ledger-mcp/resident/<id>/`; the folder and its files are owner-only. Its output is in `resident.log` of that folder.
- It runs from a copy in `~/.agent-ledger-mcp/resident-runtime/<build>/`, so an update or the removal of the plugin is never blocked by a running program. A client of another build (an updated plugin) replaces the running program.
- If the program cannot be reached or started, the call fails with exit code 2 and the line `ledger: the hook program is not available`, followed by the reason, on the standard error; the server then refuses the write with `HOST_ATTESTATION_REQUIRED`. Read `resident.log` and check that both jars are in `lib` and that `java` is JDK 25 or later.

## Limits

The hook prevents the model from spoofing `_hostAttestation` locally, while `ledger.mcp.token` protects the MCP endpoint on the network. Keep `ledger.mcp.token` restricted to trusted owner hosts. The real shape of `Arguments` and the reception of the corrected arguments by the server need their own confirmation in the native client.

## References

- [PreToolUse input, output and configuration paths](https://antigravity.google/docs/hooks/#pretooluse)
- [Plugin layout, command directory and failure handling](https://antigravity.google/docs/build-plugins/#lifecycle-hooks)
- [Client hooks in the main README](../../README.md)
