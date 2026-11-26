// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;

/**
 * Verifies the {@code _hostAttestation} value stamped by the hook: in v4.0.0 the hook stamps the plain
 * {@code <vendor>:<session_id>} author identifier and never fabricates an {@code operationKey}.
 */
final class Attestations {

    record Stamp(String author) {

        String token() {
            return author;
        }
    }

    private Attestations() {}

    static Stamp verify(String author, String op, JsonNode sent, JsonNode carrier) {
        JsonNode node = carrier.path(Json.RESERVED_KEY);
        assertThat(node.isString()).as("_hostAttestation is a text").isTrue();
        String value = node.stringValue();
        assertThat(value).as("author").isEqualTo(author);
        if (!sent.has("operationKey")) {
            assertThat(carrier.has("operationKey")).as("no operationKey added for " + op).isFalse();
        }
        return new Stamp(value);
    }
}
