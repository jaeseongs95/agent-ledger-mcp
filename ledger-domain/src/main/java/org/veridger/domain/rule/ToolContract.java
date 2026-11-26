// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.rule;

import java.util.List;
import java.util.stream.Stream;

/**
 * The canonical snake_case tool names of the ledger MCP server, aligned with Sennard {@code ApiResult.v1} and
 * {@code artifact_*} contracts. Checked by both server tests and {@code ledger-cli mcp-check}.
 */
public final class ToolContract {

    public static final List<String> WRITE = List.of(
            "ledger_issue_save", "ledger_spec_save", "ledger_task_save", "ledger_criterion_save",
            "ledger_transition", "ledger_record_deliverable", "ledger_record_case", "ledger_link",
            "ledger_correct", "ledger_batch", "ledger_review", "ledger_review_correct", "ledger_whoami");

    /** Bundle attachment tools aligned 1:1 with Sennard {@code ArtifactTools}. */
    public static final List<String> ATTACHMENT = List.of(
            "artifact_begin", "artifact_chunk", "artifact_finish", "artifact_status", "artifact_read");

    public static final List<String> READ = List.of(
            "ledger_get", "ledger_issue", "ledger_spec", "ledger_task", "ledger_criterion",
            "ledger_history", "ledger_search", "ledger_issues", "ledger_specs", "ledger_tasks",
            "ledger_criteria", "ledger_attention", "ledger_stats", "ledger_projects", "ledger_codes");

    private ToolContract() {
    }

    /** Every tool name: write tools, attachment tools, and read tools. */
    public static List<String> all() {
        return Stream.of(WRITE, ATTACHMENT, READ).flatMap(List::stream).toList();
    }
}
