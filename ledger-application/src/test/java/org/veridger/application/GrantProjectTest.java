// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.veridger.testing.TestOwners;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.application.admin.GrantService;
import org.veridger.application.admin.GrantService.GrantCommand;
import org.veridger.application.admin.GrantService.RevokeCommand;
import org.veridger.application.admin.ProjectService;
import org.veridger.application.admin.ProjectService.ProjectCommand;
import org.veridger.application.support.Expect;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.Scenario;
import org.veridger.application.write.WhoamiService;
import org.veridger.application.write.WhoamiService.GrantView;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.ErrorCode;
import org.veridger.domain.error.LedgerException;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.TrustCode;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.rule.Channel;

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
    @DisplayName("direct grant works for any valid <vendor>:<session_id> without session key or session token prerequisites")
    void directGrantWorksWithoutKeyOrToken(Scenario s) {
        String author = "codex:direct-session-1";
        List<Long> ids = s.bean(GrantService.class).grant(
                new GrantCommand(author, s.projectId(), List.of("issue", "task"), null, null), TestOwners.OWNER);
        assertThat(ids).hasSize(2);
        assertThat(rows(s, GRANT_ROWS, author)).isEqualTo(2);
        assertThat(rows(s, AUDIT_ROWS, author)).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-GRT-13: an unknown project and an empty scope list keep their codes")
    void grt13_earlierRefusalsKeepTheirCodes(Scenario s) {
        String author = "claude:grt13";
        GrantService grants = s.bean(GrantService.class);
        Expect.failure(() -> grants.grant(new GrantCommand(author, "nowhere", List.of("issue"), null, null),
                TestOwners.OWNER), LookupCode.NOT_FOUND);
        Expect.failure(() -> grants.grant(new GrantCommand(author, s.projectId(), List.of(), null, null),
                TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);
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
