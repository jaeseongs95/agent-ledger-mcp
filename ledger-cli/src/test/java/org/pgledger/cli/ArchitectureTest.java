package org.pgledger.cli;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;

import java.io.FileDescriptor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.domain.value.OwnerIdentity;
import org.pgledger.testing.PrincipleRules;
import java.util.Map;
import java.util.stream.Stream;
import org.pgledger.cli.secret.ConsoleSecretInput;
import org.pgledger.cli.secret.SecretInput;
import org.pgledger.cli.secret.StandardInput;
import org.pgledger.cli.secret.StdinSecretInput;
import org.pgledger.testing.ArchitectureRules;
import org.pgledger.testing.CodingScan;
import org.pgledger.testing.NoOneXRules;
import org.pgledger.testing.SourceRules;
import org.springframework.beans.factory.annotation.Value;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

class ArchitectureTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.pgledger.cli");

    /** ARC-PRN-01 .. 06 (TEST_CASES.md section 25): what this module allows of each rule (key to reason). */
    private static final Map<String, Map<String, String>> PRINCIPLE_ALLOWED = Map.of(
            PrincipleRules.BEAN_OF_OWN_TYPE, Map.of(
                    "CliDriverConfiguration.driverRegistrationFileLocation",
                    "the value comes from a setting of this tool (ledger.cli.driver.file); the record is a class of the database "
                            + "module, which serves two programs and cannot know the setting of either",
                    "CliDriverConfiguration.driverRegistrar",
                    "the registrar is a class of the database module that serves two programs (this tool and the admin web) and "
                            + "takes what differs between them as arguments: the JDBC URL and the one directory a jar may be in, "
                            + "a setting of this tool; the module cannot make it a component",
                    "CliDriverDataSourceConfiguration.applicationAccountSettings",
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
    @DisplayName("SEC-001 permissions are given at creation: no code widens a file and narrows it later")
    void sec001_noPermissionChangeAfterCreation() {
        noClasses().should().callMethod(Files.class, "setPosixFilePermissions", Path.class, Set.class)
                .orShould().callMethod(Files.class, "setAttribute", Path.class, String.class, Object.class, LinkOption[].class)
                .orShould().callMethod(java.io.File.class, "setReadable", boolean.class, boolean.class)
                .orShould().callMethod(java.io.File.class, "setWritable", boolean.class, boolean.class)
                .orShould().callMethod(java.io.File.class, "setExecutable", boolean.class, boolean.class)
                .check(MAIN);
    }

    @Test
    @DisplayName("SEC-002 replacement is temp file plus move: no copy-over and no whole-file write helpers")
    void sec002_noInPlaceReplacement() {
        noClasses().should().callMethod(Files.class, "copy", Path.class, Path.class, java.nio.file.CopyOption[].class)
                .orShould().callMethod(Files.class, "write", Path.class, byte[].class, java.nio.file.OpenOption[].class)
                .orShould().callMethod(Files.class, "writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .check(MAIN);
    }

    @Test
    @DisplayName("SEC-003 no code reads the environment or starts a process")
    void sec003_noEnvironmentOrProcess() {
        noClasses().should().callMethod(System.class, "getenv")
                .orShould().callMethod(System.class, "getenv", String.class)
                .orShould().dependOnClassesThat().areAssignableTo(ProcessBuilder.class)
                .orShould().callMethod(Runtime.class, "exec", String.class)
                .check(MAIN);
    }

    @Test
    @DisplayName("SEC-003 (changed by install, SEC-023) standard input is touched by one class only, StandardInput: the command line option --stdin is the one opt-in way to give a secret without a terminal")
    void sec003_onlyStandardInputTouchesStandardInput() {
        noClasses().that().doNotHaveFullyQualifiedName(StandardInput.class.getName()).should()
                .accessField(System.class, "in").orShould().accessField(FileDescriptor.class, "in")
                .orShould().callMethod(System.class, "setIn", java.io.InputStream.class)
                .check(MAIN);
    }

    @Test
    @DisplayName("SEC-003 (changed by install, SEC-023) the console and the standard input are the only SecretInput implementations")
    void sec003_consoleAndStdinAreTheOnlyInputs() {
        classes().that().implement(SecretInput.class).should().haveFullyQualifiedName(ConsoleSecretInput.class.getName())
                .orShould().haveFullyQualifiedName(StdinSecretInput.class.getName()).check(MAIN);
    }

    @Test
    @DisplayName("Console output goes through one place only: no System.out/err or printStackTrace elsewhere")
    void consoleOutputHasOneSink() {
        noClasses().that().areNotAssignableTo(StreamCliOutput.class).should(ACCESS_STANDARD_STREAMS).check(MAIN);
    }

    @Test
    @DisplayName("ARC-033 no operating system detection in main and test code")
    void noOperatingSystemDetection() {
        ArchitectureRules.noOperatingSystemDetection().check(MAIN);
        assertThat(SourceRules.operatingSystemDetection(Path.of("src", "main", "java"), Path.of("src", "test", "java")))
                .isEmpty();
    }

    @Test
    @DisplayName("ARC-036 catch rules: no empty catch, no broad catch outside the test entry that plays the CLI boundary and the MCP client command")
    void arc036_catchRules() {
        // McpCheckCommand: the MCP client reports every transport failure as an unchecked exception of a type that depends on the layer
        // (SDK, Reactor, JDK); it sorts the cause chain and rethrows each case as a FailureException (see its Javadoc).
        assertThat(CodingScan.catchProblems(Set.of("CliExtension.java", "McpCheckCommand.java"), null, Path.of("src", "main", "java"),
                Path.of("src", "test", "java"))).isEmpty();
    }

    @Test
    @DisplayName("ARC-037 string rules: literal first in equals, no == on strings, no string switch, no intern(), no new String(")
    void arc037_stringRules() {
        noClasses().should().callMethod(String.class, "intern")
                .orShould().callConstructor(String.class, String.class)
                .orShould().callConstructor(String.class, byte[].class)
                .orShould().callConstructor(String.class, byte[].class, java.nio.charset.Charset.class)
                .check(MAIN);
        assertThat(CodingScan.stringProblems(Set.of(), Path.of("src", "main", "java"), Path.of("src", "test", "java")))
                .isEmpty();
    }

    @Test
    @DisplayName("The code of the module does not touch JDBC or a DataSource: the database is reached only through the application services and the configuration of the database module")
    void noJdbcDependency() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..")
                .check(MAIN);
    }

    @Test
    @DisplayName("TXD-005 (principle 4.4) the CLI opens no transaction: no TransactionTemplate, no @Transactional; a transaction is a method of an application service")
    void txd005_noTransactionBoundaryInTheCli() {
        ArchitectureRules.noTransactionTemplate().check(MAIN);
        // TXF-001: no TransactionAspectSupport and no savepoint of its own either
        ArchitectureRules.noHandMadeTransactionControl().check(MAIN);
        ArchitectureRules.transactionalOnlyOnServiceClasses().check(MAIN);
        ArchitectureRules.transactionalOnlyOnServiceMethods().check(MAIN);
    }

    @Test
    @DisplayName("TXD-010 (principles 3.9, 4.4) the CLI makes no JdbcClient, no TransactionTemplate and no transaction manager of its own")
    void txd010_noOwnJdbcPlumbingInTheCli() {
        ArchitectureRules.noOwnJdbcPlumbing().check(MAIN);
    }

    @Test
    @DisplayName("TXF-003 (principle 4.4) setup seeds its codes with one call of CommonCodeService.seed (one use case, one transaction), not with one call of addIfAbsent per code")
    void txf003_setupSeedsInOneServiceCall() {
        noClasses().that().haveFullyQualifiedName("org.pgledger.cli.setup.SetupCommand").should()
                .callMethod(CommonCodeService.class, "addIfAbsent", CommonCodeService.CodeCommand.class,
                        OwnerIdentity.class)
                .check(MAIN);
        classes().that().haveFullyQualifiedName("org.pgledger.cli.setup.SetupCommand").should()
                .callMethod(CommonCodeService.class, "seed", List.class, OwnerIdentity.class).check(MAIN);
    }

    @Test
    @DisplayName("TXD-007 (principle 3.7) the CLI makes no JsonMapper of its own: the Spring Boot bean is injected")
    void txd007_noOwnJsonMapper() {
        ArchitectureRules.noOwnJsonMapper().check(MAIN);
    }

    @Test
    @DisplayName("Configuration comes from ConfigurationProperties records, never @Value")
    void noValueAnnotation() {
        noFields().should().beAnnotatedWith(Value.class).check(MAIN);
        noClasses().should().dependOnClassesThat(belongToAnyOf(Value.class)).check(MAIN);
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
}
