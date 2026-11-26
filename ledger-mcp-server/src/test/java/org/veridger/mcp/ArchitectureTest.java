// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.mcp.archfixture.LeakyTokenCheck;
import org.veridger.testing.ArchitectureRules;
import org.veridger.testing.CodingScan;
import org.veridger.testing.NoOneXRules;
import org.veridger.testing.PrincipleRules;
import org.veridger.testing.SourceRules;
import java.util.Map;

/**
 * The ARC rules that apply to the MCP server module (the shared rules come from the attestation test fixtures), and the
 * rules of the MCP server itself: reflection only in the binding package, tools call services only, the token is
 * compared in constant time.
 */
class ArchitectureTest {

    private static final JavaClasses MAIN = ArchitectureRules.productionClasses("org.veridger.mcp");
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");

    /** ARC-PRN-01 .. 06 (TEST_CASES.md section 25): what this module allows of each rule (key to reason). */
    private static final Map<String, Map<String, String>> PRINCIPLE_ALLOWED = Map.of();

    static Stream<PrincipleRules.Rule> principleRules() {
        return PrincipleRules.all(MAIN, MAIN_JAVA, PRINCIPLE_ALLOWED).stream();
    }

    @ParameterizedTest
    @MethodSource("principleRules")
    void thePrincipleRuleHolds(PrincipleRules.Rule rule) {
        assertThat(rule.problems()).isEmpty();
    }

    @Test
    @DisplayName("W7-AGPL-02 the source link (ledger.source.url) is for the users of the admin web: nothing of the MCP server, its sources and its resources, mentions it")
    void noSourceLinkInTheMcpServer() throws java.io.IOException {
        try (Stream<Path> files = java.nio.file.Files.walk(Path.of("src", "main"))) {
            assertThat(files.filter(java.nio.file.Files::isRegularFile).filter(file -> {
                try {
                    return java.nio.file.Files.readString(file).contains("ledger.source");
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList()).isEmpty();
        }
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

    private static final DescribedPredicate<JavaMethodCall> COMPARES_TEXT_OR_BYTES = DescribedPredicate.describe(
            "compares a text or bytes without MessageDigest.isEqual (String.equals, equalsIgnoreCase, contentEquals, "
                    + "Arrays.equals, Objects.equals)",
            call -> (call.getTargetOwner().isEquivalentTo(String.class)
                    && Set.of("equals", "equalsIgnoreCase", "contentEquals", "compareTo")
                            .contains(call.getTarget().getName()))
                    || (call.getTargetOwner().isEquivalentTo(java.util.Arrays.class)
                            && "equals".equals(call.getTarget().getName()))
                    || (call.getTargetOwner().isEquivalentTo(java.util.Objects.class)
                            && "equals".equals(call.getTarget().getName())));

    /** The rule of ARC-020 for a package: no class of it compares a text or bytes by the ordinary methods. */
    private static ArchRule noOrdinaryComparisonIn(String packageName) {
        return noClasses().that().resideInAPackage(packageName).should().callMethodWhere(COMPARES_TEXT_OR_BYTES);
    }

    static Stream<Rule> rules() {
        return Stream.of(
                new Rule("ARC-001 no Lombok", ArchitectureRules.noLombok()),
                new Rule("ARC-002 no setAccessible", ArchitectureRules.noSetAccessible()),
                new Rule("ARC-003 reflection only in the binding package",
                        ArchitectureRules.reflectionOnlyIn("org.veridger.mcp.reflection..")),
                new Rule("ARC-004 no process execution", ArchitectureRules.noProcessExecution()),
                new Rule("ARC-006 no standard streams, SLF4J only", ArchitectureRules.noStandardStreamsAndOtherLoggers()),
                new Rule("ARC-007 no getenv, no @Value", ArchitectureRules.noEnvironmentAccessAndValueAnnotation()),
                new Rule("ARC-010 no JSON tree models", ArchitectureRules.noJsonTreeModels()),
                new Rule("ARC-018 no ThreadLocal", ArchitectureRules.noThreadLocalAndNoMutableStatics()),
                new Rule("ARC-018 static fields are final", ArchitectureRules.staticFieldsAreFinal()),
                new Rule("ARC-019/024 no ambient time", ArchitectureRules.noAmbientTime()),
                new Rule("ARC-033 no operating system detection", ArchitectureRules.noOperatingSystemDetection()),
                new Rule("R9-D0090 the entry point pins the JVM zone to UTC", ArchitectureRules.mainPinsTheJvmZone()),
                new Rule("ARC-015 no switch over a String", noClasses().should().callMethodWhere(CALLS_STRING_HASH_CODE)),
                new Rule("ARC-012 JDBC clients are not used by the MCP server", noClasses().should()
                        .dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
                        .orShould().dependOnClassesThat().haveFullyQualifiedName(
                                "org.springframework.jdbc.core.JdbcTemplate")
                        .orShould().dependOnClassesThat().haveFullyQualifiedName(
                                "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")),
                new Rule("TXD-007 no JsonMapper of its own in the MCP server", ArchitectureRules.noOwnJsonMapper()),
                new Rule("TXD-005 no TransactionTemplate in the MCP server", ArchitectureRules.noTransactionTemplate()),
                new Rule("TXF-001 no TransactionAspectSupport and no hand-made savepoint in the MCP server",
                        ArchitectureRules.noHandMadeTransactionControl()),
                new Rule("TXD-010 no JdbcClient.create, no new TransactionTemplate, no new DataSourceTransactionManager in the MCP server",
                        ArchitectureRules.noOwnJdbcPlumbing()),
                new Rule("TXD-005 @Transactional on no class of the MCP server but a @Service",
                        ArchitectureRules.transactionalOnlyOnServiceClasses()),
                new Rule("TXD-005 @Transactional on no method of the MCP server but a method of a @Service",
                        ArchitectureRules.transactionalOnlyOnServiceMethods()),
                new Rule("the tools call services only: no class of the tool package depends on a repository",
                        noClasses().that().resideInAPackage("org.veridger.mcp.tool..").should().dependOnClassesThat()
                                .haveSimpleNameEndingWith("Repository")),
                new Rule("MCC-PRN-03 the adapters reach the database through the application: only the configuration "
                        + "and the application class name the database module",
                        noClasses().that().resideOutsideOfPackage("org.veridger.mcp.config..")
                                .and().doNotHaveSimpleName("LedgerMcpServerApplication").should().dependOnClassesThat()
                                .resideInAPackage("org.veridger.database..")),
                new Rule("MCC-PRN-03 the MCP server names no admin error code",
                        noClasses().should().dependOnClassesThat().haveFullyQualifiedName(
                                "org.veridger.domain.error.AdminCode")),
                new Rule("MCC-PRN-02 the size filter only throws: it builds no problem detail",
                        noClasses().that().haveSimpleName("RequestSizeFilter").should().dependOnClassesThat()
                                .haveFullyQualifiedName("org.springframework.http.ProblemDetail")),
                new Rule("ARC-020 the security package compares nothing with String.equals or Arrays.equals",
                        noOrdinaryComparisonIn("org.veridger.mcp.security..")),
                new Rule("ARC-020 the agent token is compared with MessageDigest.isEqual",
                        classes().that().haveSimpleName("AgentTokenProvider").should().callMethod(MessageDigest.class,
                                "isEqual", byte[].class, byte[].class)),
                new Rule("ARC-029 no JPA", noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.persistence..", "org.hibernate.orm..", "org.springframework.data.jpa..")));
    }

    @ParameterizedTest
    @MethodSource("rules")
    @DisplayName("ARC: the rule holds for the MCP server")
    void theRuleHolds(Rule rule) {
        rule.rule().check(MAIN);
    }

    @Test
    @DisplayName("ARC-020: the rule against ordinary token comparison fails on a class that compares with "
            + "String.equals (the rule can fail)")
    void theComparisonRuleCanFail() {
        JavaClasses leaky = new ClassFileImporter().importClasses(LeakyTokenCheck.class);

        assertThatThrownBy(() -> noOrdinaryComparisonIn("..archfixture..").check(leaky))
                .isInstanceOf(AssertionError.class).hasMessageContaining("LeakyTokenCheck");
    }

    @Test
    @DisplayName("ARC-008/025/032 and the text part of ARC-002: sources of the server and of its tests")
    void sourceRules() {
        assertThat(SourceRules.swallowedOrBroadCatches(MAIN_JAVA, Set.of())).isEmpty();
        assertThat(SourceRules.swallowedOrBroadCatches(TEST_JAVA, Set.of())).isEmpty();
        assertThat(SourceRules.utilityNames(MAIN_JAVA)).isEmpty();
        assertThat(SourceRules.vendorNames(MAIN_JAVA)).isEmpty();
        assertThat(SourceRules.forbiddenBuildSwitches(java.util.List.of(Path.of("build.gradle.kts")))).isEmpty();
    }

    @Test
    @DisplayName("ARC-036 catch rules: no empty catch, no broad catch outside the MCP boundary ToolResults.run")
    void arc036_catchRules() {
        assertThat(CodingScan.catchProblems(Set.of("ToolResults.java"), null, MAIN_JAVA, TEST_JAVA)).isEmpty();
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
    @DisplayName("ARC-033: neither the sources nor the tests detect the operating system")
    void noOperatingSystemDetectionInSources() {
        assertThat(SourceRules.operatingSystemDetection(MAIN_JAVA, TEST_JAVA)).isEmpty();
    }

    @Test
    @DisplayName("ARC-033: the test classes do not switch by operating system or by the path separator")
    void testsDoNotSwitchByOperatingSystem() {
        JavaClasses tests = new ClassFileImporter().importPackages("org.veridger.mcp");

        ArchitectureRules.noOperatingSystemDetection().check(tests);
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
}
