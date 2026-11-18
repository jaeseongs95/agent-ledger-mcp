// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/**
 * Claude Code PreToolUse (https://code.claude.com/docs/en/hooks#pretooluse). The tool name is
 * {@code mcp__<server>__<tool>}; the host conversation id is {@code session_id}; {@code agent_id} is intentionally not
 * used. A stamped call answers with {@code updatedInput} and no permission decision, so the host's own permission policy
 * still decides; exit 2 blocks the call.
 *
 * <p>The tool names that are signed for are {@code mcp__<server>__<tool>}, or {@code mcp__<server>__<server>_<tool>}
 * when the tool reaches the host through a gateway that prefixes the tool names with the name of its upstream (the portal
 * of the deployment names the upstream like the server, {@code mcp__ledger__ledger_ledger_issue_save}). The tool is one of the
 * thirteen {@code ledger_<op>} tools or one of the five attachment tools {@code artifact_begin}, {@code artifact_chunk},
 * {@code artifact_finish}, {@code artifact_status}, {@code artifact_read}, exactly. The prefix before the tool must be
 * exactly the server segment: a look-alike server that names its tool {@code other_ledger_issue_save} or
 * {@code other_artifact_begin} is not signed for. The server segment is made of {@code A-Za-z0-9_-} (the host's own
 * rule: no dot). The Codex Apps namespace belongs to Codex alone and is not signed for here.
 */
@Component
record ClaudeDialect(JsonCodec codec) implements HostDialect {

    private static final String EVENT_NAME = "PreToolUse";
    /** Group 1 is the server, group 2 the canonical tool name; compiled once. */
    private static final Pattern TOOL_NAME = Pattern.compile("^mcp__([A-Za-z0-9_-]{1," + McpToolName.MAX_SERVER_LENGTH
            + "}?)__(?:\\1_)?" + WriteTool.alternation() + "$");

    @Override
    public Client client() {
        return Client.CLAUDE;
    }

    @Override
    public Intake read(byte[] stdin) throws JacksonException {
        return Intake.ofFlatEvent(codec.readEvent(stdin, FlatEvent.class), ClaudeDialect::toolName);
    }

    /** {@code mcp__<server>__[<server>_]<tool>}. */
    private static Optional<McpToolName> toolName(String hostToolName) {
        Matcher matcher = TOOL_NAME.matcher(hostToolName);
        return matcher.matches() ? McpToolName.ofName(matcher.group(1), matcher.group(2)) : Optional.empty();
    }

    @Override
    public Response malformedInput() {
        return new Response.Failure(Refusal.MALFORMED_INPUT.message());
    }

    @Override
    public Response notLedgerCall() {
        return new Response.Document(new HostDocument.Empty());
    }

    @Override
    public Response deny(Refusal reason) {
        return document(new HostDocument.PreToolUse.Specific(EVENT_NAME, "deny", reason.message(), null));
    }

    @Override
    public Response allow(Intake.Attest call, Verdict.Stamped stamped) {
        return document(new HostDocument.PreToolUse.Specific(EVENT_NAME, null, null, stamped.arguments()));
    }

    private static Response document(HostDocument.PreToolUse.Specific specific) {
        return new Response.Document(new HostDocument.PreToolUse(specific));
    }
}
