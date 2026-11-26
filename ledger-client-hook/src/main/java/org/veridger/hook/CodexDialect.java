// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/**
 * Codex PreToolUse. The host conversation id is {@code session_id} (Codex also gives sub-agents the parent id). A stamped
 * call answers {@code allow} with {@code updatedInput}; a refusal answers {@code deny}. Malformed input is denied
 * explicitly with exit 0 (a failing hook process would not be a reliable block).
 *
 * <p>The tool names that are stamped:
 * <ul>
 * <li>{@code mcp__<server>__<tool>}, or {@code mcp__<server>__<prefix>_<tool>} when the tool reaches the host
 *     through a gateway that prefixes the tool names with the name of its upstream. The tool is {@code ledger_<op>} or
 *     one of the attachment tools {@code artifact_begin} ... {@code artifact_read}. The prefix before the tool
 *     must be either the server segment itself or one of Veridger's canonical gateway prefixes ({@code ledger} or
 *     {@code veridger}), so a look-alike or unrelated upstream is not stamped.
 * <li>The Codex Apps namespace {@code mcp__codex_apps__app_<app>_<tool>} (a real path: the tool of the app
 *     {@code <app>} reaches Codex as {@code app_<app>_<tool>} in the pseudo server {@code codex_apps}, for example
 *     {@code mcp__codex_apps__app_ledger_ledger_issue_save} for the app {@code ledger}). The server identity is
 *     {@code codex_apps.<app>}.
 * </ul>
 * The tool is one of the eighteen known ones, exactly.
 */
@Component
record CodexDialect(JsonCodec codec) implements HostDialect {

    private static final String EVENT_NAME = "PreToolUse";
    private static final String CODEX_APPS = "codex_apps";
    private static final int MAX_APP_LENGTH = McpToolName.MAX_SERVER_LENGTH - CODEX_APPS.length() - 1;
    /** Group 1 is the server, group 2 the canonical tool name; compiled once. */
    private static final Pattern TOOL_NAME = Pattern.compile("^mcp__([A-Za-z0-9_-]{1," + McpToolName.MAX_SERVER_LENGTH
            + "}?)__(?:(?:\\1|" + McpToolName.gatewayPrefixAlternation() + ")_)?" + WriteTool.alternation() + "$");
    /** Group 1 is the app, group 2 the canonical tool name; compiled once. */
    private static final Pattern APP_TOOL_NAME = Pattern.compile("^mcp__" + CODEX_APPS + "__app_([A-Za-z0-9][A-Za-z0-9_-]{0,"
            + (MAX_APP_LENGTH - 1) + "}?)_" + WriteTool.alternation() + "$");

    @Override
    public Client client() {
        return Client.CODEX;
    }

    @Override
    public Intake read(byte[] stdin) throws JacksonException {
        return Intake.ofFlatEvent(codec.readEvent(stdin, FlatEvent.class), CodexDialect::toolName);
    }

    /** The shapes of the list above: the plain server shape first, then the Codex Apps namespace. */
    private static Optional<McpToolName> toolName(String hostToolName) {
        Matcher plain = TOOL_NAME.matcher(hostToolName);
        if (plain.matches()) {
            return McpToolName.ofName(plain.group(1), plain.group(2));
        }
        Matcher app = APP_TOOL_NAME.matcher(hostToolName);
        if (app.matches()) {
            return McpToolName.ofName(CODEX_APPS + "." + app.group(1), app.group(2));
        }
        return Optional.empty();
    }

    @Override
    public Response malformedInput() {
        return deny(Refusal.MALFORMED_INPUT);
    }

    @Override
    public Response notLedgerCall() {
        return new Response.Silent();
    }

    @Override
    public Response deny(Refusal reason) {
        return document(new HostDocument.PreToolUse.Specific(EVENT_NAME, "deny", reason.message(), null));
    }

    @Override
    public Response allow(Intake.Attest call, Verdict.Stamped stamped) {
        return document(new HostDocument.PreToolUse.Specific(EVENT_NAME, "allow", null, stamped.arguments()));
    }

    private static Response document(HostDocument.PreToolUse.Specific specific) {
        return new Response.Document(new HostDocument.PreToolUse(specific));
    }
}
