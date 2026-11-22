// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.nio.file.Path;
import java.util.Set;
import org.pgledger.testing.ArchitectureRules;
import org.pgledger.testing.CodingScan;
import org.pgledger.testing.SourceRules;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.testing.NoOneXRules;
import org.pgledger.testing.PrincipleRules;
import java.util.Map;
import java.util.stream.Stream;

/**
 * HK-018 (code side): no network, process, reflection, web or data access in any hook class; file system access only in
 * the {@code keys} package; the process (System) only in Main and ProcessChannels.
 */
class ArchitectureTest {

    private static final String KEYS_PACKAGE = "org.pgledger.hook.keys..";

    private static final JavaClasses hook = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("org.pgledger.hook");

    /** ARC-PRN-01 .. 06 (TEST_CASES.md section 25): what this module allows of each rule (key to reason). The hook allows nothing. */
    private static final Map<String, Map<String, String>> PRINCIPLE_ALLOWED = Map.of();

    static Stream<PrincipleRules.Rule> principleRules() {
        return PrincipleRules.all(hook, Path.of("src", "main", "java"), PRINCIPLE_ALLOWED).stream();
    }

    @ParameterizedTest
    @MethodSource("principleRules")
    void thePrincipleRuleHolds(PrincipleRules.Rule rule) {
        assertThat(rule.problems()).isEmpty();
    }

    @Test
    @DisplayName("HK-018 no hook class uses a network, process, reflection, foreign-memory, JDBC, web, data access or Lombok API")
    void noForbiddenApi() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "java.net..", "javax.net..", "java.sql..", "javax.sql..",
                        "java.lang.reflect..", "java.lang.foreign..", "lombok..",
                        // Spring Boot and the Spring Framework are the frame of the application; what the hook must not reach
                        // through them is the web, a database and a transaction
                        "org.springframework.web..", "org.springframework.http..", "org.springframework.jdbc..",
                        "org.springframework.data..", "org.springframework.transaction..", "org.springframework.boot.web..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Process")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Runtime")
                .check(hook);
    }

    @Test
    @DisplayName("HK-018 file system APIs (java.nio.file, java.nio.channels, java.io.File*) are used only by the classes of the keys package")
    void fileSystemOnlyInKeysPackage() {
        noClasses().that().resideOutsideOfPackage(KEYS_PACKAGE)
                .should().dependOnClassesThat().resideInAnyPackage("java.nio.file..", "java.nio.channels..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.File")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileInputStream")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileOutputStream")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileReader")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileWriter")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.RandomAccessFile")
                .check(hook);
        // the package exists in the imported classes: the rule above is not vacuous for it
        assertThat(hook.stream().anyMatch(type -> "org.pgledger.hook.keys".equals(type.getPackageName()))).isTrue();
    }

    @Test
    @DisplayName("HK-037 (changed by SFR-020: a link is followed) the key file is opened once and read from that channel: no read of a path (readAllBytes, readString, readAllLines, newInputStream, newBufferedReader)")
    void keyFileIsNeverReadByPath() {
        noClasses().should().callMethod(java.nio.file.Files.class, "readAllBytes", java.nio.file.Path.class)
                .orShould().callMethod(java.nio.file.Files.class, "readString", java.nio.file.Path.class)
                .orShould().callMethod(java.nio.file.Files.class, "readAllLines", java.nio.file.Path.class)
                .orShould().callMethod(java.nio.file.Files.class, "newInputStream", java.nio.file.Path.class,
                        java.nio.file.OpenOption[].class)
                .orShould().callMethod(java.nio.file.Files.class, "newBufferedReader", java.nio.file.Path.class)
                .check(hook);
    }

    @Test
    @DisplayName("HK-018 only Main and ProcessChannels touch java.lang.System (exit, stdin, stdout, stderr); the environment and the properties are read through the Spring Environment")
    void systemOnlyInMainAndProcessChannels() {
        noClasses().that().doNotHaveFullyQualifiedName("org.pgledger.hook.Main")
                .and().doNotHaveFullyQualifiedName("org.pgledger.hook.ProcessChannels")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.System")
                .check(hook);
        noClasses().should().callMethod(System.class, "getenv")
                .orShould().callMethod(System.class, "getenv", String.class)
                .orShould().callMethod(System.class, "getProperty", String.class)
                .orShould().callMethod(System.class, "getProperty", String.class, String.class)
                .orShould().callMethod(System.class, "getProperties")
                .check(hook);
    }

    @Test
    @DisplayName("HK-051 the settings come through the record KeyStoreProperties only: no @Value and no lookup in the Spring Environment in any hook class")
    void settingsOnlyThroughConfigurationProperties() {
        noClasses().should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.beans.factory.annotation.Value")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.core.env.Environment")
                .check(hook);
    }

    @Test
    @DisplayName("HK-052 the hook logs with SLF4J only: no java.util.logging, no printStackTrace, no System.out or System.err access outside ProcessChannels (the host streams)")
    void loggingOnlyWithSlf4j() {
        noClasses().should().dependOnClassesThat().resideInAPackage("java.util.logging..")
                .orShould().callMethod(Throwable.class, "printStackTrace")
                .check(hook);
        noClasses().that().doNotHaveFullyQualifiedName("org.pgledger.hook.ProcessChannels")
                .should().accessField(System.class, "out")
                .orShould().accessField(System.class, "err")
                .check(hook);
    }

    @Test
    @DisplayName("TXF-001 (principle 4.4) no TransactionTemplate, no TransactionAspectSupport and no hand-made savepoint in any module")
    void txf001_noHandMadeTransactions() {
        ArchitectureRules.noTransactionTemplate().check(hook);
        ArchitectureRules.noHandMadeTransactionControl().check(hook);
    }

    @Test
    @DisplayName("ARC-033 no operating system detection in main and test code")
    void noOperatingSystemDetection() {
        ArchitectureRules.noOperatingSystemDetection().check(hook);
        ArchitectureRules.fileSystemCapabilityIsProbedInFilesOnly().check(hook);
        assertThat(SourceRules.operatingSystemDetection(Path.of("src", "main", "java"), Path.of("src", "test", "java")))
                .isEmpty();
    }

    @Test
    @DisplayName("ARC-036 catch rules: no empty catch, no broad catch")
    void arc036_catchRules() {
        assertThat(CodingScan.catchProblems(Set.of(), null, Path.of("src", "main", "java"),
                Path.of("src", "test", "java"))).isEmpty();
    }

    @Test
    @DisplayName("ARC-037 string rules: literal first in equals, no == on strings, no string switch, no intern(), no new String( in main")
    void arc037_stringRules() {
        noClasses().should().callMethod(String.class, "intern")
                .orShould().callConstructor(String.class, String.class)
                .orShould().callConstructor(String.class, byte[].class)
                .orShould().callConstructor(String.class, byte[].class, java.nio.charset.Charset.class)
                .check(hook);
        // Run.java turns captured process bytes into text for an assertion: no file or stream API applies to a byte[]
        assertThat(CodingScan.stringProblems(Set.of("Run.java"), Path.of("src", "main", "java"),
                Path.of("src", "test", "java"))).isEmpty();
    }

    @Test
    @DisplayName("HK-018 no mutable static state")
    void noMutableStatics() {
        fields().that().areStatic().should().beFinal().check(hook);
    }

    @Test
    @DisplayName("ARC-1X-01 no class or package of the module is named after 1.x")
    void arc1x01_noClassNamedAfterOneX() {
        NoOneXRules.noClassNamedAfterOneX().check(hook);
    }

    @Test
    @DisplayName("ARC-1X-02 no class of the module depends on the migration tool")
    void arc1x02_noDependencyOnTheMigrationTool() {
        NoOneXRules.noDependencyOnTheMigrationTool().check(hook);
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
