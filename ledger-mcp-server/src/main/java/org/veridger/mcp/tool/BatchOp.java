// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.veridger.application.write.BatchOpInput;

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
    @JsonSubTypes.Type(value = BatchOp.Correct.class, name = "correct"),
    @JsonSubTypes.Type(value = BatchOp.ProjectVersion.class, name = "project_version")
})
public sealed interface BatchOp {

    BatchOpInput toInput(String defaultReason);

    String reason();

    default String reasonOr(String defaultReason) {
        return reason() == null ? defaultReason : reason();
    }

    record Save(@JsonProperty("entity") String entity, @JsonProperty("fields") SaveFields fields,
            @JsonProperty("reason") String reason, @JsonProperty("id") String id,
            @JsonProperty("expected_version") Long expectedVersion) implements BatchOp {

        @Override
        public BatchOpInput toInput(String defaultReason) {
            return new BatchOpInput.Save(entity, fields == null ? null : fields.toInput(), reasonOr(defaultReason), id,
                    expectedVersion);
        }
    }

    record Transition(@JsonProperty("entity") String entity, @JsonProperty("id") String id,
            @JsonProperty("expected_version") Long expectedVersion, @JsonProperty("to") String to,
            @JsonProperty("reason") String reason, @JsonProperty("evidence_uri") String evidenceUri,
            @JsonProperty("evidence_sha256") String evidenceSha256, @JsonProperty("merged_into") String mergedInto,
            @JsonProperty("attachment_id") String attachmentId) implements BatchOp {

        @Override
        public BatchOpInput toInput(String defaultReason) {
            return new BatchOpInput.Transition(entity, id, expectedVersion, to, reasonOr(defaultReason), evidenceUri,
                    evidenceSha256, mergedInto, attachmentId);
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
        public BatchOpInput toInput(String defaultReason) {
            return new BatchOpInput.RecordDeliverable(targetEntity, targetId, kind, deliverableRef,
                    reasonOr(defaultReason), location, summary, checkRef, checkResult, checkedAt, markDone, attachmentId);
        }
    }

    record RecordCase(@JsonProperty("issue_id") String issueId, @JsonProperty("observation") String observation,
            @JsonProperty("reason") String reason, @JsonProperty("evidence") String evidence,
            @JsonProperty("source_quote") String sourceQuote, @JsonProperty("observed_at") String observedAt,
            @JsonProperty("attachment_id") String attachmentId) implements BatchOp {

        @Override
        public BatchOpInput toInput(String defaultReason) {
            return new BatchOpInput.RecordCase(issueId, observation, reasonOr(defaultReason), evidence, sourceQuote,
                    observedAt, attachmentId);
        }
    }

    record Link(@JsonProperty("kind") String kind, @JsonProperty("a") String a, @JsonProperty("b") String b,
            @JsonProperty("reason") String reason, @JsonProperty("remove") Boolean remove) implements BatchOp {

        @Override
        public BatchOpInput toInput(String defaultReason) {
            return new BatchOpInput.Link(kind, a, b, reasonOr(defaultReason), remove);
        }
    }

    record Correct(@JsonProperty("history_id") Long historyId, @JsonProperty("reason") String reason,
            @JsonProperty("attachment_id") String attachmentId) implements BatchOp {

        @Override
        public BatchOpInput toInput(String defaultReason) {
            return new BatchOpInput.Correct(historyId, reasonOr(defaultReason), attachmentId);
        }
    }

    record ProjectVersion(@JsonProperty("project_id") String projectId, @JsonProperty("name") String name,
            @JsonProperty("reason") String reason, @JsonProperty("create") Boolean create,
            @JsonProperty("original_name") String originalName,
            @JsonProperty("description") String description, @JsonProperty("link_issue_ids") List<String> linkIssueIds,
            @JsonProperty("unlink_issue_ids") List<String> unlinkIssueIds) implements BatchOp {

        @Override
        public BatchOpInput toInput(String defaultReason) {
            return new BatchOpInput.ProjectVersion(projectId, name, reasonOr(defaultReason), create, originalName,
                    description, linkIssueIds, unlinkIssueIds);
        }
    }
}
