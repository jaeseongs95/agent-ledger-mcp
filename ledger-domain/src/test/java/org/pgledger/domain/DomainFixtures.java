// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain;

import java.time.Instant;
import org.pgledger.domain.model.Issue;
import org.pgledger.domain.model.Spec;
import org.pgledger.domain.rule.IssueStatus;
import org.pgledger.domain.rule.SpecStatus;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.SpecId;

/** Small builders shared by the domain tests. */
final class DomainFixtures {

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private DomainFixtures() {
    }

    static Issue issue(IssueStatus status, IssueId mergedInto) {
        return new Issue(IssueId.of("E2E-I-0001"), ProjectId.of("e2e"), "title", CodeValue.of("PROCESS"),
                CodeValue.of("P2"), status, mergedInto, "symptom", "cause", AuthorId.of("claude:me"), 0, 1, NOW, NOW,
                AuthorId.of("claude:me"));
    }

    static Spec spec(SpecStatus status) {
        return new Spec(SpecId.of("E2E-S-0001"), ProjectId.of("e2e"), null, "spec title", "summary",
                "acceptance", status, "P1", AuthorId.of("claude:me"), null, null, 1, NOW, NOW,
                AuthorId.of("claude:me"), null);
    }
}
