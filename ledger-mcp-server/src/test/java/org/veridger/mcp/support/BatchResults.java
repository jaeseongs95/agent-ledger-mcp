// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.support;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.veridger.application.write.WriteStatus;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Deserialized {@code data} payload of {@code ledger_batch}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BatchResults(@JsonProperty("status") WriteStatus status, @JsonProperty("count") int count,
        @JsonProperty("results") List<Entry> results) {

    public BatchResults {
        results = List.copyOf(results);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Entry(WriteStatus status, String entity, String id, Long version, Long historyId, String author,
            String login, String from, String to, String targetEntity, String targetId, Long deliverableId,
            String targetState, String ownerActorId, Long transitionHistoryId, Long caseId, String issue,
            Boolean removed, Long corrects) {
    }
}
