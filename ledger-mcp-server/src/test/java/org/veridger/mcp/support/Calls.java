// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Typed MCP tool argument records for the 2.0 universal ledger tests.
 */
public final class Calls {

    private Calls() {
    }

    public static String freshKey() {
        return UUID.randomUUID().toString();
    }

    /** {@code fields} object used inside {@code save} operations of {@code ledger_batch}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Fields(String projectId, String parentId, String title, String category, String severity,
            String symptom, String rootCause, String summary, String acceptanceCriteria, String priority,
            String ownerActorId, String purpose, String successCondition, String originRef, String originQuote,
            String milestoneName, String goalLabel, String code, String criterionText) {

        public static Fields issue(String projectId, String title) {
            return new Fields(projectId, null, title, "ARC", "P2", "symptom of " + title, "cause of " + title,
                    null, null, null, null, null, null, null, null, null, null, null, null);
        }

        public static Fields spec(String projectId, String title) {
            return new Fields(projectId, null, title, null, null, null, null, "summary of " + title,
                    "criteria of " + title, "P1", null, null, null, null, null, null, null, null, null);
        }

        public static Fields task(String projectId, String title) {
            return new Fields(projectId, null, title, null, null, null, null, null, null, null, null,
                    "purpose of " + title, "success of " + title, null, null, null, null, null, null);
        }

        public static Fields criterion(String projectId, String milestoneName, String code) {
            return new Fields(projectId, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, milestoneName, null, code, "criterion " + code);
        }

        public static Fields titled(String title) {
            return new Fields(null, null, title, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record IssueSave(String projectId, String title, String category, String severity, String symptom,
            String rootCause, String ownerActorId, String id, Long expectedVersion, String reason,
            @JsonProperty("operationKey") String operationKey,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static IssueSave create(String projectId, String title, String author) {
            return new IssueSave(projectId, title, "ARC", "P2", "symptom of " + title, "cause of " + title,
                    null, null, null, Scenario.REASON, freshKey(), author);
        }

        public static IssueSave editTitle(String id, long version, String title, String author) {
            return new IssueSave(null, title, null, null, null, null, null, id, version, Scenario.REASON, freshKey(),
                    author);
        }

        public IssueSave withOperationKey(String newKey) {
            return new IssueSave(projectId, title, category, severity, symptom, rootCause, ownerActorId, id,
                    expectedVersion, reason, newKey, hostAttestation);
        }

        public IssueSave withCategory(String newCategory) {
            return new IssueSave(projectId, title, newCategory, severity, symptom, rootCause, ownerActorId, id,
                    expectedVersion, reason, operationKey, hostAttestation);
        }

        public IssueSave withOwner(String newOwner) {
            return new IssueSave(projectId, title, category, severity, symptom, rootCause, newOwner, id,
                    expectedVersion, reason, operationKey, hostAttestation);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SpecSave(String projectId, String parentId, String title, String summary, String acceptanceCriteria,
            String priority, String ownerActorId, String originRef, String originQuote, String id,
            Long expectedVersion, String reason, @JsonProperty("operationKey") String operationKey,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static SpecSave create(String projectId, String title, String author) {
            return new SpecSave(projectId, null, title, "summary of " + title, "criteria of " + title, "P1", null,
                    null, null, null, null, Scenario.REASON, freshKey(), author);
        }

        public static SpecSave editTitle(String id, long version, String title, String author) {
            return new SpecSave(null, null, title, null, null, null, null, null, null, id, version, Scenario.REASON,
                    freshKey(), author);
        }

        public SpecSave withId(String newId) {
            return new SpecSave(projectId, parentId, title, summary, acceptanceCriteria, priority, ownerActorId,
                    originRef, originQuote, newId, expectedVersion, reason, operationKey, hostAttestation);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TaskSave(String projectId, String title, String purpose, String successCondition,
            String ownerActorId, String originRef, String originQuote, String id, Long expectedVersion, String reason,
            @JsonProperty("operationKey") String operationKey,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static TaskSave create(String projectId, String title, String author) {
            return new TaskSave(projectId, title, "purpose of " + title, "success of " + title, null, null, null,
                    null, null, Scenario.REASON, freshKey(), author);
        }

        public static TaskSave editTitle(String id, long version, String title, String author) {
            return new TaskSave(null, title, null, null, null, null, null, id, version, Scenario.REASON, freshKey(),
                    author);
        }

        public TaskSave withId(String newId) {
            return new TaskSave(projectId, title, purpose, successCondition, ownerActorId, originRef, originQuote,
                    newId, expectedVersion, reason, operationKey, hostAttestation);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CriterionSave(String projectId, String id, String milestoneName, String goalLabel, String code,
            String criterionText, Long expectedVersion, String reason,
            @JsonProperty("operationKey") String operationKey,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static CriterionSave create(String projectId, String id, String milestoneName, String code,
                String author) {
            return new CriterionSave(projectId, id, milestoneName, null, code, "criterion " + code, null,
                    Scenario.REASON, freshKey(), author);
        }

        public static CriterionSave editText(String id, long version, String text, String author) {
            return new CriterionSave(null, id, null, null, null, text, version, Scenario.REASON, freshKey(), author);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Transition(String entity, String id, Long expectedVersion, String to, String reason,
            String evidenceUri, String evidenceSha256, String mergedInto,
            @JsonProperty("operationKey") String operationKey,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static Transition of(String entity, String id, long version, String to, String author) {
            return new Transition(entity, id, version, to, Scenario.REASON, null, null, null, freshKey(), author);
        }

        public Transition withEvidence(String uri) {
            return new Transition(entity, id, expectedVersion, to, reason, uri, evidenceSha256, mergedInto,
                    operationKey, hostAttestation);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecordDeliverable(String targetEntity, String targetId, String kind, String deliverableRef,
            String summary, String reason, String location, String checkRef, String checkResult, String checkedAt,
            Boolean markDone, @JsonProperty("operationKey") String operationKey,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static RecordDeliverable ofIssue(String issueId, String deliverableRef, String author) {
            return new RecordDeliverable("issue", issueId, "document", deliverableRef,
                    "deliverable summary for " + issueId, Scenario.REASON, "vault/docs", null, null, null, null,
                    freshKey(), author);
        }

        public static RecordDeliverable ofSpec(String specId, String deliverableRef, String author) {
            return new RecordDeliverable("spec", specId, "specification", deliverableRef,
                    "completed specification deliverable for " + specId, Scenario.REASON, "vault/specs", null, null,
                    null, true, freshKey(), author);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecordCase(String issueId, String observation, String reason, String evidence, String sourceQuote,
            String observedAt, @JsonProperty("operationKey") String operationKey,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static RecordCase of(String issueId, String observation, String author) {
            return new RecordCase(issueId, observation, Scenario.REASON, null, null, null, freshKey(), author);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Link(String kind, String a, String b, String reason, Boolean remove,
            @JsonProperty("operationKey") String operationKey,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static Link of(String kind, String a, String b, String author) {
            return new Link(kind, a, b, Scenario.REASON, null, freshKey(), author);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Correct(Long historyId, String reason, @JsonProperty("operationKey") String operationKey,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static Correct of(long historyId, String author) {
            return new Correct(historyId, Scenario.REASON, freshKey(), author);
        }
    }

    /** One operation inside {@code ledger_batch}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Op(String op, String entity, String id, Long expectedVersion, String to, String reason,
            Fields fields, String evidenceUri, String targetEntity, String targetId, String deliverableRef,
            String summary, String location, String issueId, String observation, String kind, String a, String b,
            Long historyId) {

        public static Op save(String entity, Fields fields) {
            return new Op("save", entity, null, null, null, Scenario.REASON, fields, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }

        public static Op saveWithId(String entity, String id, Fields fields) {
            return new Op("save", entity, id, null, null, Scenario.REASON, fields, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }

        public static Op transition(String entity, String id, long version, String to) {
            return new Op("transition", entity, id, version, to, Scenario.REASON, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }

        public static Op recordDeliverable(String issueId, String deliverableRef) {
            return new Op("record_deliverable", null, null, null, null, Scenario.REASON, null, null, "issue",
                    issueId, deliverableRef, "deliverable " + deliverableRef, "vault/docs", null, null, "document",
                    null, null, null);
        }

        public static Op recordCase(String issueId, String observation) {
            return new Op("record_case", null, null, null, null, Scenario.REASON, null, null, null, null, null, null,
                    null, issueId, observation, null, null, null, null);
        }

        public static Op link(String kind, String a, String b) {
            return new Op("link", null, null, null, null, Scenario.REASON, null, null, null, null, null, null, null,
                    null, null, kind, a, b, null);
        }

        public static Op correct(long historyId) {
            return new Op("correct", null, null, null, null, Scenario.REASON, null, null, null, null, null, null,
                    null, null, null, null, null, null, historyId);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Batch(List<Op> ops, @JsonProperty("operationKey") String operationKey,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static Batch of(String author, Op... ops) {
            return new Batch(List.of(ops), freshKey(), author);
        }

        public Batch withOperationKey(String newKey) {
            return new Batch(ops, newKey, hostAttestation);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TextBatch(String ops, @JsonProperty("operationKey") String operationKey,
            @JsonProperty("_hostAttestation") String hostAttestation) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Review(String entity, String id, Long expectedVersion, String to, String reason,
            String evidenceUri, String evidenceSha256, @JsonProperty("operationKey") String operationKey,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static Review of(String entity, String id, long version, String to, String author) {
            return new Review(entity, id, version, to, Scenario.REASON, Scenario.EVIDENCE, null, freshKey(), author);
        }

        public Review withoutEvidence() {
            return new Review(entity, id, expectedVersion, to, reason, null, evidenceSha256, operationKey,
                    hostAttestation);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Whoami(@JsonProperty("_hostAttestation") String hostAttestation) {

        public static Whoami as(String author) {
            return new Whoami(author);
        }

        public static Whoami withoutAttestation() {
            return new Whoami(null);
        }
    }

    // ---------------------------------------------------------------------------------------------------- reads

    public record Nothing() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ids(List<String> ids) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IdOnly(String id) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProjectOnly(String project) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Codes(String group, Boolean includeInactive, Integer maxRows) {

        public static Codes of(String group) {
            return new Codes(group, null, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record History(String project, Boolean includeBefore, Integer maxRows) {

        public static History of(String project) {
            return new History(project, null, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Search(List<String> keywords) {
    }
}
