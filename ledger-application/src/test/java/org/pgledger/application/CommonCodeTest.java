package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.pgledger.testing.TestOwners;

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
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.application.admin.CommonCodeService.CodeResult;
import org.pgledger.application.admin.CommonCodeWriter;
import org.pgledger.application.admin.OwnerAuditWriter;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Scenario;
import org.pgledger.apptest.LedgerTestRunners;
import org.pgledger.application.write.SaveService;
import org.pgledger.database.repository.CodeRevisionRepository;
import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.database.repository.LinkRepository;
import org.pgledger.database.repository.VersionedCode;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.value.CodeValue;

/** SVC-CC-01 .. SVC-CC-10: the common codes. */
@LedgerServiceTest
class CommonCodeTest {

    private static CodeCommand code(String group, String code) {
        return new CodeCommand(group, code, "이름", "Name", null, null, 25, null);
    }

    private long revision(Scenario s) {
        return s.bean(CodeRevisionRepository.class).current();
    }

    static Stream<String> codeGroups() {
        return Stream.of("DEFECT_CATEGORY", "DEFECT_SEVERITY", "RELATION_KIND");
    }

    @ParameterizedTest
    @MethodSource("codeGroups")
    @DisplayName("SVC-CC-01: the three code groups are accepted")
    void cc01_validGroups(String group, Scenario s) {
        CodeCommand inactive = new CodeCommand(group, "CC01_" + s.code(), "이름", "Name", null, null, 25, "INACTIVE");
        assertThat(s.bean(CommonCodeService.class).set(inactive, TestOwners.OWNER).code().group().name()).isEqualTo(group);
    }

    @Test
    @DisplayName("SVC-CC-01: any other group is refused")
    void cc01_otherGroup(Scenario s) {
        Expect.failure(() -> s.bean(CommonCodeService.class).set(code("X", "CC01"), TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("SVC-CC-02: a new code is active, bumps the revision and is usable at once")
    void cc02_newCode(Scenario s) {
        long before = revision(s);
        String category = "UI_" + s.code();
        var result = s.bean(CommonCodeService.class).set(new CodeCommand("DEFECT_CATEGORY", category, "화면", "UI",
                null, null, 25, null), TestOwners.OWNER);
        assertThat(result.code().state()).isEqualTo(CodeState.ACTIVE);
        assertThat(result.revision()).isEqualTo(before + 1);
        var fields = s.defectFields("d");
        fields.put("category", category);
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, fields)).version())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-CC-03: an inactive code stays, is refused for new writes and kept by existing rows")
    void cc03_inactive(Scenario s) {
        String category = "OLD_" + s.code();
        s.bean(CommonCodeService.class).set(new CodeCommand("DEFECT_CATEGORY", category, "옛", "Old", null, null, 30,
                null), TestOwners.OWNER);
        var fields = s.defectFields("d");
        fields.put("category", category);
        var defect = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, fields));
        s.bean(CommonCodeService.class).set(new CodeCommand("DEFECT_CATEGORY", category, "옛", "Old", null, null, 30,
                "INACTIVE"), TestOwners.OWNER);
        assertThat(s.bean(CommonCodeRepository.class).find(CodeGroup.DEFECT_CATEGORY, CodeValue.of(category)))
                .isPresent();
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, fields)),
                ValidationCode.CODE_INVALID);
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", defect.id(), 1,
                Map.of("title", "kept"))).version()).isEqualTo(2);
        assertThat(s.defect(defect.id()).category().value()).isEqualTo(category);
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

    /** A code command with one field at, or outside, its limit. */
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
                new Limit("code with a leading digit", named("1LIM", "k", "x", null), refused),
                new Limit("code of 32 characters", named("L".repeat(32), "k", "x", null), null),
                new Limit("code of 33 characters", named("L".repeat(33), "k", "x", null), refused),
                new Limit("code with a dash", named("LIM-G", "k", "x", null), refused));
    }

    @ParameterizedTest
    @MethodSource("limits")
    @DisplayName("SVC-CC-05: text and code limits")
    void cc05_limits(Limit limit, Scenario s) {
        assertThat(Expect.outcome(() -> s.bean(CommonCodeService.class).set(limit.command(), TestOwners.OWNER)))
                .isEqualTo(Optional.ofNullable(limit.expected()));
    }

    @Test
    @DisplayName("SVC-CC-06: an omitted English text falls back to the Korean text")
    void cc06_englishFallback(Scenario s) {
        var result = s.bean(CommonCodeService.class).set(new CodeCommand("RELATION_KIND", "CC06_" + s.code(), "한글",
                null, "설명", null, 41, null), TestOwners.OWNER);
        assertThat(result.code().nameEn()).isEqualTo("한글");
        assertThat(result.code().descriptionEn()).isEqualTo("설명");
    }

    @Test
    @DisplayName("SVC-CC-07: setting a code to what it already is does not bump the revision")
    void cc07_noSpuriousRevision(Scenario s) {
        String name = "CC07_" + s.code();
        s.bean(CommonCodeService.class).set(code("RELATION_KIND", name), TestOwners.OWNER);
        long before = revision(s);
        s.bean(CommonCodeService.class).set(code("RELATION_KIND", name), TestOwners.OWNER);
        assertThat(revision(s)).isEqualTo(before);
    }

    @Test
    @DisplayName("SVC-CC-08: a rolled back set leaves the revision as it was")
    void cc08_rollback(Scenario s) {
        long before = revision(s);
        String name = "CC08_" + s.code();
        assertThatThrownBy(() -> s.ctx().tx(() -> {
            s.bean(CommonCodeService.class).set(code("RELATION_KIND", name), TestOwners.OWNER);
            throw new IllegalStateException("rolled back");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(revision(s)).isEqualTo(before);
        assertThat(s.bean(CommonCodeRepository.class).find(CodeGroup.RELATION_KIND, CodeValue.of(name))).isEmpty();
    }

    @Test
    @DisplayName("SVC-CC-09: a list is ordered by sort order and code and starts with the seed")
    void cc09_ordering(Scenario s) {
        List<CommonCode> severities = s.bean(CommonCodeService.class).list(CodeGroup.DEFECT_SEVERITY);
        assertThat(severities.stream().map(c -> c.code().value())).containsExactly("P1", "P2", "P3", "UNRATED");
        assertThat(severities.getLast().state()).isEqualTo(CodeState.INACTIVE);
        assertThat(s.bean(CommonCodeService.class).list(CodeGroup.RELATION_KIND).stream()
                .map(c -> c.code().value())).startsWith("same_site", "caused_by", "blocks", "same_family",
                "same_fix_unit", "regression_of");
    }

    @Test
    @DisplayName("SVC-CC-10: with no category a defect cannot be created and the message points at the codes")
    void cc10_noCategories() {
        LedgerTestRunners.withoutCategories(fresh -> {
            Scenario s = new Scenario(fresh);
            var fields = s.defectFields("d");
            fields.remove("category");
            LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                    s.create("defect", null, fields)), ValidationCode.CODE_REQUIRED);
            assertThat(e.details()).containsEntry("valid", List.of());
            assertThat(e.args()).containsExactly("defect.category", "", "DEFECT_CATEGORY");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"1LEADING", "WITH SPACE"})
    @DisplayName("SVC-CC-05: a code that does not match the pattern is refused")
    void cc05_codePattern(String code, Scenario s) {
        Expect.failure(() -> s.bean(CommonCodeService.class).set(code("RELATION_KIND", code), TestOwners.OWNER),
                ValidationCode.VALIDATION_FAILED);
    }

    private static long auditRows(Scenario s, String subject) {
        return s.ctx().jdbc().sql("SELECT COUNT(*) FROM owner_audit WHERE subject = :subject").param("subject", subject)
                .query(Long.class).single();
    }

    private static long codeRows(Scenario s, String name) {
        return s.count("SELECT COUNT(*) FROM common_code WHERE code = ?", name);
    }

    @Test
    @DisplayName("SVC-CC-11 (TXF-002): two sets of the same code that read the same version: the one that writes first wins and the other is a VERSION_CONFLICT that names the version it read and the one now; it overwrites nothing, bumps no revision and writes no audit row")
    void cc11_aStaleSetIsAVersionConflict(Scenario s) {
        CommonCodeService real = s.bean(CommonCodeService.class);
        String name = "CC11_" + s.code();
        String subject = "RELATION_KIND/" + name;
        real.set(code("RELATION_KIND", name), TestOwners.OWNER);
        CodeCommand winner = new CodeCommand("RELATION_KIND", name, "이긴 쪽", "Winner", null, null, 31, null);
        CodeCommand loser = new CodeCommand("RELATION_KIND", name, "진 쪽", "Loser", null, null, 32, null);
        AtomicBoolean raced = new AtomicBoolean();
        // The repository of the service under test: after it has read the code, the other action reads the same row, writes
        // and commits on its own connection (a second thread), the interleaving that the old read-then-write overwrote.
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
        CommonCodeService service = new CommonCodeService(racing, s.bean(CommonCodeWriter.class),
                s.bean(CodeRevisionRepository.class), s.bean(OwnerAuditWriter.class), s.bean(LinkRepository.class));
        long audit = auditRows(s, subject);

        LedgerException failure = Expect.failure(() -> s.ctx().tx(() -> service.set(loser, TestOwners.OWNER)),
                ConflictCode.VERSION_CONFLICT);

        assertThat(raced).isTrue();
        assertThat(failure.args()).containsExactly("common_code", subject, "0", "1");
        CommonCode kept = s.bean(CommonCodeRepository.class).find(CodeGroup.RELATION_KIND, CodeValue.of(name)).orElseThrow();
        assertThat(kept.nameEn()).as("the winner is not overwritten").isEqualTo("Winner");
        assertThat(kept.sortOrder()).isEqualTo(31);
        assertThat(auditRows(s, subject)).as("one audit row for the winner, none for the loser").isEqualTo(audit + 1);
    }

    @Test
    @DisplayName("SVC-CC-14 (TXF-003): seed is one use case and one transaction: a list with one invalid code adds nothing (the codes before it are taken back with the revision and the audit rows), and the same list without it adds them all")
    void cc14_aSeedWithAnInvalidCodeAddsNothing(Scenario s) {
        CommonCodeService service = s.bean(CommonCodeService.class);
        String first = "CC14A_" + s.code();
        String second = "CC14B_" + s.code();
        long before = revision(s);
        List<CodeCommand> broken = List.of(named(first, "하나", "One", null), named(second, "둘", "Two", null),
                named("CC14C_" + s.code(), "", "Three", null));

        Expect.failure(() -> service.seed(broken, TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);

        assertThat(codeRows(s, first)).as("the code before the invalid one was taken back").isZero();
        assertThat(codeRows(s, second)).isZero();
        assertThat(revision(s)).as("no revision bump of the codes that were taken back").isEqualTo(before);
        assertThat(auditRows(s, "RELATION_KIND/" + first)).isZero();

        assertThat(service.seed(broken.subList(0, 2), TestOwners.OWNER)).isEqualTo(2);
        assertThat(codeRows(s, first)).isEqualTo(1);
        assertThat(codeRows(s, second)).isEqualTo(1);
        assertThat(revision(s)).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("SVC-CC-15 (TXF-003): seed answers how many codes it added; a code that exists is left exactly as the owner left it, and a second seed of the same list adds none")
    void cc15_seedAddsOnlyTheAbsentCodes(Scenario s) {
        CommonCodeService service = s.bean(CommonCodeService.class);
        String mine = "CC15A_" + s.code();
        String fresh = "CC15B_" + s.code();
        service.set(new CodeCommand("RELATION_KIND", mine, "내 이름", "My name", null, null, 77, "INACTIVE"), TestOwners.OWNER);
        List<CodeCommand> seed = List.of(named(mine, "시드", "Seed", null), named(fresh, "새것", "New", null));
        long before = revision(s);

        assertThat(service.seed(seed, TestOwners.OWNER)).isEqualTo(1);

        CommonCode kept = s.bean(CommonCodeRepository.class).find(CodeGroup.RELATION_KIND, CodeValue.of(mine)).orElseThrow();
        assertThat(kept.nameKo()).isEqualTo("내 이름");
        assertThat(kept.state()).isEqualTo(CodeState.INACTIVE);
        assertThat(codeRows(s, fresh)).isEqualTo(1);
        assertThat(revision(s)).isEqualTo(before + 1);
        assertThat(service.seed(seed, TestOwners.OWNER)).isZero();
        assertThat(revision(s)).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("SVC-CC-12 (TXF-002): 8 calls that add the same new code at the same time (addIfAbsent) leave one row: exactly one call adds it, the others answer empty, none fails, and the revision moves once with one audit row")
    void cc12_concurrentAddOfTheSameCode(Scenario s) {
        CommonCodeService service = s.bean(CommonCodeService.class);
        String name = "CC12_" + s.code();
        CodeCommand seed = new CodeCommand("RELATION_KIND", name, "시드", "Seed", null, null, 50, null);
        long before = revision(s);

        List<Callable<Optional<CodeResult>>> calls = IntStream.range(0, 8)
                .<Callable<Optional<CodeResult>>>mapToObj(i -> () -> service.addIfAbsent(seed, TestOwners.OWNER)).toList();
        List<Parallel.Outcome<Optional<CodeResult>>> outcomes = Parallel.all(calls);

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).as("the loser answers, it does not fail")
                .isNull());
        assertThat(outcomes.stream().filter(outcome -> outcome.value().isPresent())).hasSize(1);
        assertThat(codeRows(s, name)).isEqualTo(1);
        assertThat(revision(s)).isEqualTo(before + 1);
        assertThat(auditRows(s, "RELATION_KIND/" + name)).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-CC-13 (TXF-002): 8 calls that set the same new code at the same time create it once (the one creation path): none fails with a duplicate key, the one that lost the unique key changes the row of the winner like any existing code, and the row holds one of the commands")
    void cc13_concurrentSetOfTheSameNewCode(Scenario s) {
        CommonCodeService service = s.bean(CommonCodeService.class);
        String name = "CC13_" + s.code();
        List<Callable<CodeResult>> calls = IntStream.range(0, 8).<Callable<CodeResult>>mapToObj(i -> () -> service.set(
                new CodeCommand("RELATION_KIND", name, "이름 " + i, "Name " + i, null, null, 60 + i, null),
                TestOwners.OWNER)).toList();

        List<Parallel.Outcome<CodeResult>> outcomes = Parallel.all(calls);

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).as("no duplicate key reaches the caller")
                .satisfiesAnyOf(failure -> assertThat(failure).isNull(),
                        failure -> assertThat(failure).isInstanceOfSatisfying(LedgerException.class,
                                refused -> assertThat(refused.code()).isEqualTo(ConflictCode.VERSION_CONFLICT))));
        assertThat(outcomes.stream().filter(Parallel.Outcome::succeeded)).as("at least the call that created it")
                .isNotEmpty();
        assertThat(codeRows(s, name)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM owner_audit WHERE subject = ? AND detail LIKE 'added%'",
                "RELATION_KIND/" + name)).as("the code was created once").isEqualTo(1);
    }

    @Test
    @DisplayName("SET-023: addIfAbsent adds a new code with the revision bump and one audit row, and leaves a code that exists exactly as it is")
    void set023_addIfAbsentNeverOverwrites(Scenario s) {
        CommonCodeService service = s.bean(CommonCodeService.class);
        String name = "SEED_" + s.code();
        String subject = "DEFECT_CATEGORY/" + name;
        long before = revision(s);
        CodeCommand seed = new CodeCommand("DEFECT_CATEGORY", name, "시드", "Seed", "설명", "Description", 10, "ACTIVE");

        var added = service.addIfAbsent(seed, TestOwners.OWNER);

        assertThat(added).isPresent();
        assertThat(added.orElseThrow().revision()).isEqualTo(before + 1);
        assertThat(auditRows(s, subject)).isEqualTo(1);
        // the owner changes and retires the code; the seed command is repeated
        service.set(new CodeCommand("DEFECT_CATEGORY", name, "내 이름", "My name", "내 설명", "My description", 77, "INACTIVE"),
                TestOwners.OWNER);
        long afterOwner = revision(s);
        long auditAfterOwner = auditRows(s, subject);

        assertThat(service.addIfAbsent(seed, TestOwners.OWNER)).isEmpty();

        CommonCode kept = s.bean(CommonCodeRepository.class).find(CodeGroup.DEFECT_CATEGORY, CodeValue.of(name)).orElseThrow();
        assertThat(kept.nameKo()).isEqualTo("내 이름");
        assertThat(kept.sortOrder()).isEqualTo(77);
        assertThat(kept.state()).isEqualTo(CodeState.INACTIVE);
        assertThat(revision(s)).isEqualTo(afterOwner);
        assertThat(auditRows(s, subject)).isEqualTo(auditAfterOwner);
    }
}
