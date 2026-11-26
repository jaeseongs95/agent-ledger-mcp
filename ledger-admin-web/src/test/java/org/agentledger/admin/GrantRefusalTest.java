// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.admin.support.AdminTest;
import org.agentledger.admin.web.AdminProblems;
import org.agentledger.application.admin.GrantService;
import org.agentledger.application.admin.GrantService.GrantCommand;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.ConflictException;
import org.agentledger.domain.error.LedgerException;
import org.agentledger.testing.TestOwners;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** ADM-PRN-12: the refusal of the service for a grant without a live session key is a 409 problem like the other conflicts. */
@AdminTest
class GrantRefusalTest {

    @Autowired
    private AdminProblems problems;

    @Autowired
    private GrantService grants;

    @Autowired
    private org.agentledger.admin.support.Seeds seeds;

    @Test
    @DisplayName("ADM-PRN-12: SESSION_KEY_REQUIRED from the grant service is 409 with its code and the author in the sentence, in English and in Korean")
    void adm_prn12_refusalIsAConflictProblem() {
        org.agentledger.admin.support.Seeds.Seeded project = seeds.project();
        String author = "gemini:no-key-admin";

        LedgerException refused = null;
        try {
            grants.grant(new GrantCommand(author, project.project(), List.of("issue"), null, null), TestOwners.OWNER);
        } catch (ConflictException failure) {
            refused = failure;
        }

        assertThat(refused).isNotNull();
        assertThat(refused.code()).isEqualTo(ConflictCode.SESSION_KEY_REQUIRED);
        assertThat(grants.live(author)).isEmpty();
        ProblemDetail english = problems.problem(refused, Locale.ENGLISH);
        assertThat(english.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(english.getProperties()).containsEntry("code", "SESSION_KEY_REQUIRED");
        assertThat(english.getDetail()).contains(author).contains("no live session key");
        ProblemDetail korean = problems.problem(refused, Locale.KOREAN);
        assertThat(korean.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(korean.getDetail()).contains(author).contains("세션 키");
    }
}
