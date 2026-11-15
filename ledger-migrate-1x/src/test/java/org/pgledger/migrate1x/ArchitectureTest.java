package org.pgledger.migrate1x;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.FileDescriptor;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.migrate1x.secret.ConsoleSecretInput;
import org.pgledger.migrate1x.secret.SecretInput;
import org.pgledger.migrate1x.secret.StandardInput;
import org.pgledger.migrate1x.secret.StdinSecretInput;
import org.pgledger.testing.ArchitectureRules;
import org.pgledger.testing.CodingScan;
import org.pgledger.testing.PrincipleRules;
import org.pgledger.testing.SourceRules;

/** The architecture and source rules of the migration tool (M1X-007 and the rules every module of the product keeps). */
class ArchitectureTest {

    /** The one class that starts a process, and the one that prints (package-private classes, so named by their names). */
    private static final String SERVICE_CONTROL = "org.pgledger.migrate1x.host.SystemServiceControl";
    private static final String STREAM_OUTPUT = "org.pgledger.migrate1x.tool.StreamOutput";

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.pgledger.migrate1x");

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
    @DisplayName("M1X-007 standard input is touched by one class only, StandardInput: --stdin is the one opt-in way to give a secret without a terminal")
    void m1x007_onlyStandardInputTouchesStandardInput() {
        noClasses().that().doNotHaveFullyQualifiedName(StandardInput.class.getName()).should()
                .accessField(System.class, "in").orShould().accessField(FileDescriptor.class, "in")
                .orShould().callMethod(System.class, "setIn", java.io.InputStream.class)
                .check(MAIN);
    }

    @Test
    @DisplayName("M1X-007 the console and the standard input are the only SecretInput implementations")
    void m1x007_consoleAndStdinAreTheOnlyInputs() {
        classes().that().implement(SecretInput.class).should().haveFullyQualifiedName(ConsoleSecretInput.class.getName())
                .orShould().haveFullyQualifiedName(StdinSecretInput.class.getName()).check(MAIN);
    }

    @Test
    @DisplayName("M1X-007 console output goes through one place only: no System.out/err or printStackTrace elsewhere")
    void m1x007_consoleOutputHasOneSink() {
        noClasses().that().doNotHaveFullyQualifiedName(STREAM_OUTPUT).should(ACCESS_STANDARD_STREAMS).check(MAIN);
    }

    @Test
    @DisplayName("M1X-007 ARC-033 no operating system detection in main and test code")
    void m1x007_noOperatingSystemDetection() {
        ArchitectureRules.noOperatingSystemDetection().check(MAIN);
        assertThat(SourceRules.operatingSystemDetection(Path.of("src", "main", "java"), Path.of("src", "test", "java")))
                .isEmpty();
    }

    @Test
    @DisplayName("M1X-007 the class comment of SystemServiceControl says why Java cannot do what it does")
    void m1x007_theProcessCallsAreJustified() throws java.io.IOException {
        String source = java.nio.file.Files.readString(Path.of("src", "main", "java", "org", "pgledger", "migrate1x", "host",
                "SystemServiceControl.java"));
        assertThat(source).contains("Why a process and no Java API").contains("systemctl").contains("sc.exe");
    }

    @Test
    @DisplayName("ARC-036 catch rules: no empty catch, no broad catch outside the test entry that plays the boundary")
    void arc036_catchRules() {
        assertThat(CodingScan.catchProblems(Set.of("ToolExtension.java"), null, Path.of("src", "main", "java"),
                Path.of("src", "test", "java"))).isEmpty();
    }
}
