package org.pgledger.domain.model;

import java.time.Instant;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.TextRule;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** One observation or occurrence of an issue. Insert-only; id assigned from {@code id_counter}. */
@Table("issue_case")
public record IssueCase(@Id Long id, IssueId issueId, String observation, String evidence, String sourceQuote,
        Instant observedAt, ActorId recordedBy, Instant recordedAt) {

    public static IssueCase observe(long id, IssueId issueId, String observation, String evidence,
            String sourceQuote, Instant observedAt, ActorId by, Instant now) {
        return new IssueCase(id, issueId, TextRule.OBSERVATION.required(observation),
                TextRule.EVIDENCE.optional(evidence), TextRule.SOURCE_QUOTE.optional(sourceQuote),
                observedAt, by, now);
    }
}
