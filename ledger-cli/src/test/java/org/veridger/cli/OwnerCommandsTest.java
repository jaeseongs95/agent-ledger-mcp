// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.veridger.cli.support.CliExtension;
import org.veridger.cli.support.CliExtension.Cli;
import org.veridger.cli.support.CliExtension.Outcome;
import org.veridger.cli.support.EnglishMessages;
import org.veridger.cli.support.RecordingOutput;
import org.veridger.cli.support.TestLedgerDatabase;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * CLI-005 .. CLI-009 and CLI-037: the owner commands {@code project-add}, {@code code-set}, {@code codes}, {@code grant},
 * {@code revoke} and {@code grants} on the real wiring: the real application context (the real services, repositories and
 * transaction manager; no mock) over a schema of its own in the PostgreSQL of the test PC that the real migrator built. The
 * tests read the tables the commands wrote ({@code TIMESTAMP} columns come back as {@link java.sql.Timestamp}, whose text form
 * the expiry assertions use).
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class, EnglishMessages.class })
class OwnerCommandsTest {

    private static final String USER = "alice";
    private static final String AUTHOR = "claude:e2e-author";
    private static final String OWNER = "user:owner";

    private static final String PROJECTS = "SELECT id, code, title, next_issue_no, next_spec_no, next_task_no, created_by FROM project ORDER BY id";
    private static final String CODES = "SELECT code_group, code, name_ko, name_en, description_ko, description_en, sort_order, "
            + "state, created_by FROM common_code WHERE code_group = 'ISSUE_CATEGORY' ORDER BY code";
    private static final String GRANTS = "SELECT id, author_id, project_id, grant_scope, granted_by, expires_at, revoked_at, note "
            + "FROM session_grant ORDER BY id";
    private static final String AUDIT = "SELECT actor, operation, subject, detail FROM owner_audit ORDER BY id";

    @TempDir
    Path tmp;

    private TestLedgerDatabase database;

    @BeforeEach
    void migrate() {
        database = TestLedgerDatabase.migrated();
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Outcome run(Cli cli, RecordingOutput output, String... args) {
        return cli.withDatabase(database.dataSource()).asProcessUser(USER).run(tmp.resolve("secrets"), null, output, args);
    }

    private Outcome run(Cli cli, String... args) {
        return run(cli, new RecordingOutput(), args);
    }

    private void addProject(Cli cli) {
        assertThat(run(cli, "project-add", "e2e", "E2E", "End-to-end test project").exitCode()).isZero();
    }

    private static String detailOfFirstRefusal(Outcome result) {
        return result.refusal().orElseThrow().arguments().getFirst();
    }

    // CLI-005 ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CLI-005 project-add <id> <code> <title> adds the project (counters 0, created_by user:owner), prints it and records PROJECT_ADDED with the actor cli:<user>")
    void addsAProject(Cli cli) {
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(cli, output, "project-add", "e2e", "E2E", "End-to-end test project");

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        assertThat(output.info()).contains("e2e").contains("E2E").contains("End-to-end test project");
        List<Map<String, Object>> projects = database.rows(PROJECTS);
        assertThat(projects).hasSize(1);
        Map<String, Object> project = projects.getFirst();
        assertThat(project.get("id")).isEqualTo("e2e");
        assertThat(project.get("code")).isEqualTo("E2E");
        assertThat(project.get("title")).isEqualTo("End-to-end test project");
        assertThat(((Number) project.get("next_issue_no")).longValue()).isZero();
        assertThat(((Number) project.get("next_spec_no")).longValue()).isZero();
        assertThat(((Number) project.get("next_task_no")).longValue()).isZero();
        assertThat(project.get("created_by")).isEqualTo(OWNER);
        List<Map<String, Object>> audit = database.rows(AUDIT);
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("actor")).isEqualTo("cli:" + USER);
        assertThat(audit.getFirst().get("operation")).isEqualTo("PROJECT_ADDED");
        assertThat(audit.getFirst().get("subject")).isEqualTo("e2e");
        assertThat(audit.getFirst().get("detail")).isEqualTo("code=E2E");
    }

    @Test
    @DisplayName("CLI-005 an invalid id, an invalid code or a title of 201 characters is LEDGER_REFUSED naming VALIDATION_FAILED (exit 1); nothing is stored, no audit row")
    void refusesInvalidProjects(Cli cli, CapturedOutput captured) {
        for (String[] line : List.of(new String[] { "bad id", "E2E", "title" }, new String[] { "e2e", "e2e-lower", "title" },
                new String[] { "e2e", "E2E", "x".repeat(201) })) {
            Outcome result = run(cli, "project-add", line[0], line[1], line[2]);

            assertThat(result.exitCode()).as(String.join(" ", line)).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(result.code()).contains(ErrorCode.LEDGER_REFUSED);
            assertThat(detailOfFirstRefusal(result)).startsWith("VALIDATION_FAILED");
        }
        assertThat(captured.getAll()).contains("VALIDATION_FAILED");
        assertThat(database.rows(PROJECTS)).isEmpty();
        assertThat(database.rows(AUDIT)).isEmpty();
    }

    @Test
    @DisplayName("CLI-005 a duplicate id or a duplicate code is LEDGER_REFUSED naming DUPLICATE_KEY (exit 1) and leaves one project and one audit row")
    void refusesDuplicateProjects(Cli cli) {
        addProject(cli);

        Outcome sameId = run(cli, "project-add", "e2e", "OTHER", "Another");
        Outcome sameCode = run(cli, "project-add", "other", "E2E", "Another");

        for (Outcome result : List.of(sameId, sameCode)) {
            assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(result.code()).contains(ErrorCode.LEDGER_REFUSED);
            assertThat(detailOfFirstRefusal(result)).startsWith("DUPLICATE_KEY");
        }
        assertThat(database.rows(PROJECTS)).hasSize(1);
        assertThat(database.rows(AUDIT)).hasSize(1);
    }

    // CLI-006 / CLI-007 ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("CLI-006 code-set creates a code with English texts, sort order and --inactive (state INACTIVE), created_by user:owner, and records CODE_SET as cli:<user>; setting it again to the same thing changes nothing")
    void createsACode(Cli cli) {
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(cli, output, "code-set", "ISSUE_CATEGORY", "BUG", "ko-name", "ko-desc", "--name-en=English name",
                "--description-en=English description", "--sort=6", "--inactive");

        assertThat(result.exitCode()).isZero();
        assertThat(output.info()).contains("ISSUE_CATEGORY/BUG").contains("INACTIVE");
        List<Map<String, Object>> codes = database.rows(CODES);
        assertThat(codes).hasSize(1);
        Map<String, Object> code = codes.getFirst();
        assertThat(code.get("name_ko")).isEqualTo("ko-name");
        assertThat(code.get("name_en")).isEqualTo("English name");
        assertThat(code.get("description_ko")).isEqualTo("ko-desc");
        assertThat(code.get("description_en")).isEqualTo("English description");
        assertThat(((Number) code.get("sort_order")).intValue()).isEqualTo(6);
        assertThat(code.get("state")).isEqualTo("INACTIVE");
        assertThat(code.get("created_by")).isEqualTo(OWNER);
        List<Map<String, Object>> audit = database.rows(AUDIT);
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("actor")).isEqualTo("cli:" + USER);
        assertThat(audit.getFirst().get("operation")).isEqualTo("CODE_SET");
        assertThat(audit.getFirst().get("subject")).isEqualTo("ISSUE_CATEGORY/BUG");

        Outcome same = run(cli, "code-set", "ISSUE_CATEGORY", "BUG", "ko-name", "ko-desc", "--name-en=English name",
                "--description-en=English description", "--sort=6", "--inactive");

        assertThat(same.exitCode()).isZero();
        assertThat(database.rows(AUDIT)).hasSize(1);
    }

    @Test
    @DisplayName("CLI-006 code-set updates an existing code: without --inactive it is ACTIVE again, without --sort it keeps its position, the English texts fall back to the Korean ones; the audit trail says changed")
    void updatesACode(Cli cli) {
        assertThat(run(cli, "code-set", "ISSUE_CATEGORY", "BUG", "ko-name", "--sort=6", "--inactive").exitCode()).isZero();

        Outcome result = run(cli, "code-set", "ISSUE_CATEGORY", "BUG", "new-ko-name", "new-desc");

        assertThat(result.exitCode()).isZero();
        Map<String, Object> code = database.rows(CODES).getFirst();
        assertThat(code.get("name_ko")).isEqualTo("new-ko-name");
        assertThat(code.get("name_en")).isEqualTo("new-ko-name");
        assertThat(code.get("description_en")).isEqualTo("new-desc");
        assertThat(code.get("state")).isEqualTo("ACTIVE");
        assertThat(((Number) code.get("sort_order")).intValue()).isEqualTo(6);
        assertThat(database.rows(AUDIT).getLast().get("detail").toString()).startsWith("changed");
    }

    @Test
    @DisplayName("CLI-006 special characters (quotes, a line break, a backslash, text that looks like a placeholder or like @CODES:..@) are stored exactly as given")
    void keepsSpecialCharacters(Cli cli) {
        String name = "say \"hi\" it's a back\\slash ${x} @CODES:ISSUE_CATEGORY@";
        String description = "line 1\nline 2 with 'single' and \"double\" quotes and @CODES:RELATION_KIND@";

        Outcome result = run(cli, "code-set", "ISSUE_CATEGORY", "QUOTE", name, description, "--name-en=" + name,
                "--description-en=" + description);

        assertThat(result.exitCode()).isZero();
        Map<String, Object> code = database.rows(CODES).getFirst();
        assertThat(code.get("name_ko")).isEqualTo(name);
        assertThat(code.get("name_en")).isEqualTo(name);
        assertThat(code.get("description_ko")).isEqualTo(description);
        assertThat(code.get("description_en")).isEqualTo(description);
    }

    @Test
    @DisplayName("CLI-006 an unknown group, an invalid code and a name of 201 characters are LEDGER_REFUSED naming VALIDATION_FAILED (exit 1); nothing is stored")
    void refusesInvalidCodes(Cli cli) {
        for (String[] line : List.of(new String[] { "NO_SUCH_GROUP", "BUG", "n" }, new String[] { "ISSUE_CATEGORY", "1bad", "n" },
                new String[] { "ISSUE_CATEGORY", "BUG", "x".repeat(201) })) {
            Outcome result = run(cli, "code-set", line[0], line[1], line[2]);

            assertThat(result.exitCode()).as(String.join(" ", line)).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(detailOfFirstRefusal(result)).startsWith("VALIDATION_FAILED");
        }
        assertThat(database.rows(CODES)).isEmpty();
        assertThat(database.rows(AUDIT)).isEmpty();
    }

    @Test
    @DisplayName("CLI-007 codes [group] lists code, state, sort order and the names, for one group or for every group; an unknown group is refused")
    void listsCodes(Cli cli) {
        assertThat(run(cli, "code-set", "ISSUE_CATEGORY", "BUG", "ko-name", "--name-en=English name", "--sort=6", "--inactive")
                .exitCode()).isZero();
        RecordingOutput one = new RecordingOutput();
        RecordingOutput every = new RecordingOutput();

        Outcome group = run(cli, one, "codes", "ISSUE_CATEGORY");
        Outcome all = run(cli, every, "codes");

        assertThat(group.exitCode()).isZero();
        assertThat(one.info()).isEqualTo("ISSUE_CATEGORY BUG INACTIVE 6 ko-name / English name");
        assertThat(all.exitCode()).isZero();
        long stored = ((Number) database.rows("SELECT COUNT(*) AS n FROM common_code").getFirst().get("n")).longValue();
        assertThat(every.info().lines().count()).isEqualTo(stored);
        assertThat(every.info()).contains("ISSUE_CATEGORY BUG INACTIVE 6 ko-name / English name");
        Outcome unknown = run(cli, "codes", "NO_SUCH_GROUP");
        assertThat(unknown.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(detailOfFirstRefusal(unknown)).startsWith("VALIDATION_FAILED");
    }

    private static final String DIRECTION = "SELECT direction FROM common_code WHERE code_group = 'RELATION_KIND' AND code = 'dir_cli'";

    @Test
    @DisplayName("CLI-DIR-01 code-set --direction sets the direction of a relation kind (GIVEN or CANONICAL), an absent option keeps it, codes lists it for a relation kind; a value that is no direction and a direction on another group are refused (VALIDATION_FAILED, nothing stored)")
    void setsTheDirectionOfARelationKind(Cli cli) {
        assertThat(run(cli, "code-set", "RELATION_KIND", "dir_cli", "ko-name", "--direction=GIVEN").exitCode()).isZero();
        assertThat(database.rows(DIRECTION).getFirst().get("direction")).isEqualTo("GIVEN");
        // without the option the direction stays
        assertThat(run(cli, "code-set", "RELATION_KIND", "dir_cli", "new-ko-name", "--sort=77").exitCode()).isZero();
        assertThat(database.rows(DIRECTION).getFirst().get("direction")).isEqualTo("GIVEN");
        RecordingOutput listed = new RecordingOutput();
        assertThat(run(cli, listed, "codes", "RELATION_KIND").exitCode()).isZero();
        assertThat(listed.info()).contains("RELATION_KIND dir_cli ACTIVE 77 new-ko-name / new-ko-name direction GIVEN")
                .contains("RELATION_KIND same_site ACTIVE 1 동일 위치 / Same location direction CANONICAL")
                .contains("RELATION_KIND caused_by ACTIVE 2 원인 관계 / Caused by direction GIVEN");
        assertThat(run(cli, "code-set", "RELATION_KIND", "dir_cli", "new-ko-name", "--direction=CANONICAL").exitCode())
                .isZero();
        assertThat(database.rows(DIRECTION).getFirst().get("direction")).isEqualTo("CANONICAL");

        Outcome sideways = run(cli, "code-set", "RELATION_KIND", "dir_cli", "n", "--direction=SIDEWAYS");
        assertThat(sideways.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(detailOfFirstRefusal(sideways)).startsWith("VALIDATION_FAILED");
        Outcome category = run(cli, "code-set", "ISSUE_CATEGORY", "BUG", "n", "--direction=GIVEN");
        assertThat(category.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(detailOfFirstRefusal(category)).startsWith("VALIDATION_FAILED");
        assertThat(database.rows(CODES)).isEmpty();
    }

    // CLI-008 / CLI-016 -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("CLI-008 grant <author> <project> <scope>... --note --expires issues one live grant per scope (granted_by user:owner); an expiry with an offset is stored as UTC; the owner's audit trail records GRANT_ISSUED as cli:<user>")
    void issuesGrants(Cli cli) {
        addProject(cli);
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(cli, output, "grant", AUTHOR, "e2e", "issue", "spec", "task", "deliverable", "verify", "--note=x",
                "--expires=2099-01-01T10:00:00+09:00");

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        assertThat(output.info()).contains(AUTHOR).contains("e2e").contains("issue,spec,task,deliverable,verify");
        List<Map<String, Object>> grants = database.rows(GRANTS);
        assertThat(grants).hasSize(5);
        assertThat(grants).extracting(row -> row.get("grant_scope"))
                .containsExactly("ISSUE", "SPEC", "TASK", "DELIVERABLE", "VERIFY");
        for (Map<String, Object> grant : grants) {
            assertThat(grant.get("author_id")).isEqualTo(AUTHOR);
            assertThat(grant.get("project_id")).isEqualTo("e2e");
            assertThat(grant.get("granted_by")).isEqualTo(OWNER);
            assertThat(grant.get("revoked_at")).isNull();
            assertThat(grant.get("note")).isEqualTo("x");
            assertThat(String.valueOf(grant.get("expires_at"))).startsWith("2099-01-01 01:00:00");
        }
        Map<String, Object> audit = database.rows(AUDIT).getLast();
        assertThat(audit.get("actor")).isEqualTo("cli:" + USER);
        assertThat(audit.get("operation")).isEqualTo("GRANT_ISSUED");
        assertThat(audit.get("subject")).isEqualTo(AUTHOR + "|e2e");
        assertThat(audit.get("detail").toString()).contains("scopes=issue,spec,task,deliverable,verify");
    }

    @Test
    @DisplayName("CLI-008/CLI-016 an expiry without a zone, or a date only, is refused (VALIDATION_FAILED, exit 1) and nothing is granted; the Z form is accepted")
    void refusesAnExpiryWithoutAZone(Cli cli) {
        addProject(cli);

        for (String expires : List.of("2099-01-01T10:00:00", "2099-01-01")) {
            Outcome result = run(cli, "grant", AUTHOR, "e2e", "issue", "--expires=" + expires);

            assertThat(result.exitCode()).as(expires).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(detailOfFirstRefusal(result)).startsWith("VALIDATION_FAILED");
        }
        assertThat(database.rows(GRANTS)).isEmpty();

        Outcome zulu = run(cli, "grant", AUTHOR, "e2e", "issue", "--expires=2099-01-01T10:00:00Z");

        assertThat(zulu.exitCode()).isZero();
        assertThat(String.valueOf(database.rows(GRANTS).getFirst().get("expires_at"))).startsWith("2099-01-01 10:00:00");
    }

    @Test
    @DisplayName("CLI-008 granting again closes the earlier live grant of the same key (the row stays) and leaves exactly one live grant; an unknown project is NOT_FOUND and an unknown scope VALIDATION_FAILED")
    void grantsAgainAndRefuses(Cli cli) {
        addProject(cli);
        assertThat(run(cli, "grant", AUTHOR, "e2e", "issue").exitCode()).isZero();

        Outcome again = run(cli, "grant", AUTHOR, "e2e", "issue", "--note=second");
        Outcome noProject = run(cli, "grant", AUTHOR, "nope", "issue");
        Outcome noScope = run(cli, "grant", AUTHOR, "e2e", "bogus");

        assertThat(again.exitCode()).isZero();
        List<Map<String, Object>> grants = database.rows(GRANTS);
        assertThat(grants).hasSize(2);
        assertThat(grants.get(0).get("revoked_at")).isNotNull();
        assertThat(grants.get(1).get("revoked_at")).isNull();
        assertThat(grants.get(1).get("note")).isEqualTo("second");
        assertThat(noProject.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(detailOfFirstRefusal(noProject)).startsWith("NOT_FOUND");
        assertThat(noScope.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(detailOfFirstRefusal(noScope)).startsWith("VALIDATION_FAILED");
        assertThat(database.rows(GRANTS)).hasSize(2);
    }

    @Test
    @DisplayName("CLI-008 grants [author] lists the live grants (author, project, scope, expiry in UTC with Z or never, note); another author has none")
    void listsGrants(Cli cli) {
        addProject(cli);
        assertThat(run(cli, "grant", AUTHOR, "e2e", "issue", "task", "--note=first", "--expires=2099-01-01T10:00:00+09:00")
                .exitCode()).isZero();
        assertThat(run(cli, "grant", AUTHOR, "e2e", "deliverable").exitCode()).isZero();
        RecordingOutput all = new RecordingOutput();
        RecordingOutput one = new RecordingOutput();
        RecordingOutput none = new RecordingOutput();

        Outcome everyone = run(cli, all, "grants");
        Outcome author = run(cli, one, "grants", AUTHOR);
        Outcome other = run(cli, none, "grants", "claude:other-author");

        assertThat(everyone.exitCode()).isZero();
        assertThat(all.info().lines().toList()).containsExactlyInAnyOrder(
                AUTHOR + " e2e issue expires 2099-01-01T01:00:00Z first",
                AUTHOR + " e2e task expires 2099-01-01T01:00:00Z first",
                AUTHOR + " e2e deliverable expires never ");
        assertThat(author.exitCode()).isZero();
        assertThat(one.info()).isEqualTo(all.info());
        assertThat(other.exitCode()).isZero();
        assertThat(none.info()).isEqualTo("There are no live grants.");
    }

    @Test
    @DisplayName("CLI-008 revoke <author> [project [scope]] revokes exactly the matching live grants and prints how many (rows stay, revoked_at is set); GRANT_REVOKED is recorded as cli:<user> each time")
    void revokesGrants(Cli cli) {
        addProject(cli);
        assertThat(run(cli, "grant", AUTHOR, "e2e", "issue", "spec", "task", "deliverable", "verify").exitCode()).isZero();
        RecordingOutput scope = new RecordingOutput();
        RecordingOutput project = new RecordingOutput();
        RecordingOutput all = new RecordingOutput();

        Outcome oneScope = run(cli, scope, "revoke", AUTHOR, "e2e", "task");
        Outcome oneProject = run(cli, project, "revoke", AUTHOR, "e2e");
        Outcome nothingLeft = run(cli, all, "revoke", AUTHOR);

        assertThat(oneScope.exitCode()).isZero();
        assertThat(scope.info()).isEqualTo("Revoked 1 live grant(s) of " + AUTHOR + ".");
        assertThat(project.info()).isEqualTo("Revoked 4 live grant(s) of " + AUTHOR + ".");
        assertThat(nothingLeft.exitCode()).isZero();
        assertThat(all.info()).isEqualTo("Revoked 0 live grant(s) of " + AUTHOR + ".");
        List<Map<String, Object>> grants = database.rows(GRANTS);
        assertThat(grants).hasSize(5);
        assertThat(grants).allSatisfy(grant -> assertThat(grant.get("revoked_at")).isNotNull());
        List<Map<String, Object>> revocations = database.rows(AUDIT).stream()
                .filter(row -> "GRANT_REVOKED".equals(row.get("operation"))).toList();
        assertThat(revocations).hasSize(3);
        assertThat(revocations).allSatisfy(row -> assertThat(row.get("actor")).isEqualTo("cli:" + USER));
    }

    // CLI-009 ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CLI-009 changed by cli2 (the CLI also writes owner_audit): every row the commands create is stamped user:owner (project.created_by, common_code.created_by, session_grant.granted_by) and every owner operation has an owner_audit row whose actor is cli:<process user>")
    void rowsAreOwnedByTheOwnerAndAuditedAsTheCli(Cli cli) {
        addProject(cli);
        assertThat(run(cli, "code-set", "ISSUE_CATEGORY", "BUG", "ko-name").exitCode()).isZero();
        assertThat(run(cli, "grant", AUTHOR, "e2e", "issue").exitCode()).isZero();
        assertThat(run(cli, "revoke", AUTHOR, "e2e", "issue").exitCode()).isZero();

        assertThat(database.rows(PROJECTS)).allSatisfy(row -> assertThat(row.get("created_by")).isEqualTo(OWNER));
        assertThat(database.rows(CODES)).allSatisfy(row -> assertThat(row.get("created_by")).isEqualTo(OWNER));
        assertThat(database.rows(GRANTS)).allSatisfy(row -> assertThat(row.get("granted_by")).isEqualTo(OWNER));
        List<Map<String, Object>> audit = database.rows(AUDIT);
        assertThat(audit).extracting(row -> row.get("operation")).containsExactly("PROJECT_ADDED",
                "CODE_SET", "GRANT_ISSUED", "GRANT_REVOKED");
        assertThat(audit).allSatisfy(row -> assertThat(row.get("actor")).isEqualTo("cli:" + USER));
    }

    // PSK-CLI -----------------------------------------------------------------------------------------------------

    private static final String PASSKEY_CODES = "SELECT code_hash, issued_by, used_at FROM owner_passkey_code ORDER BY issued_at, code_hash";

    @Test
    @DisplayName("PSK-CLI-01 passkey-code prints one code in five groups of four (only now), stores only its hash and records PASSKEY_CODE_ISSUED with the actor cli:<user>; a second request ends the first code")
    void issuesAPasskeyCode(Cli cli) {
        RecordingOutput first = new RecordingOutput();
        RecordingOutput second = new RecordingOutput();

        assertThat(run(cli, first, "passkey-code").exitCode()).isZero();
        assertThat(run(cli, second, "passkey-code").exitCode()).isZero();

        assertThat(first.info()).containsPattern("[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){4}");
        assertThat(second.info()).doesNotContain("closed");
        List<Map<String, Object>> codes = database.rows(PASSKEY_CODES);
        assertThat(codes).hasSize(2).allSatisfy(row -> assertThat(row.get("issued_by")).isEqualTo("cli:" + USER));
        assertThat(codes).allSatisfy(row -> assertThat(row.get("code_hash").toString()).hasSize(64));
        assertThat(database.rows(AUDIT)).extracting(row -> row.get("operation"))
                .containsExactly("PASSKEY_CODE_ISSUED", "PASSKEY_CODE_ISSUED");
        assertThat(database.rows(AUDIT)).allSatisfy(row -> assertThat(row.get("actor")).isEqualTo("cli:" + USER));
    }

    @Test
    @DisplayName("PSK-CLI-02 --remove-existing closes the live passkeys (the count is printed, PASSKEY_RESET is recorded, the credential rows stay); without the flag nothing is closed; an operand or an unknown option is refused")
    void resettingNeedsTheFlag(Cli cli) {
        database.update("INSERT INTO owner_passkey (credential_id, user_id, public_key, signature_count, uv_initialized, "
                + "backup_eligible, backup_state, attestation_object, label, fingerprint, registered_by, registered_at) VALUES "
                + "('c1', 'u', 'AAAA', 0, 1, 0, 0, 'BBBB', 'one', '0000-0000-0000-0001', 'x', TIMESTAMP '2026-11-15 10:00:00')");
        RecordingOutput kept = new RecordingOutput();
        RecordingOutput removed = new RecordingOutput();

        assertThat(run(cli, kept, "passkey-code").exitCode()).isZero();
        assertThat(database.rows("SELECT credential_id FROM owner_passkey_revocation")).isEmpty();
        assertThat(run(cli, removed, "passkey-code", "--remove-existing").exitCode()).isZero();

        assertThat(removed.info()).contains("Closed 1 live passkey");
        assertThat(database.rows("SELECT credential_id, revoked_by FROM owner_passkey_revocation")).singleElement()
                .satisfies(row -> assertThat(row.get("revoked_by")).isEqualTo("cli:" + USER));
        assertThat(database.rows("SELECT credential_id FROM owner_passkey")).hasSize(1);
        assertThat(database.rows(AUDIT)).extracting(row -> row.get("operation")).contains("PASSKEY_RESET");
        assertThat(run(cli, "passkey-code", "extra").exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(run(cli, "passkey-code", "--unknown").exitCode()).isEqualTo(ExitStatus.USAGE.code());
    }
}
