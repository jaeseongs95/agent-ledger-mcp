// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * R9-D0088: the texts the model reads say that a creation time bound of {@code ledger_search} leaves the criteria out (a
 * criterion has no creation time), in the description of the tool and in the two parameters, in both languages. The search
 * itself answers an empty page without a notice for criteria only with a bound; the rule is what the texts must tell.
 */
class SearchTimeBoundDocsTest {

    private static Properties bundle(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = SearchTimeBoundDocsTest.class.getResourceAsStream("/messages/" + name);
                InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    @ParameterizedTest
    @CsvSource({
        "mcp.properties,tool.ledger_search,no creation time",
        "mcp.properties,param.created_from,no creation time",
        "mcp.properties,param.created_to,no creation time",
        "mcp_ko.properties,tool.ledger_search,생성 시각이 없",
        "mcp_ko.properties,param.created_from,생성 시각이 없",
        "mcp_ko.properties,param.created_to,생성 시각이 없"})
    @DisplayName("R9-D0088: the text says that a criterion has no creation time and is left out when a time bound is given")
    void theTextStatesTheRule(String bundle, String key, String phrase) throws IOException {
        assertThat(bundle(bundle).getProperty(key)).contains(phrase);
    }
}
