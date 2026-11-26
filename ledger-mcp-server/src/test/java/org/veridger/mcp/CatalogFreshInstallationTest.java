// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.mcp.support.McpFreshInstallationTest;
import org.veridger.mcp.support.McpSession;
import org.veridger.mcp.support.SchemaView;
import org.veridger.mcp.support.ToolNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/** MCC-013: a fresh installation starts with an empty ISSUE_CATEGORY group and instructs how to add codes. */
@McpFreshInstallationTest
class CatalogFreshInstallationTest {

    @Test
    @DisplayName("MCC-013: with no active category the tools are still listed and the category text says how to add one")
    void theEmptyCategoryGroupExplainsItself(McpSession session, @Autowired MessageSource messages) {
        SchemaView category = SchemaView.of(session.mapper(), session.tool("ledger_issue_save").inputSchema())
                .at("category").orElseThrow();
        String expected = messages.getMessage("catalog.empty", new Object[] {"ISSUE_CATEGORY"}, Locale.ENGLISH);

        assertThat(session.tools()).extracting(tool -> tool.name()).containsExactlyInAnyOrderElementsOf(ToolNames.all());
        assertThat(category.description()).contains(expected).contains("ledger-cli code-set ISSUE_CATEGORY");
        assertThat(category.allowed()).isEmpty();
    }
}
