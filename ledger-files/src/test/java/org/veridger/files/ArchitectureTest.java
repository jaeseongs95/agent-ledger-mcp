// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.files;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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
import org.veridger.testing.ArchitectureRules;
import org.veridger.testing.CodingScan;
import org.veridger.testing.NoOneXRules;
import org.veridger.testing.PrincipleRules;
import org.veridger.testing.SourceRules;

/** REM-007 (D-0045): the files module is plain JDK code shared by the server, the CLI and the hook. */
class ArchitectureTest {

    private static final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("org.veridger.files");

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
    @DisplayName("REM-007 the module uses no network, process, reflection, JDBC, Spring, Jackson or other product module")
    void onlyTheJdkCore() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "java.net..", "javax.net..", "java.sql..", "javax.sql..",
                        "java.lang.reflect..", "org.springframework..", "tools.jackson..",
                        "com.fasterxml..", "org.veridger.domain..", "org.veridger.database..",
                        "org.veridger.application..", "org.veridger.hook..", "org.veridger.cli..",
                        "org.veridger.mcp..", "lombok..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Runtime")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.System")
                .check(classes);
    }

    @Test
    @DisplayName("R9-D0095-B the foreign function API is used by the boundary class WindowsAccountNames alone, and that class is reached by WellKnownAccounts alone (so a host without ACLs never loads it)")
    void theNativeBoundaryIsOneClass() {
        String boundary = "org\\.veridger\\.files\\.WindowsAccountNames(\\$.*)?";
        noClasses().that().haveNameNotMatching(boundary)
                .should().dependOnClassesThat().resideInAPackage("java.lang.foreign..").check(classes);
        noClasses().that().haveNameNotMatching(boundary).and().doNotHaveFullyQualifiedName("org.veridger.files.WellKnownAccounts")
                .should().dependOnClassesThat().haveNameMatching(boundary).check(classes);
        classes().that().haveFullyQualifiedName("org.veridger.files.WindowsAccountNames").should().notBePublic().check(classes);
    }

    @Test
    @DisplayName("REM-007 no mutable static state")
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
        assertThat(CodingScan.catchProblems(Set.of("WindowsAccountNames.java"), null, Path.of("src", "main", "java"),
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
