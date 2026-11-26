// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.json;

import java.util.List;
import org.agentledger.domain.error.InfrastructureCode;
import org.agentledger.domain.error.InfrastructureException;
import org.agentledger.domain.value.JsonDocument;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one JSON writer and reader of the application: records become snake_case JSON text for the long text
 * columns (history changes, before_data, receipt responses) and are read back into records. Nothing here builds
 * JSON by hand and no generic tree is exposed.
 */
@Component
public final class LedgerJson {

    private final JsonMapper mapper;

    /**
     * Derives the mapper of the ledger from the {@code JsonMapper} bean of Spring Boot: the stored form is
     * snake_case whatever the application configures for its own responses.
     */
    public LedgerJson(JsonMapper boot) {
        this.mapper = boot.rebuild().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    }

    public JsonDocument write(Object value) {
        try {
            return JsonDocument.of(mapper.writeValueAsString(value));
        } catch (JacksonException e) {
            throw new InfrastructureException(InfrastructureCode.INTERNAL_ERROR, List.of(), e);
        }
    }

    public <T> T read(JsonDocument document, Class<T> type) {
        return read(document.text(), type);
    }

    public <T> T read(String text, Class<T> type) {
        try {
            return mapper.readValue(text, type);
        } catch (JacksonException e) {
            throw new InfrastructureException(InfrastructureCode.INTERNAL_ERROR, List.of(), e);
        }
    }
}
