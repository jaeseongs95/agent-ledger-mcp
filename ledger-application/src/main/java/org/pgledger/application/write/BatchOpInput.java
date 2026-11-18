package org.pgledger.application.write;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotNull;

/**
 * One operation inside {@code ledger_batch}; {@code op} selects the concrete record.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "op")
@JsonSubTypes({
    @JsonSubTypes.Type(value = BatchOpInput.Save.class, name = "save"),
    @JsonSubTypes.Type(value = BatchOpInput.Transition.class, name = "transition"),
    @JsonSubTypes.Type(value = BatchOpInput.RecordDeliverable.class, name = "record_deliverable"),
    @JsonSubTypes.Type(value = BatchOpInput.RecordCase.class, name = "record_case"),
    @JsonSubTypes.Type(value = BatchOpInput.Link.class, name = "link"),
    @JsonSubTypes.Type(value = BatchOpInput.Correct.class, name = "correct")
})
public sealed interface BatchOpInput {

    String REQUIRED = "{reason.REQUIRED}";

    record Save(@NotNull(message = REQUIRED) String entity, @NotNull(message = REQUIRED) FieldsInput fields,
            @NotNull(message = REQUIRED) String reason, String id, Long expectedVersion) implements BatchOpInput {
    }

    record Transition(@NotNull(message = REQUIRED) String entity, @NotNull(message = REQUIRED) String id,
            @NotNull(message = REQUIRED) Long expectedVersion, @NotNull(message = REQUIRED) String to,
            @NotNull(message = REQUIRED) String reason, String evidenceUri, String evidenceSha256, String mergedInto,
            String attachmentId) implements BatchOpInput {
    }

    record RecordDeliverable(@NotNull(message = REQUIRED) String targetEntity,
            @NotNull(message = REQUIRED) String targetId, @NotNull(message = REQUIRED) String kind,
            @NotNull(message = REQUIRED) String deliverableRef, @NotNull(message = REQUIRED) String reason,
            String location, @NotNull(message = REQUIRED) String summary, String checkRef, String checkResult,
            String checkedAt, Boolean markDone, String attachmentId) implements BatchOpInput {
    }

    record RecordCase(@NotNull(message = REQUIRED) String issueId, @NotNull(message = REQUIRED) String observation,
            @NotNull(message = REQUIRED) String reason, String evidence, String sourceQuote, String observedAt,
            String attachmentId) implements BatchOpInput {
    }

    record Link(@NotNull(message = REQUIRED) String kind, @NotNull(message = REQUIRED) String a,
            @NotNull(message = REQUIRED) String b, @NotNull(message = REQUIRED) String reason, Boolean remove)
            implements BatchOpInput {
    }

    record Correct(@NotNull(message = REQUIRED) Long historyId, @NotNull(message = REQUIRED) String reason,
            String attachmentId) implements BatchOpInput {
    }
}
