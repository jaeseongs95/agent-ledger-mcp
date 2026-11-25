// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.testing.ArchitectureRules;
import org.pgledger.testing.CodingScan;
import org.pgledger.testing.NoOneXRules;
import org.pgledger.testing.PrincipleRules;
import org.pgledger.testing.SourceRules;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The ARC rules that apply to the application module (the shared rules come from the attestation test fixtures). */
class ArchitectureTest {

    private static final JavaClasses MAIN = ArchitectureRules.productionClasses("org.pgledger.application");
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");

    /** ARC-PRN-01 .. 06 (TEST_CASES.md section 25): what this module allows of each rule (key to reason). */
    private static final Map<String, Map<String, String>> PRINCIPLE_ALLOWED = Map.of(
            PrincipleRules.OR_ELSE_NULL, Map.of());

    static Stream<PrincipleRules.Rule> principleRules() {
        return PrincipleRules.all(MAIN, MAIN_JAVA, PRINCIPLE_ALLOWED).stream();
    }

    @ParameterizedTest
    @MethodSource("principleRules")
    void thePrincipleRuleHolds(PrincipleRules.Rule rule) {
        assertThat(rule.problems()).isEmpty();
    }
    private static final Path TEST_JAVA = Path.of("src", "test", "java");

    /** One rule of the catalogue under its ID. */
    private record Rule(String id, ArchRule rule) {
        @Override
        public String toString() {
            return id;
        }
    }

    private static final DescribedPredicate<JavaMethodCall> CALLS_STRING_HASH_CODE = DescribedPredicate.describe(
            "calls String.hashCode (the first step of a switch over a String)",
            call -> "hashCode".equals(call.getTarget().getName()) && call.getTargetOwner().isEquivalentTo(String.class));

    private static final DescribedPredicate<JavaMethodCall> INSERTS_HISTORY = DescribedPredicate.describe(
            "inserts into the history", call -> "insert".equals(call.getTarget().getName())
                    && call.getTargetOwner().isAssignableTo(HistoryRepository.class));

    static Stream<Rule> rules() {
        return Stream.of(
                new Rule("ARC-001 no Lombok", ArchitectureRules.noLombok()),
                new Rule("ARC-002 no setAccessible", ArchitectureRules.noSetAccessible()),
                new Rule("ARC-003 no reflection", ArchitectureRules.reflectionOnlyIn("org.pgledger.nowhere..")),
                new Rule("ARC-004 no process execution", ArchitectureRules.noProcessExecution()),
                new Rule("ARC-006 no standard streams, SLF4J only", ArchitectureRules.noStandardStreamsAndOtherLoggers()),
                new Rule("ARC-007 no getenv, no @Value", ArchitectureRules.noEnvironmentAccessAndValueAnnotation()),
                new Rule("ARC-010 no JSON tree models", ArchitectureRules.noJsonTreeModels()),
                new Rule("ARC-018 no ThreadLocal", ArchitectureRules.noThreadLocalAndNoMutableStatics()),
                new Rule("ARC-018 static fields are final", ArchitectureRules.staticFieldsAreFinal()),
                new Rule("ARC-019/024 no ambient time", ArchitectureRules.noAmbientTime()),
                new Rule("ARC-009 no web in the application module", noClasses().should().dependOnClassesThat()
                        .resideInAnyPackage("org.springframework.web..", "org.springframework.http..",
                                "jakarta.servlet..")),
                new Rule("ARC-009 the application does not reach into schema or driver code of the database module",
                        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                                "org.pgledger.database.schema..", "org.pgledger.database.driver..")),
                new Rule("ARC-012 JDBC clients only in the repository package", noClasses().should()
                        .dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
                        .orShould().dependOnClassesThat().haveFullyQualifiedName(
                                "org.springframework.jdbc.core.JdbcTemplate")
                        .orShould().dependOnClassesThat().haveFullyQualifiedName(
                                "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")),
                new Rule("ARC-039 no retry of a transaction method (principle 4.4: a lost race is decided by the affected "
                        + "rows of a conditional statement, never by running the transaction again)", noClasses().should()
                        .dependOnClassesThat().resideInAnyPackage("org.springframework.resilience..",
                                "org.springframework.retry..")),
                new Rule("ARC-013 no TransactionTemplate", noClasses().should().dependOnClassesThat()
                        .haveFullyQualifiedName("org.springframework.transaction.support.TransactionTemplate")),
                new Rule("TXF-001 no TransactionAspectSupport and no hand-made savepoint (principle 4.4: no nested write "
                        + "either, see TXP-001)", ArchitectureRules.noHandMadeTransactionControl()),
                new Rule("ARC-013 @Transactional only on services", noClasses().that().areNotAnnotatedWith(
                        Service.class).should().beAnnotatedWith(Transactional.class)),
                new Rule("ARC-015 no switch over a String", noClasses().should().callMethodWhere(
                        CALLS_STRING_HASH_CODE)),
                new Rule("ARC-011 only the history writer inserts history rows", noClasses().that()
                        .doNotHaveFullyQualifiedName(HistoryWriter.class.getName()).should()
                        .callMethodWhere(INSERTS_HISTORY)),
                new Rule("ARC-029 no JPA", noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.persistence..", "org.hibernate.orm..", "org.springframework.data.jpa..")));
    }

    @ParameterizedTest
    @MethodSource("rules")
    void theRuleHolds(Rule rule) {
        rule.rule().check(MAIN);
    }

    @Test
    @DisplayName("ARC-013: every service declares its transaction once, at class level")
    void servicesAreTransactionalOnTheClass() {
        classes().that().areAnnotatedWith(Service.class).should().beAnnotatedWith(Transactional.class).check(MAIN);
    }

    @Test
    @DisplayName("ARC-036 catch rules: no empty catch, no broad catch")
    void arc036_catchRules() {
        assertThat(CodingScan.catchProblems(Set.of(), null, MAIN_JAVA, TEST_JAVA)).isEmpty();
    }

    @Test
    @DisplayName("ARC-037 string rules: literal first in equals, no == on strings, no string switch, no intern(), no new String(")
    void arc037_stringRules() {
        noClasses().should().callMethod(String.class, "intern")
                .orShould().callConstructor(String.class, String.class)
                .orShould().callConstructor(String.class, byte[].class)
                .orShould().callConstructor(String.class, byte[].class, java.nio.charset.Charset.class)
                .check(MAIN);
        assertThat(CodingScan.stringProblems(Set.of(), MAIN_JAVA, TEST_JAVA)).isEmpty();
    }

    @Test
    @DisplayName("ARC-008/025/032 and the text part of ARC-002: sources")
    void sourceRules() {
        assertThat(SourceRules.swallowedOrBroadCatches(MAIN_JAVA, Set.of())).isEmpty();
        assertThat(SourceRules.utilityNames(MAIN_JAVA)).isEmpty();
        assertThat(SourceRules.vendorNames(MAIN_JAVA)).isEmpty();
        assertThat(SourceRules.forbiddenBuildSwitches(List.of(Path.of("build.gradle.kts")))).isEmpty();
    }

    @Test
    @DisplayName("ARC-038: every owner operation of the services names its actor; no main code has an actor-less path or "
            + "the system actor")
    void arc038_ownerOperationsNameTheirActor() throws java.io.IOException {
        Set<String> operations = Set.of("grant", "revoke", "set", "register", "registerReplacing", "grantWithKey",
                "grantReplacingKey", "seed", "issue");
        for (Class<?> service : List.of(org.pgledger.application.admin.GrantService.class,
                org.pgledger.application.admin.CommonCodeService.class,
                org.pgledger.application.admin.SessionKeyService.class,
                org.pgledger.application.admin.SessionTokenService.class)) {
            for (java.lang.reflect.Method method : service.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPublic(method.getModifiers()) && operations.contains(method.getName())) {
                    assertThat(method.getParameterTypes()).as(method.toString())
                            .contains(org.pgledger.domain.value.OwnerIdentity.class);
                }
            }
        }
        try (Stream<Path> files = java.nio.file.Files.walk(MAIN_JAVA)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                assertThat(java.nio.file.Files.readString(file)).as(file.toString()).doesNotContain("OwnerIdentity.SYSTEM")
                        .doesNotContain("OwnerIdentity.of(\"system\")");
            }
        }
    }

    @Test
    @DisplayName("ARC-1X-01 no class or package of the module is named after 1.x")
    void arc1x01_noClassNamedAfterOneX() {
        NoOneXRules.noClassNamedAfterOneX().check(MAIN);
    }

    @Test
    @DisplayName("ARC-1X-02 no class of the module depends on the migration tool")
    void arc1x02_noDependencyOnTheMigrationTool() {
        NoOneXRules.noDependencyOnTheMigrationTool().check(MAIN);
    }

    @Test
    @DisplayName("ARC-1X-03 no main source or resource of the module names 1.x")
    void arc1x03_noOneXTextInMainSources() {
        assertThat(NoOneXRules.oneXTextInMainSources()).isEmpty();
    }

    @Test
    @DisplayName("OPK-017 no main source or resource of the module uses a name of the retired request key (request_id, batch_request_id, RequestId): the key of a write is the operationKey")
    void opk017_noRetiredRequestKeyName() {
        assertThat(NoOneXRules.retiredRequestKeyTextInMainSources()).isEmpty();
    }

    @Test
    @DisplayName("OPK-024 the batch has one entry: BatchService has no public method batch(String, String, String) (the text form of ops) and LedgerJson has no readInputList; the only public batch method takes the actor, the operation key and the list of operations")
    void opk024_theBatchHasOneEntry() {
        Class<?> service = org.pgledger.application.write.BatchService.class;
        assertThat(java.util.Arrays.stream(service.getMethods()).filter(method -> "batch".equals(method.getName()))
                .map(method -> java.util.Arrays.toString(method.getParameterTypes())).toList())
                .as("the public methods named batch of BatchService")
                .containsExactly("[class java.lang.String, class java.lang.String, interface java.util.List]");
        assertThat(java.util.Arrays.stream(service.getDeclaredMethods())
                .filter(method -> "batch".equals(method.getName()))
                .map(method -> java.util.Arrays.toString(method.getParameterTypes())).toList())
                .as("no method named batch of any visibility takes the text form of ops")
                .doesNotContain("[class java.lang.String, class java.lang.String, class java.lang.String]");
        assertThat(java.util.Arrays.stream(org.pgledger.application.json.LedgerJson.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)).as("the methods of LedgerJson").doesNotContain("readInputList");
        assertThat(java.util.Arrays.stream(org.pgledger.application.json.LedgerJson.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)).as("LedgerJson has no strict mapper any more")
                .doesNotContain("strict");
    }

    @Test
    @DisplayName("R9-D0091 R9-D0092 the kind of the file system (POSIX, ACL, DOS) is asked in ledger-files only")
    void r9d0091_fileSystemCapabilityIsProbedInFilesOnly() {
        ArchitectureRules.fileSystemCapabilityIsProbedInFilesOnly().check(MAIN);
    }
}
