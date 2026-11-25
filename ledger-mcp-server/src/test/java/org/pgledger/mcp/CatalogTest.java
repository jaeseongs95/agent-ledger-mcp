// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.domain.rule.ToolContract;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.ToolNames;

/** MCC-001: the 33 tools of the 2.0 server and their standard MCP ToolAnnotations. */
@McpServerTest
class CatalogTest {

    private static final Set<String> READ_ONLY = Stream.concat(
            ToolContract.READ.stream(),
            Stream.of("ledger_whoami", "artifact_status", "artifact_read"))
            .collect(Collectors.toUnmodifiableSet());

    @Test
    @DisplayName("MCC-001: tools/list holds exactly the 33 tools (13 write, 5 snake_case attachment, 15 read) with accurate ToolAnnotations")
    void theServerHasThirtyThreeToolsWithAnnotations(McpSession session) {
        assertThat(session.tools()).extracting(Tool::name).hasSize(33)
                .containsExactlyInAnyOrderElementsOf(ToolNames.all());
        for (Tool tool : session.tools()) {
            ToolAnnotations annotations = tool.annotations();
            assertThat(annotations).as("annotations of %s", tool.name()).isNotNull();
            assertThat(annotations.readOnlyHint()).as("readOnlyHint of %s", tool.name())
                    .isEqualTo(READ_ONLY.contains(tool.name()));
            assertThat(annotations.destructiveHint()).as("destructiveHint of %s", tool.name()).isFalse();
            assertThat(annotations.idempotentHint()).as("idempotentHint of %s", tool.name()).isTrue();
            assertThat(annotations.openWorldHint()).as("openWorldHint of %s", tool.name()).isFalse();
        }
    }
}
