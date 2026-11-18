// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.mcp.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.pgledger.application.write.BatchOpInput;

/**
 * One operation inside {@code ledger_batch} on the wire: {@code op} selects the variant, and the whole batch shares a
 * single top-level {@code operationKey} (any {@code operationKey} inside an operation is rejected as an unknown key).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "op")
@JsonSubTypes({
    @JsonSubTypes.Type(value = BatchOp.Save.class, name = "save"),
    @JsonSubTypes.Type(value = BatchOp.Transition.class, name = "transition"),
    @JsonSubTypes.Type(value = BatchOp.RecordDeliverable.class, name = "record_deliverable"),
    @JsonSubTypes.Type(value = BatchOp.RecordCase.class, name = "record_case"),
    @JsonSubTypes.Type(value = BatchOp.Link.class, name = "link"),
    @JsonSubTypes.Type(value = BatchOp.Correct.class, name = "correct")
})
public sealed interface BatchOp {

    BatchOpInput toInput();

    record Save(@JsonProperty("entity") String entity, @JsonProperty("fields") SaveFields fields,
            @JsonProperty("reason") String reason, @JsonProperty("id") String id,
            @JsonProperty("expected_version") Long expectedVersion) implements BatchOp {

        @Override
        public BatchOpInput toInput() {
            return new BatchOpInput.Save(entity, fields == null ? null : fields.toInput(), reason, id, expectedVersion);
        }
    }

    record Transition(@JsonProperty("entity") String entity, @JsonProperty("id") String id,
            @JsonProperty("expected_version") Long expectedVersion, @JsonProperty("to") String to,
            @JsonProperty("reason") String reason, @JsonProperty("evidence_uri") String evidenceUri,
            @JsonProperty("evidence_sha256") String evidenceSha256, @JsonProperty("merged_into") String mergedInto,
            @JsonProperty("attachment_id") String attachmentId) implements BatchOp {

        @Override
        public BatchOpInput toInput() {
            return new BatchOpInput.Transition(entity, id, expectedVersion, to, reason, evidenceUri, evidenceSha256,
                    mergedInto, attachmentId);
        }
    }

    record RecordDeliverable(@JsonProperty("target_entity") String targetEntity,
            @JsonProperty("target_id") String targetId, @JsonProperty("kind") String kind,
            @JsonProperty("deliverable_ref") String deliverableRef, @JsonProperty("reason") String reason,
            @JsonProperty("location") String location, @JsonProperty("summary") String summary,
            @JsonProperty("check_ref") String checkRef, @JsonProperty("check_result") String checkResult,
            @JsonProperty("checked_at") String checkedAt, @JsonProperty("mark_done") Boolean markDone,
            @JsonProperty("attachment_id") String attachmentId) implements BatchOp {

        @Override
        public BatchOpInput toInput() {
            return new BatchOpInput.RecordDeliverable(targetEntity, targetId, kind, deliverableRef, reason, location,
                    summary, checkRef, checkResult, checkedAt, markDone, attachmentId);
        }
    }

    record RecordCase(@JsonProperty("issue_id") String issueId, @JsonProperty("observation") String observation,
            @JsonProperty("reason") String reason, @JsonProperty("evidence") String evidence,
            @JsonProperty("source_quote") String sourceQuote, @JsonProperty("observed_at") String observedAt,
            @JsonProperty("attachment_id") String attachmentId) implements BatchOp {

        @Override
        public BatchOpInput toInput() {
            return new BatchOpInput.RecordCase(issueId, observation, reason, evidence, sourceQuote, observedAt,
                    attachmentId);
        }
    }

    record Link(@JsonProperty("kind") String kind, @JsonProperty("a") String a, @JsonProperty("b") String b,
            @JsonProperty("reason") String reason, @JsonProperty("remove") Boolean remove) implements BatchOp {

        @Override
        public BatchOpInput toInput() {
            return new BatchOpInput.Link(kind, a, b, reason, remove);
        }
    }

    record Correct(@JsonProperty("history_id") Long historyId, @JsonProperty("reason") String reason,
            @JsonProperty("attachment_id") String attachmentId) implements BatchOp {

        @Override
        public BatchOpInput toInput() {
            return new BatchOpInput.Correct(historyId, reason, attachmentId);
        }
    }
}
