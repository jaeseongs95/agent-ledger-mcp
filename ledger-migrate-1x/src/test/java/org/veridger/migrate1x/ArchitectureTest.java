// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.migrate1x;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.testing.ArchitectureRules;
import org.veridger.testing.CodingScan;
import org.veridger.testing.PrincipleRules;
import org.veridger.testing.SourceRules;

/**
 * The architecture and source rules of the migration tool (M1X-007, M1X-008 and the rules every module of the product keeps). The
 * rules of the secret classes (the standard input, the console, the implementations of {@code SecretInput}, the one sink of the
 * output) belong to {@code ledger-cli}, whose classes these are, and are checked by the {@code ArchitectureTest} of that module.
 */
class ArchitectureTest {

    /** The one class that starts a process (a package-private class, so named by its name). */
    private static final String SERVICE_CONTROL = "org.veridger.migrate1x.host.SystemServiceControl";

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.veridger.migrate1x");

    /** ARC-PRN-01 .. 06 (TEST_CASES.md section 25): what this module allows of each rule (key to reason). */
    private static final Map<String, Map<String, String>> PRINCIPLE_ALLOWED = Map.of(
            PrincipleRules.BEAN_OF_OWN_TYPE, Map.of(
                    "DriverDataSourceConfiguration.applicationAccountSettings",
                    "the settings of the application account are bound from a filtered view of the environment, without the "
                            + "source of the owner account, which no annotation can say; the record belongs to the database module"));

    static Stream<PrincipleRules.Rule> principleRules() {
        return PrincipleRules.all(MAIN, Path.of("src", "main", "java"), PRINCIPLE_ALLOWED).stream();
    }

    @ParameterizedTest
    @MethodSource("principleRules")
    void thePrincipleRuleHolds(PrincipleRules.Rule rule) {
        assertThat(rule.problems()).isEmpty();
    }

    @Test
    @DisplayName("M1X-007 a process is started in SystemServiceControl only (ProcessBuilder, Runtime.exec)")
    void m1x007_onlyTheServiceControlStartsAProcess() {
        noClasses().that().doNotHaveFullyQualifiedName(SERVICE_CONTROL).should()
                .dependOnClassesThat().areAssignableTo(ProcessBuilder.class)
                .orShould().callMethod(Runtime.class, "exec", String.class)
                .orShould().callMethod(Runtime.class, "exec", String[].class)
                .check(MAIN);
    }

    @Test
    @DisplayName("M1X-008 no class of the tool has the simple name of a class of ledger-cli: what the tool needs of the command line machinery and the secret store it uses and does not copy")
    void m1x008_noClassOfTheToolIsACopyOfAClassOfLedgerCli() {
        // ledger-cli is on the classpath of the tool as its plain jar (the tool uses its classes, without its libraries)
        JavaClasses cli = new ClassFileImporter().importPackages("org.veridger.cli");
        Set<String> cliNames = simpleNamesOfTopLevelClasses(cli);
        Set<String> toolNames = simpleNamesOfTopLevelClasses(MAIN);

        assertThat(cliNames).as("the classes of ledger-cli are on the classpath of the tool").contains("CliRunner", "CliOptions");
        assertThat(toolNames).as("the classes of the tool").contains("ToolCommand", "MigrationError");
        Set<String> copies = new TreeSet<>(toolNames);
        copies.retainAll(cliNames);
        assertThat(copies).as("classes of the tool that have the name of a class of ledger-cli").isEmpty();
    }

    /** The simple names of the classes that are no nested and no anonymous class (and no package or module description). */
    private static Set<String> simpleNamesOfTopLevelClasses(JavaClasses classes) {
        Set<String> names = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            String name = javaClass.getSimpleName();
            if (!javaClass.isNestedClass() && !name.equals("package-info") && !name.equals("module-info")) {
                names.add(name);
            }
        }
        return names;
    }

    @Test
    @DisplayName("M1X-007 ARC-033 no operating system detection in main and test code")
    void m1x007_noOperatingSystemDetection() {
        ArchitectureRules.noOperatingSystemDetection().check(MAIN);
        ArchitectureRules.fileSystemCapabilityIsProbedInFilesOnly().check(MAIN);
        ArchitectureRules.mainPinsTheJvmZone().check(MAIN);
        assertThat(SourceRules.operatingSystemDetection(Path.of("src", "main", "java"), Path.of("src", "test", "java")))
                .isEmpty();
    }

    @Test
    @DisplayName("M1X-007 the class comment of SystemServiceControl says why Java cannot do what it does")
    void m1x007_theProcessCallsAreJustified() throws java.io.IOException {
        String source = java.nio.file.Files.readString(Path.of("src", "main", "java", "org", "veridger", "migrate1x", "host",
                "SystemServiceControl.java"));
        assertThat(source).contains("Why a process and no Java API").contains("systemctl").doesNotContain("sc.exe");
    }

    @Test
    @DisplayName("ARC-036 catch rules: no empty catch, no broad catch outside the test entry that plays the boundary")
    void arc036_catchRules() {
        assertThat(CodingScan.catchProblems(Set.of("ToolExtension.java"), null, Path.of("src", "main", "java"),
                Path.of("src", "test", "java"))).isEmpty();
    }
}
