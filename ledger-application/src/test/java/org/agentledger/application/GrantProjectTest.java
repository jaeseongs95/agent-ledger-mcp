// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.agentledger.testing.TestOwners;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.agentledger.application.admin.GrantService;
import org.agentledger.application.admin.GrantService.GrantCommand;
import org.agentledger.application.admin.GrantService.RevokeCommand;
import org.agentledger.application.admin.ProjectService;
import org.agentledger.application.admin.ProjectService.ProjectCommand;
import org.agentledger.application.admin.SessionKeyService;
import org.agentledger.application.admin.SessionKeyService.RegisterCommand;
import org.agentledger.application.support.Expect;
import org.agentledger.application.support.LedgerServiceTest;
import org.agentledger.application.support.Scenario;
import org.agentledger.application.write.WhoamiService;
import org.agentledger.application.write.WhoamiService.GrantView;
import org.agentledger.apptest.TestKeyRing;
import org.agentledger.attestation.PublicKeys;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.ErrorCode;
import org.agentledger.domain.error.LedgerException;
import org.agentledger.domain.error.LookupCode;
import org.agentledger.domain.error.TrustCode;
import org.agentledger.domain.error.ValidationCode;
import org.agentledger.domain.error.ValidationReason;
import org.agentledger.domain.rule.Channel;

/** SVC-GRT-01 .. SVC-GRT-05, 11 .. 15 (SVC-GRT-06 needs both databases and is in the race test). */
@LedgerServiceTest
class GrantProjectTest {

    @Test
    @DisplayName("SVC-GRT-01: one row per scope, an earlier live grant of the same key is closed, nothing is deleted")
    void grt01_grant(Scenario s) {
        String author = "claude:grt01";
        s.ctx().enroll(author);
        s.bean(GrantService.class).grant(new GrantCommand(author, s.projectId(), List.of("issue"), null, "first"),
                TestOwners.OWNER);
        List<Long> ids = s.bean(GrantService.class).grant(new GrantCommand(author, s.projectId(),
                List.of("issue", "task"), "2030-01-01T00:00:00Z", "second"), TestOwners.OWNER);
        assertThat(ids).hasSize(2);
        assertThat(s.count("SELECT COUNT(*) FROM session_grant WHERE author_id = ? AND grant_scope = 'ISSUE'", author))
                .isEqualTo(2);
        assertThat(s.count("SELECT COUNT(*) FROM session_grant WHERE author_id = ? AND grant_scope = 'ISSUE' "
                + "AND revoked_at IS NULL", author)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM session_grant WHERE author_id = ? AND granted_by = 'user:owner'",
                author)).isEqualTo(3);
    }

    private static long rows(Scenario s, String sql, String author) {
        return s.count(sql, author);
    }

    private static final String GRANT_ROWS = "SELECT COUNT(*) FROM session_grant WHERE author_id = ?";
    private static final String AUDIT_ROWS = "SELECT COUNT(*) FROM owner_audit WHERE operation = 'GRANT_ISSUED' "
            + "AND subject LIKE ? || '|%'";

    @Test
    @DisplayName("SVC-GRT-11: a grant for an author without a live session key is refused SESSION_KEY_REQUIRED; no grant row and no audit row")
    void grt11_noKeyNoGrant(Scenario s) {
        String author = "claude:grt11";
        LedgerException refused = Expect.failure(() -> s.bean(GrantService.class).grant(
                new GrantCommand(author, s.projectId(), List.of("issue", "task"), null, null), TestOwners.OWNER),
                ConflictCode.SESSION_KEY_REQUIRED);
        assertThat(refused.args()).containsExactly(author);
        assertThat(rows(s, GRANT_ROWS, author)).isZero();
        assertThat(rows(s, AUDIT_ROWS, author)).isZero();
    }

    @Test
    @DisplayName("SVC-GRT-12: a closed key is no live key; after a key is registered again the grant is issued")
    void grt12_closedKey(Scenario s) {
        String author = "claude:grt12";
        SessionKeyService keys = s.bean(SessionKeyService.class);
        keys.register(new RegisterCommand(author, PublicKeys.encode(TestKeyRing.newPair().getPublic()), null),
                TestOwners.OWNER);
        keys.revoke(author, TestOwners.OWNER);
        GrantCommand command = new GrantCommand(author, s.projectId(), List.of("issue"), null, null);
        Expect.failure(() -> s.bean(GrantService.class).grant(command, TestOwners.OWNER),
                ConflictCode.SESSION_KEY_REQUIRED);
        assertThat(rows(s, GRANT_ROWS, author)).isZero();

        keys.register(new RegisterCommand(author, PublicKeys.encode(TestKeyRing.newPair().getPublic()), null),
                TestOwners.OWNER);
        assertThat(s.bean(GrantService.class).grant(command, TestOwners.OWNER)).hasSize(1);
        assertThat(rows(s, GRANT_ROWS, author)).isEqualTo(1);
        assertThat(rows(s, AUDIT_ROWS, author)).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-GRT-13: an unknown project and an empty scope list keep their codes for an author without a key")
    void grt13_earlierRefusalsKeepTheirCodes(Scenario s) {
        String author = "claude:grt13";
        GrantService grants = s.bean(GrantService.class);
        Expect.failure(() -> grants.grant(new GrantCommand(author, "nowhere", List.of("issue"), null, null),
                TestOwners.OWNER), LookupCode.NOT_FOUND);
        Expect.failure(() -> grants.grant(new GrantCommand(author, s.projectId(), List.of(), null, null),
                TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);
    }

    private static final String SUBJECT_AUDIT_ROWS = "SELECT COUNT(*) FROM owner_audit WHERE subject = ?";

    @Test
    @DisplayName("SVC-GRT-14 (TXA-02): the key and the grants of the grant screen are one use case and one transaction: a refused grant leaves no key and no audit row, an accepted one commits the key, the grants and both audit rows together, the same key again is a second grant of the one key")
    void grt14_keyAndGrantsAreOneTransaction(Scenario s) {
        String author = "claude:grt14";
        GrantService grants = s.bean(GrantService.class);
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String key = PublicKeys.encode(TestKeyRing.newPair().getPublic());

        Expect.failure(() -> grants.grantWithKey(new GrantCommand(author, "nowhere", List.of("issue"), null, null), key,
                TestOwners.OWNER), LookupCode.NOT_FOUND);
        assertThat(keys.history(author)).as("the key was rolled back with the refused grant").isEmpty();
        assertThat(rows(s, SUBJECT_AUDIT_ROWS, author)).isZero();
        assertThat(rows(s, GRANT_ROWS, author)).isZero();

        GrantCommand command = new GrantCommand(author, s.projectId(), List.of("issue", "task"), null, null);
        assertThat(grants.grantWithKey(command, key, TestOwners.OWNER)).hasSize(2);
        assertThat(keys.live(author)).isPresent();
        assertThat(rows(s, GRANT_ROWS, author)).isEqualTo(2);
        assertThat(rows(s, SUBJECT_AUDIT_ROWS, author)).as("KEY_REGISTERED").isEqualTo(1);
        assertThat(rows(s, AUDIT_ROWS, author)).as("GRANT_ISSUED").isEqualTo(1);

        assertThat(grants.grantWithKey(command, key, TestOwners.OWNER)).hasSize(2);
        assertThat(keys.history(author)).hasSize(1);
        assertThat(rows(s, GRANT_ROWS, author)).isEqualTo(4);
    }

    @Test
    @DisplayName("SVC-GRT-15 (TXA-02): the confirmed replacement of a key and the grants are one transaction: a refused grant leaves the old key live and no closed row, a replacement that is not confirmed issues no grant, a confirmed one replaces the key and grants")
    void grt15_replacementAndGrantsAreOneTransaction(Scenario s) {
        GrantService grants = s.bean(GrantService.class);
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String author = s.actorWith("issue");
        String oldFingerprint = keys.live(author).orElseThrow().fingerprint();
        String replacement = PublicKeys.encode(TestKeyRing.newPair().getPublic());
        long grantRows = rows(s, GRANT_ROWS, author);

        Expect.failure(() -> grants.grantReplacingKey(new GrantCommand(author, "nowhere", List.of("deliverable"),
                null, null), replacement, oldFingerprint, TestOwners.OWNER), LookupCode.NOT_FOUND);
        assertThat(keys.live(author).orElseThrow().fingerprint()).isEqualTo(oldFingerprint);
        assertThat(keys.history(author)).as("the close of the old key was rolled back").hasSize(1);
        assertThat(rows(s, GRANT_ROWS, author)).isEqualTo(grantRows);

        GrantCommand command = new GrantCommand(author, s.projectId(), List.of("deliverable"), null, null);
        Expect.failure(() -> grants.grantReplacingKey(command, replacement, "0".repeat(64), TestOwners.OWNER),
                ConflictCode.KEY_REPLACEMENT_NOT_CONFIRMED);
        assertThat(keys.live(author).orElseThrow().fingerprint()).isEqualTo(oldFingerprint);
        assertThat(rows(s, GRANT_ROWS, author)).isEqualTo(grantRows);

        assertThat(grants.grantReplacingKey(command, replacement, oldFingerprint, TestOwners.OWNER)).hasSize(1);
        assertThat(keys.live(author).orElseThrow().publicKey()).isEqualTo(replacement);
        assertThat(keys.history(author)).hasSize(2);
        assertThat(rows(s, GRANT_ROWS, author)).isEqualTo(grantRows + 1);
        assertThat(s.count("SELECT COUNT(*) FROM owner_audit WHERE subject = ? AND operation = 'KEY_REPLACED'", author))
                .isEqualTo(1);
    }

    /** A grant request that must be refused, and why. */
    record Bad(String name, GrantCommand command, ErrorCode expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Bad> badGrants() {
        List<String> issue = List.of("issue");
        ErrorCode invalid = ValidationCode.VALIDATION_FAILED;
        return Stream.of(
                new Bad("author without a vendor", new GrantCommand("someone", "P", issue, null, null),
                        TrustCode.AUTHOR_ID_FORMAT),
                new Bad("no scope", new GrantCommand("claude:x", "P", List.of(), null, null), invalid),
                new Bad("unknown scope", new GrantCommand("claude:x", "P", List.of("write"), null, null), invalid),
                new Bad("unknown project", new GrantCommand("claude:x", "nowhere", issue, null, null),
                        LookupCode.NOT_FOUND),
                new Bad("note of 501 characters", new GrantCommand("claude:x", "P", issue, null, "n".repeat(501)),
                        invalid),
                new Bad("an expiry in the past", new GrantCommand("claude:x", "P", issue, "2020-01-01T00:00:00Z",
                        null), invalid),
                new Bad("an expiry without a zone", new GrantCommand("claude:x", "P", issue, "2030-01-01T00:00:00",
                        null), invalid));
    }

    @ParameterizedTest
    @MethodSource("badGrants")
    @DisplayName("SVC-GRT-02: invalid grants are refused")
    void grt02_validation(Bad bad, Scenario s) {
        GrantCommand c = bad.command();
        if ("claude:x".equals(c.author())) {
            s.ctx().enroll(c.author());
        }
        GrantCommand onProject = new GrantCommand(c.author(), "P".equals(c.project()) ? s.projectId() : c.project(),
                c.scopes(), c.expiresAt(), c.note());
        assertThat(Expect.outcome(() -> s.bean(GrantService.class).grant(onProject, TestOwners.OWNER)))
                .isEqualTo(Optional.of(bad.expected()));
    }

    @Test
    @DisplayName("SVC-GRT-03: a revoke closes exactly the matching live grants and counts them")
    void grt03_revoke(Scenario s) {
        String author = "claude:grt03";
        s.ctx().enroll(author);
        String other = s.otherProject();
        s.bean(GrantService.class).grant(new GrantCommand(author, s.projectId(), List.of("issue", "task"), null,
                null), TestOwners.OWNER);
        s.bean(GrantService.class).grant(new GrantCommand(author, other, List.of("issue"), null, null), TestOwners.OWNER);
        assertThat(s.bean(GrantService.class).revoke(new RevokeCommand(author, s.projectId(), "issue"), TestOwners.OWNER))
                .isEqualTo(1);
        assertThat(s.bean(GrantService.class).revoke(new RevokeCommand(author, s.projectId(), null), TestOwners.OWNER)).isEqualTo(1);
        assertThat(s.bean(GrantService.class).revoke(new RevokeCommand(author, null, null), TestOwners.OWNER)).isEqualTo(1);
        assertThat(s.bean(GrantService.class).revoke(new RevokeCommand(author, null, null), TestOwners.OWNER)).isZero();
        assertThat(s.count("SELECT COUNT(*) FROM session_grant WHERE author_id = ?", author)).isEqualTo(3);
    }

    @Test
    @DisplayName("SVC-GRT-03: a scope without a project is refused")
    void grt03_scopeNeedsAProject(Scenario s) {
        Expect.failure(() -> s.bean(GrantService.class).revoke(new RevokeCommand("claude:x", null, "issue"), TestOwners.OWNER),
                ValidationCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("SVC-GRT-04: whoami lists the live grants only, by project and scope")
    void grt04_whoami(Scenario s) {
        String author = "claude:grt04";
        s.ctx().enroll(author);
        String other = s.otherProject();
        s.bean(GrantService.class).grant(new GrantCommand(author, other, List.of("task"), null, null), TestOwners.OWNER);
        s.bean(GrantService.class).grant(new GrantCommand(author, s.projectId(), List.of("task", "issue"),
                "2030-01-01T00:00:00Z", null), TestOwners.OWNER);
        s.bean(GrantService.class).grant(new GrantCommand(author, s.projectId(), List.of("deliverable"),
                "2026-11-05T10:30:00Z", null), TestOwners.OWNER);
        s.bean(GrantService.class).revoke(new RevokeCommand(author, other, "task"), TestOwners.OWNER);
        s.ctx().clock().set(Instant.parse("2026-11-05T11:00:00Z"));
        var whoami = s.bean(WhoamiService.class).whoami(author, Channel.AUTHOR);
        assertThat(whoami.grants()).containsExactly(new GrantView(s.projectId(), "issue",
                Instant.parse("2030-01-01T00:00:00Z")), new GrantView(s.projectId(), "task",
                Instant.parse("2030-01-01T00:00:00Z")));
        assertThat(whoami.authorFormatOk()).isTrue();
        assertThat(whoami.canVerifyLogin()).isFalse();
        assertThat(s.bean(WhoamiService.class).whoami(author, Channel.REVIEWER).canVerifyLogin()).isTrue();
    }

    @Test
    @DisplayName("SVC-GRT-04: a malformed author gets format-not-ok and no grants")
    void grt04_malformed(Scenario s) {
        var whoami = s.bean(WhoamiService.class).whoami("claude", Channel.AUTHOR);
        assertThat(whoami.authorFormatOk()).isFalse();
        assertThat(whoami.grants()).isEmpty();
    }

    /** A project to add, and the verdict. */
    record NewProject(String name, ProjectCommand command, ErrorCode expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<NewProject> newProjects() {
        ErrorCode invalid = ValidationCode.VALIDATION_FAILED;
        return Stream.of(
                new NewProject("valid", new ProjectCommand("prj-ok", "PRJOK", "A project"), null),
                new NewProject("id with a space", new ProjectCommand("a b", "PRJA", "t"), invalid),
                new NewProject("id of 65 characters", new ProjectCommand("p".repeat(65), "PRJB", "t"), invalid),
                new NewProject("lower-case code", new ProjectCommand("prj-c", "prjc", "t"), invalid),
                new NewProject("code with a leading digit", new ProjectCommand("prj-d", "1PRJ", "t"), invalid),
                new NewProject("empty title", new ProjectCommand("prj-e", "PRJE", " "), invalid),
                new NewProject("title of 201 characters", new ProjectCommand("prj-f", "PRJF", "t".repeat(201)),
                        invalid));
    }

    @ParameterizedTest
    @MethodSource("newProjects")
    @DisplayName("SVC-GRT-05: a project needs a valid id, code and title")
    void grt05_validation(NewProject project, Scenario s) {
        assertThat(Expect.outcome(() -> s.bean(ProjectService.class).add(project.command(), TestOwners.OWNER)))
                .isEqualTo(Optional.ofNullable(project.expected()));
        if (project.expected() == null) {
            LedgerException noId = Expect.failure(() -> s.bean(ProjectService.class).add(
                    new ProjectCommand(null, "PRJN", "t"), TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);
            assertThat(noId.args()).containsExactly("id");
            assertThat(Expect.firstIssue(noId).reason()).isEqualTo(ValidationReason.REQUIRED);
            LedgerException noCode = Expect.failure(() -> s.bean(ProjectService.class).add(
                    new ProjectCommand("prj-n", null, "t"), TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);
            assertThat(noCode.args()).containsExactly("code");
            assertThat(Expect.firstIssue(noCode).reason()).isEqualTo(ValidationReason.REQUIRED);
        }
    }

    @Test
    @DisplayName("SVC-GRT-05: a new project starts at zero and is stamped user:owner")
    void grt05_counters(Scenario s) {
        assertThat(s.count("SELECT next_issue_no FROM project WHERE id = ?", s.projectId())).isZero();
        assertThat(s.count("SELECT next_spec_no FROM project WHERE id = ?", s.projectId())).isZero();
        assertThat(s.count("SELECT next_task_no FROM project WHERE id = ?", s.projectId())).isZero();
        assertThat(s.count("SELECT COUNT(*) FROM project WHERE id = ? AND created_by = 'user:owner'",
                s.projectId())).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-GRT-05: a duplicate id or code")
    void grt05_duplicates(Scenario s) {
        Expect.failure(() -> s.bean(ProjectService.class).add(new ProjectCommand(s.projectId(), "OTHERCODE", "t"), TestOwners.OWNER),
                ConflictCode.DUPLICATE_KEY);
        Expect.failure(() -> s.bean(ProjectService.class).add(new ProjectCommand("another-id", s.code(), "t"), TestOwners.OWNER),
                ConflictCode.DUPLICATE_KEY);
    }
}
