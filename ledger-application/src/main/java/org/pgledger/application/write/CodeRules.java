// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.write;

import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.domain.model.Issue;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.value.CodeValue;
import org.springframework.stereotype.Component;

/** Common-code rules for issue and deliverable columns, checked in Java inside the write transaction. */
@Component
public class CodeRules {

    private final CommonCodeRepository codes;

    public CodeRules(CommonCodeRepository codes) {
        this.codes = codes;
    }

    /**
     * Checks category and severity of an issue about to be written. {@code ISSUE_SEVERITY} is always-active, so an
     * issue holding a retired severity requires an active one on edit; {@code ISSUE_CATEGORY} allows keeping an
     * existing retired category on unrelated edits.
     */
    public void checkIssue(Issue current, CodeValue category, CodeValue severity) {
        codes.catalog(CodeGroup.ISSUE_CATEGORY).check("issue.category", category,
                current == null ? null : current.category());
        codes.catalog(CodeGroup.ISSUE_SEVERITY).check("issue.severity", severity,
                current == null ? null : current.severity());
    }

    /** Checks that {@code kind} is an active {@code DELIVERABLE_KIND} code. */
    public void checkDeliverableKind(CodeValue kind) {
        codes.catalog(CodeGroup.DELIVERABLE_KIND).check("deliverable.kind", kind, null);
    }
}
