// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.admin.CommonCodeService;
import org.veridger.application.admin.CommonCodeService.CodeCommand;
import org.veridger.application.admin.GrantService;
import org.veridger.application.admin.GrantService.GrantCommand;
import org.veridger.application.admin.GrantService.RevokeCommand;
import org.veridger.application.admin.ProjectService;
import org.veridger.application.admin.ProjectService.ProjectCommand;
import org.veridger.application.support.Expect;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.Scenario;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.value.OwnerIdentity;

/**
 * SVC-GRT-07, SVC-GRT-08, SVC-CC-11: the owner operations record who did them.
 */
@LedgerServiceTest
class OwnerAuditTest {

    private static final OwnerIdentity OWNER = OwnerIdentity.of("token:owner");
    private static final OwnerIdentity OTHER_OWNER = OwnerIdentity.of("oidc:https://issuer.example#sub-1");

    private static String newAuthor() {
        return "claude:audit" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static List<String> audit(Scenario s, String subject) {
        return s.ctx().jdbc().sql("SELECT operation || ' ' || actor || ' ' || COALESCE(detail, '') FROM owner_audit "
                + "WHERE subject = :subject ORDER BY id").param("subject", subject).query(String.class).list();
    }

    @Test
    @DisplayName("SVC-GRT-07: grant and revoke with an identity record one audit row each, in the transaction of the call")
    void grt07_grantAndRevokeAreAudited(Scenario s) {
        GrantService grants = s.bean(GrantService.class);
        String author = newAuthor();
        s.ctx().enroll(author);
        List<Long> ids = grants.grant(new GrantCommand(author, s.projectId(), List.of("issue", "task"), null, null), OWNER);

        assertThat(audit(s, author + "|" + s.projectId())).singleElement().satisfies(row -> assertThat(row)
                .startsWith("GRANT_ISSUED token:owner scopes=issue,task expires=never grants=" + ids.get(0) + ","
                        + ids.get(1)));
        assertThat(grants.revoke(new RevokeCommand(author, s.projectId(), "task"), OWNER)).isEqualTo(1);
        assertThat(grants.revoke(new RevokeCommand(author, null, null), OWNER)).isEqualTo(1);
        assertThat(audit(s, author + "|" + s.projectId())).hasSize(2).last().isEqualTo(
                "GRANT_REVOKED token:owner scope=task revoked=1");
        assertThat(audit(s, author)).containsExactly("GRANT_REVOKED token:owner scope=all revoked=1");
        assertThat(s.count("SELECT COUNT(*) FROM session_grant WHERE author_id = ? AND granted_by = 'user:owner'", author))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("SVC-GRT-07: the identity of another owner is recorded as given; a refused grant leaves no audit row")
    void grt07_otherOwnerAndRefused(Scenario s) {
        GrantService grants = s.bean(GrantService.class);
        String author = newAuthor();
        s.ctx().enroll(author);
        grants.grant(new GrantCommand(author, s.projectId(), List.of("deliverable"), null, null), OTHER_OWNER);
        Expect.failure(() -> grants.grant(new GrantCommand(author, "nowhere", List.of("deliverable"), null, null), OWNER),
                LookupCode.NOT_FOUND);

        assertThat(audit(s, author + "|" + s.projectId())).singleElement().satisfies(row -> assertThat(row)
                .startsWith("GRANT_ISSUED oidc:https://issuer.example#sub-1 "));
        assertThat(audit(s, author + "|nowhere")).isEmpty();
    }

    @Test
    @DisplayName("SVC-GRT-08: an owner identity is 1 to 300 characters without a control character")
    void grt08_identity() {
        assertThat(OwnerIdentity.of("x".repeat(300)).value()).hasSize(300);
        for (String bad : new String[] {null, "", "   ", "x".repeat(301), "line\nbreak", "bell\u0007"}) {
            assertThatThrownBy(() -> OwnerIdentity.of(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("SVC-CC-11: a code that is added or changed is audited with the identity; an identical set and another owner behave as specified")
    void cc11_codeChangesAreAudited(Scenario s) {
        CommonCodeService codes = s.bean(CommonCodeService.class);
        String name = "AUD_" + s.code();
        String subject = "RELATION_KIND/" + name;
        CodeCommand added = new CodeCommand("RELATION_KIND", name, "감사", "Audit", null, null, 70, "ACTIVE");
        codes.set(added, OWNER);
        codes.set(added, OWNER);
        codes.set(new CodeCommand("RELATION_KIND", name, "감사", "Audit", null, null, 70, "INACTIVE"), OWNER);
        codes.set(new CodeCommand("RELATION_KIND", name, "감사", "Audit", null, null, 70, "ACTIVE"), OTHER_OWNER);

        assertThat(audit(s, subject)).containsExactly("CODE_SET token:owner added state=ACTIVE",
                "CODE_SET token:owner changed state=INACTIVE", "CODE_SET oidc:https://issuer.example#sub-1 changed state=ACTIVE");
    }

    @Test
    @DisplayName("SVC-GRT-09: every owner operation of the services takes the owner identity")
    void grt09_noActorlessOperation() {
        for (Class<?> service : List.of(GrantService.class, CommonCodeService.class)) {
            for (java.lang.reflect.Method method : service.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPublic(method.getModifiers())
                        && java.util.Set.of("grant", "revoke", "syncProjectGrants", "set").contains(method.getName())) {
                    assertThat(method.getParameterTypes()).as(method.toString()).contains(OwnerIdentity.class);
                }
            }
        }
    }

    @Test
    @DisplayName("SVC-GRT-10 (audit2 N3): adding a project records PROJECT_ADDED with the actor, the project id as subject and the code in the detail, in the transaction of the insert; a refused duplicate leaves no second row")
    void grt10_aNewProjectIsAudited(Scenario s) {
        ProjectService projects = s.bean(ProjectService.class);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String id = "audited-" + suffix;
        String code = "AUD" + suffix.toUpperCase(Locale.ROOT);

        projects.add(new ProjectCommand(id, code, "Audited"), OWNER);

        assertThat(audit(s, id)).containsExactly("PROJECT_ADDED token:owner code=" + code);
        Expect.failure(() -> projects.add(new ProjectCommand(id, "OTHER" + code, "Audited"), OWNER), ConflictCode.DUPLICATE_KEY);
        assertThat(audit(s, id)).hasSize(1);
        assertThat(s.count("SELECT COUNT(*) FROM project WHERE id = ?", id)).isEqualTo(1);
    }
}
