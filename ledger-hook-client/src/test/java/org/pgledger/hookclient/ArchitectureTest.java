// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hookclient;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.testing.ArchitectureRules;
import org.pgledger.testing.CodingScan;
import org.pgledger.testing.NoOneXRules;
import org.pgledger.testing.PrincipleRules;
import org.pgledger.testing.SourceRules;

/**
 * R9-D0096-D (D-0096): the light client that a host starts for every tool call loads java.base and the file protection of
 * {@code ledger-files} and nothing else: no Spring, no Jackson, no logging library, no module of the JDK beyond java.base
 * (the loaded classes of a real start are checked by the process test of the hook module).
 */
class ArchitectureTest {

    private static final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("org.pgledger.hookclient");

    /** ARC-PRN-01 .. 06 (TEST_CASES.md section 25): what this module allows of each rule (key to reason): nothing. */
    private static final Map<String, Map<String, String>> PRINCIPLE_ALLOWED = Map.of();

    static Stream<PrincipleRules.Rule> principleRules() {
        return PrincipleRules.all(classes, Path.of("src", "main", "java"), PRINCIPLE_ALLOWED).stream();
    }

    @ParameterizedTest
    @MethodSource("principleRules")
    void thePrincipleRuleHolds(PrincipleRules.Rule rule) {
        assertThat(rule.problems()).isEmpty();
    }

    @Test
    @DisplayName("R9-D0096-D the client depends only on java.base (no java.net.http, java.logging, java.sql, java.xml, javax, jdk, sun) and on ledger-files; no Spring, Jackson, SLF4J, Logback, Lombok or other product module")
    void onlyJavaBase() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "java.net.http..", "java.util.logging..", "java.sql..", "java.xml..", "java.desktop..",
                        "java.management..", "java.naming..", "javax..", "jdk..", "sun..", "com.sun..",
                        "org.springframework..", "tools.jackson..", "com.fasterxml..", "org.slf4j..", "ch.qos..",
                        "org.apache..", "lombok..", "org.pgledger.domain..", "org.pgledger.database..",
                        "org.pgledger.application..", "org.pgledger.attestation..", "org.pgledger.hook..",
                        "org.pgledger.cli..", "org.pgledger.mcp..")
                .check(classes);
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes().should().onlyDependOnClassesThat()
                .resideInAnyPackage("java.lang..", "java.io..", "java.nio..", "java.net..", "java.util..", "java.time..",
                        "java.security..", "org.pgledger.hookclient..", "org.pgledger.files..")
                .check(classes);
    }

    @Test
    @DisplayName("R9-D0096-D the process (properties, environment, standard streams, exit) is read only by Main; no class logs or prints")
    void processOnlyInMain() {
        noClasses().that().doNotHaveFullyQualifiedName("org.pgledger.hookclient.Main")
                .should().callMethod(System.class, "getenv")
                .orShould().callMethod(System.class, "getenv", String.class)
                .orShould().callMethod(System.class, "getProperty", String.class)
                .orShould().callMethod(System.class, "getProperty", String.class, String.class)
                .orShould().callMethod(System.class, "getProperties")
                .orShould().callMethod(System.class, "exit", int.class)
                .orShould().accessField(System.class, "in")
                .orShould().accessField(System.class, "out")
                .orShould().accessField(System.class, "err")
                .check(classes);
        noClasses().should().callMethod(Throwable.class, "printStackTrace").check(classes);
    }

    @Test
    @DisplayName("R9-D0096-D the client uses no reflection, foreign memory or Runtime; the one process it starts is the resident program, by ProcessBuilder in ResidentLauncher only")
    void processOnlyInTheLauncher() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("java.lang.reflect..", "java.lang.foreign..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Runtime").check(classes);
        noClasses().that().doNotHaveFullyQualifiedName("org.pgledger.hookclient.ResidentLauncher")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder").check(classes);
    }

    @Test
    @DisplayName("R9-D0096-D the socket is used only by ResidentClient (HTTP over the loopback), and only HttpURLConnection, never java.net.http")
    void networkOnlyInTheClient() {
        // Main names the exception of reading the location of its own jar (URISyntaxException), which is no connection
        noClasses().that().doNotHaveFullyQualifiedName("org.pgledger.hookclient.ResidentClient")
                .and().doNotHaveFullyQualifiedName("org.pgledger.hookclient.Main")
                .should().dependOnClassesThat().resideInAnyPackage("java.net..").check(classes);
    }

    @Test
    @DisplayName("R9-D0096-D no mutable static state")
    void noMutableStatics() {
        fields().that().areStatic().should().beFinal().check(classes);
    }

    @Test
    @DisplayName("TXF-001 no TransactionTemplate, no TransactionAspectSupport and no hand-made savepoint")
    void txf001_noHandMadeTransactions() {
        ArchitectureRules.noTransactionTemplate().check(classes);
        ArchitectureRules.noHandMadeTransactionControl().check(classes);
    }

    @Test
    @DisplayName("ARC-033 no operating system detection in main and test code")
    void noOperatingSystemDetection() {
        ArchitectureRules.noOperatingSystemDetection().check(classes);
        assertThat(SourceRules.operatingSystemDetection(Path.of("src", "main", "java"), Path.of("src", "test", "java")))
                .isEmpty();
    }

    @Test
    @DisplayName("ARC-036 catch rules: no empty catch, no broad catch")
    void catchRules() {
        assertThat(CodingScan.catchProblems(Set.of(), null, Path.of("src", "main", "java"),
                Path.of("src", "test", "java"))).isEmpty();
    }

    @Test
    @DisplayName("ARC-037 string rules: literal first in equals, no == on strings, no string switch, no intern(), no new String(")
    void stringRules() {
        assertThat(CodingScan.stringProblems(Set.of(), Path.of("src", "main", "java"), Path.of("src", "test", "java")))
                .isEmpty();
    }

    @Test
    @DisplayName("ARC-1X-01 no class or package of the module is named after 1.x")
    void arc1x01_noClassNamedAfterOneX() {
        NoOneXRules.noClassNamedAfterOneX().check(classes);
    }

    @Test
    @DisplayName("ARC-1X-02 no class of the module depends on the migration tool")
    void arc1x02_noDependencyOnTheMigrationTool() {
        NoOneXRules.noDependencyOnTheMigrationTool().check(classes);
    }

    @Test
    @DisplayName("ARC-1X-03 no main source or resource of the module names 1.x")
    void arc1x03_noOneXTextInMainSources() {
        assertThat(NoOneXRules.oneXTextInMainSources()).isEmpty();
    }

    @Test
    @DisplayName("OPK-017 no main source or resource of the module uses a name of the retired request key")
    void opk017_noRetiredRequestKeyName() {
        assertThat(NoOneXRules.retiredRequestKeyTextInMainSources()).isEmpty();
    }
}
