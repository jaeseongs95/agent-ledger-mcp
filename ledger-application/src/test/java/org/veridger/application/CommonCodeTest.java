// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.veridger.application.admin.CommonCodeService;
import org.veridger.application.admin.CommonCodeService.CodeCommand;
import org.veridger.application.admin.CommonCodeService.CodeResult;
import org.veridger.application.admin.OwnerAuditWriter;
import org.veridger.application.support.Expect;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.Parallel;
import org.veridger.application.support.Scenario;
import org.veridger.application.write.SaveService;
import org.veridger.apptest.LedgerTestRunners;
import org.veridger.database.repository.CodeRevisionRepository;
import org.veridger.database.repository.CommonCodeRepository;
import org.veridger.database.repository.LinkRepository;
import org.veridger.database.repository.VersionedCode;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.ErrorCode;
import org.veridger.domain.error.LedgerException;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.model.CommonCode;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.rule.CodeState;
import org.veridger.domain.value.CodeValue;
import org.veridger.testing.TestOwners;

/** SVC-CC-01 .. SVC-CC-16: extensible common codes. */
@LedgerServiceTest
class CommonCodeTest {

    private static CodeCommand code(String group, String code) {
        return new CodeCommand(group, code, "이름", "Name", null, null, 25, null);
    }

    private long revision(Scenario s) {
        return s.bean(CodeRevisionRepository.class).current();
    }

    static Stream<String> codeGroups() {
        return Stream.of("ISSUE_CATEGORY", "ISSUE_SEVERITY", "DELIVERABLE_KIND", "RELATION_KIND");
    }

    @ParameterizedTest
    @MethodSource("codeGroups")
    @DisplayName("SVC-CC-01: all four extensible code groups are accepted")
    void cc01_validGroups(String group, Scenario s) {
        CodeCommand inactive = new CodeCommand(group, "CC01_" + s.code(), "이름", "Name", null, null, 25, "INACTIVE");
        assertThat(s.bean(CommonCodeService.class).set(inactive, TestOwners.OWNER).code().group().name())
                .isEqualTo(group);
    }

    @Test
    @DisplayName("SVC-CC-01: any other group is refused")
    void cc01_otherGroup(Scenario s) {
        Expect.failure(() -> s.bean(CommonCodeService.class).set(code("X", "CC01"), TestOwners.OWNER),
                ValidationCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("SVC-CC-02: a new code is active, bumps the revision and is usable at once")
    void cc02_newCode(Scenario s) {
        long before = revision(s);
        String category = "UI_" + s.code();
        var result = s.bean(CommonCodeService.class).set(new CodeCommand("ISSUE_CATEGORY", category, "화면", "UI",
                null, null, 25, null), TestOwners.OWNER);
        assertThat(result.code().state()).isEqualTo(CodeState.ACTIVE);
        assertThat(result.revision()).isEqualTo(before + 1);
        var fields = s.issueFields("i");
        fields.put("category", category);
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("issue", null, fields)).version())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-CC-03: an inactive code stays, is refused for new writes and kept by existing rows")
    void cc03_inactive(Scenario s) {
        String category = "OLD_" + s.code();
        s.bean(CommonCodeService.class).set(new CodeCommand("ISSUE_CATEGORY", category, "옛", "Old", null, null, 30,
                null), TestOwners.OWNER);
        var fields = s.issueFields("i");
        fields.put("category", category);
        var issue = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("issue", null, fields));
        s.bean(CommonCodeService.class).set(new CodeCommand("ISSUE_CATEGORY", category, "옛", "Old", null, null, 30,
                "INACTIVE"), TestOwners.OWNER);
        assertThat(s.bean(CommonCodeRepository.class).find(CodeGroup.ISSUE_CATEGORY, CodeValue.of(category)))
                .isPresent();
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("issue", null, fields)),
                ValidationCode.CODE_INVALID);
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", issue.id(), 1,
                Map.of("title", "kept"))).version()).isEqualTo(2);
        assertThat(s.issue(issue.id()).category().value()).isEqualTo(category);
    }

    @Test
    @DisplayName("SVC-CC-04: the same key updates text and state; the service has no delete and no rename")
    void cc04_neverDeleted(Scenario s) {
        String name = "CC04_" + s.code();
        s.bean(CommonCodeService.class).set(code("RELATION_KIND", name), TestOwners.OWNER);
        var updated = s.bean(CommonCodeService.class).set(new CodeCommand("RELATION_KIND", name, "바뀜", "Changed",
                null, null, 26, null), TestOwners.OWNER);
        assertThat(updated.code().nameEn()).isEqualTo("Changed");
        assertThat(updated.code().createdBy().value()).isEqualTo("user:owner");
        assertThat(Arrays.stream(CommonCodeService.class.getMethods()).map(m -> m.getName()))
                .noneMatch(n -> n.matches("(?i).*(delete|remove|rename).*"));
        assertThat(Arrays.stream(CommonCodeRepository.class.getMethods()).map(m -> m.getName()))
                .noneMatch(n -> n.matches("(?i).*(delete|remove|rename).*"));
    }

    record Limit(String name, CodeCommand command, ErrorCode expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static CodeCommand named(String code, String ko, String en, String descriptionKo) {
        return new CodeCommand("RELATION_KIND", code, ko, en, descriptionKo, null, 40, null);
    }

    static Stream<Limit> limits() {
        ErrorCode refused = ValidationCode.VALIDATION_FAILED;
        return Stream.of(
                new Limit("name_ko empty", named("LIM_A", "", "x", null), refused),
                new Limit("name_ko 1", named("LIM_B", "k", "x", null), null),
                new Limit("name_ko 200", named("LIM_C", "k".repeat(200), "x", null), null),
                new Limit("name_ko 201", named("LIM_D", "k".repeat(201), "x", null), refused),
                new Limit("description 500", named("LIM_E", "k", "x", "d".repeat(500)), null),
                new Limit("description 501", named("LIM_F", "k", "x", "d".repeat(501)), refused),
                new Limit("code with leading digit", named("1LIM", "k", "x", null), refused),
                new Limit("code of 32 chars", named("L".repeat(32), "k", "x", null), null),
                new Limit("code of 33 chars", named("L".repeat(33), "k", "x", null), refused),
                new Limit("code with dash", named("LIM-G", "k", "x", null), refused));
    }

    @ParameterizedTest
    @MethodSource("limits")
    @DisplayName("SVC-CC-05: text and code limits")
    void cc05_limits(Limit limit, Scenario s) {
        assertThat(Expect.outcome(() -> s.bean(CommonCodeService.class).set(limit.command(), TestOwners.OWNER)))
                .isEqualTo(Optional.ofNullable(limit.expected()));
    }

    @Test
    @DisplayName("SVC-CC-06: omitted English text falls back to Korean text")
    void cc06_englishFallback(Scenario s) {
        var result = s.bean(CommonCodeService.class).set(new CodeCommand("RELATION_KIND", "CC06_" + s.code(), "한글",
                null, "설명", null, 41, null), TestOwners.OWNER);
        assertThat(result.code().nameEn()).isEqualTo("한글");
        assertThat(result.code().descriptionEn()).isEqualTo("설명");
    }

    @Test
    @DisplayName("SVC-CC-07/08: unchanged set does not bump revision; rollback leaves revision unchanged")
    void cc07_08_revisionAndRollback(Scenario s) {
        String name = "CC07_" + s.code();
        s.bean(CommonCodeService.class).set(code("RELATION_KIND", name), TestOwners.OWNER);
        long before = revision(s);
        s.bean(CommonCodeService.class).set(code("RELATION_KIND", name), TestOwners.OWNER);
        assertThat(revision(s)).isEqualTo(before);

        String rbName = "CC08_" + s.code();
        assertThatThrownBy(() -> s.ctx().tx(() -> {
            s.bean(CommonCodeService.class).set(code("RELATION_KIND", rbName), TestOwners.OWNER);
            throw new IllegalStateException("rolled back");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(revision(s)).isEqualTo(before);
        assertThat(s.bean(CommonCodeRepository.class).find(CodeGroup.RELATION_KIND, CodeValue.of(rbName))).isEmpty();
    }

    @Test
    @DisplayName("SVC-CC-09: list is ordered by sort order and code and starts with seed")
    void cc09_ordering(Scenario s) {
        List<CommonCode> severities = s.bean(CommonCodeService.class).list(CodeGroup.ISSUE_SEVERITY);
        assertThat(severities.stream().map(c -> c.code().value())).containsExactly("P1", "P2", "P3", "UNRATED");
        assertThat(severities.getLast().state()).isEqualTo(CodeState.INACTIVE);
        assertThat(s.bean(CommonCodeService.class).list(CodeGroup.RELATION_KIND).stream()
                .map(c -> c.code().value())).startsWith("same_site", "caused_by", "blocks", "same_family",
                        "same_work_unit", "regression_of");
    }

    @Test
    @DisplayName("SVC-CC-10: with no category an issue cannot be created and message points at codes")
    void cc10_noCategories() {
        LedgerTestRunners.withoutCategories(fresh -> {
            Scenario s = new Scenario(fresh);
            var fields = s.issueFields("i");
            fields.remove("category");
            LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                    s.create("issue", null, fields)), ValidationCode.CODE_REQUIRED);
            assertThat(e.details()).containsEntry("valid", List.of());
            assertThat(e.args()).containsExactly("issue.category", "", "ISSUE_CATEGORY");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"1LEADING", "WITH SPACE"})
    @DisplayName("SVC-CC-05: code pattern refusal")
    void cc05_codePattern(String code, Scenario s) {
        Expect.failure(() -> s.bean(CommonCodeService.class).set(code("RELATION_KIND", code), TestOwners.OWNER),
                ValidationCode.VALIDATION_FAILED);
    }

    private static long auditRows(Scenario s, String subject) {
        return s.ctx().jdbc().sql("SELECT COUNT(*) FROM owner_audit WHERE subject = :subject")
                .param("subject", subject).query(Long.class).single();
    }

    private static long codeRows(Scenario s, String name) {
        return s.count("SELECT COUNT(*) FROM common_code WHERE code = ?", name);
    }

    @Test
    @DisplayName("SVC-CC-11..16 & SET-023: optimistic locking, seed rollback/idempotency, and concurrent set/addIfAbsent")
    void cc11_to_16_concurrencyAndSeed(Scenario s) {
        CommonCodeService real = s.bean(CommonCodeService.class);
        String name = "CC11_" + s.code();
        String subject = "RELATION_KIND/" + name;
        real.set(code("RELATION_KIND", name), TestOwners.OWNER);
        CodeCommand winner = new CodeCommand("RELATION_KIND", name, "이긴 쪽", "Winner", null, null, 31, null);
        CodeCommand loser = new CodeCommand("RELATION_KIND", name, "진 쪽", "Loser", null, null, 32, null);
        AtomicBoolean raced = new AtomicBoolean();
        CommonCodeRepository racing = new CommonCodeRepository(s.ctx().jdbc()) {
            @Override
            public Optional<VersionedCode> findVersioned(CodeGroup group, CodeValue code) {
                Optional<VersionedCode> seen = super.findVersioned(group, code);
                if (raced.compareAndSet(false, true)) {
                    assertThat(Parallel.all(List.<Callable<CodeResult>>of(() -> real.set(winner, TestOwners.OWNER)))
                            .getFirst().failure()).isNull();
                }
                return seen;
            }
        };
        CommonCodeService service = new CommonCodeService(racing, s.bean(CodeRevisionRepository.class),
                s.bean(OwnerAuditWriter.class), s.bean(LinkRepository.class));
        long audit = auditRows(s, subject);
        LedgerException failure = Expect.failure(() -> s.ctx().tx(() -> service.set(loser, TestOwners.OWNER)),
                ConflictCode.VERSION_CONFLICT);
        assertThat(raced).isTrue();
        assertThat(failure.args()).containsExactly("common_code", subject, "0", "1");
        assertThat(auditRows(s, subject)).isEqualTo(audit + 1);

        // Seed rollback & idempotency
        String first = "CC14A_" + s.code();
        String second = "CC14B_" + s.code();
        long before = revision(s);
        List<CodeCommand> broken = List.of(named(first, "하나", "One", null), named(second, "둘", "Two", null),
                named("CC14C_" + s.code(), "", "Three", null));
        Expect.failure(() -> real.seed(broken, TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);
        assertThat(codeRows(s, first)).isZero();
        assertThat(revision(s)).isEqualTo(before);
        assertThat(real.seed(broken.subList(0, 2), TestOwners.OWNER)).isEqualTo(2);

        // SET-023 addIfAbsent never overwrites
        String seedName = "SEED_" + s.code();
        CodeCommand seed = new CodeCommand("ISSUE_CATEGORY", seedName, "시드", "Seed", "설명", "Description", 10,
                "ACTIVE");
        assertThat(real.addIfAbsent(seed, TestOwners.OWNER)).isPresent();
        real.set(new CodeCommand("ISSUE_CATEGORY", seedName, "내 이름", "My name", "내 설명", "My description", 77,
                "INACTIVE"), TestOwners.OWNER);
        assertThat(real.addIfAbsent(seed, TestOwners.OWNER)).isEmpty();
        CommonCode kept = s.bean(CommonCodeRepository.class).find(CodeGroup.ISSUE_CATEGORY, CodeValue.of(seedName))
                .orElseThrow();
        assertThat(kept.nameKo()).isEqualTo("내 이름");
        assertThat(kept.state()).isEqualTo(CodeState.INACTIVE);

        // R5-NB2: service enforces reactivation confirmation atomically inside CommonCodeService.set
        CodeCommand reactivate = new CodeCommand("ISSUE_CATEGORY", seedName, "내 이름", "My name", "내 설명",
                "My description", 77, "ACTIVE");
        Expect.failure(() -> real.set(reactivate, false, TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);
        assertThat(real.set(reactivate, true, TestOwners.OWNER).code().state()).isEqualTo(CodeState.ACTIVE);
    }
}
