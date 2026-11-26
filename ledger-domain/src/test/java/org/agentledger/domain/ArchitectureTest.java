// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.agentledger.testing.NoOneXRules;
import org.agentledger.testing.PrincipleRules;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.agentledger.testing.ArchitectureRules;
import org.agentledger.testing.SourceRules;

/** ARC rules that apply to the domain module, DOM-035, and negative controls proving the rules bite. */
class ArchitectureTest {

    private static final JavaClasses MAIN = ArchitectureRules.productionClasses("org.agentledger.domain");
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");

    /** ARC-PRN-01 .. 06 (TEST_CASES.md section 25): what this module allows of each rule (key to reason). The domain allows nothing. */
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
    void arc001_002_003_004_noLombokNoSetAccessibleNoReflectionNoProcesses() {
        ArchitectureRules.noLombok().check(MAIN);
        ArchitectureRules.noSetAccessible().check(MAIN);
        ArchitectureRules.reflectionOnlyIn().check(MAIN);
        ArchitectureRules.noProcessExecution().check(MAIN);
        assertThat(SourceRules.forbiddenBuildSwitches(List.of(Path.of("build.gradle.kts")))).isEmpty();
    }

    @Test
    void arc006_007_noStreamsNoEnvironmentNoValue() {
        ArchitectureRules.noStandardStreamsAndOtherLoggers().check(MAIN);
        ArchitectureRules.noEnvironmentAccessAndValueAnnotation().check(MAIN);
    }

    @Test
    @DisplayName("TXF-001 (principle 4.4) no TransactionTemplate, no TransactionAspectSupport and no hand-made savepoint in any module")
    void txf001_noHandMadeTransactions() {
        ArchitectureRules.noTransactionTemplate().check(MAIN);
        ArchitectureRules.noHandMadeTransactionControl().check(MAIN);
    }

    @Test
    void arc008_noSwallowedExceptions() {
        assertThat(SourceRules.swallowedOrBroadCatches(MAIN_JAVA, Set.of())).isEmpty();
    }

    @Test
    void arc009_domainIsIndependent() {
        ArchitectureRules.domainIsIndependent().check(MAIN);
    }

    @Test
    void arc010_018_019_024_noTreeModelsNoThreadLocalNoAmbientTime() {
        ArchitectureRules.noJsonTreeModels().check(MAIN);
        ArchitectureRules.noThreadLocalAndNoMutableStatics().check(MAIN);
        ArchitectureRules.staticFieldsAreFinal().check(MAIN);
        ArchitectureRules.noAmbientTime().check(MAIN);
    }

    @Test
    void arc022_dom035_aggregatesAreRecordsWithoutBooleansOrLocalTime() {
        ArchitectureRules.modelTypesAreRecordsEnumsOrInterfaces().check(MAIN);
        ArchitectureRules.aggregatesHaveNoBooleanNorLocalTime().check(MAIN);
    }

    @Test
    void arc025_032_noUtilityNamesNoVendorNames() {
        assertThat(SourceRules.utilityNames(MAIN_JAVA)).isEmpty();
        assertThat(SourceRules.vendorNames(MAIN_JAVA)).isEmpty();
    }

    @Test
    void arc033_noOperatingSystemDetectionInMainAndTest() {
        ArchitectureRules.noOperatingSystemDetection().check(MAIN);
        assertThat(SourceRules.operatingSystemDetection(MAIN_JAVA, Path.of("src", "test", "java"))).isEmpty();
    }

    @Test
    void negativeControl_theOperatingSystemRuleDetectsTheOsName(@TempDir Path dir) throws IOException {
        // the token is joined at run time so that this source file does not trip the rule it tests
        String key = String.join(".", "os", "name");
        Files.writeString(dir.resolve("Bad.java"), "class Bad { String s = System.getProperty(\"" + key + "\"); }");

        assertThat(SourceRules.operatingSystemDetection(dir)).hasSize(1);
    }

    @Test
    void negativeControl_theModelRuleSkipsGeneratedClassesButRefusesAHandWrittenPlainClass() {
        JavaClasses fixture = new ClassFileImporter().importPackages("org.agentledger.archfixture");
        assertThat(fixture.contain("org.agentledger.archfixture.SwitchOverEnum$1")).as("javac made the switch map").isTrue();

        String report = String.join("\n", ArchitectureRules.modelTypesAreRecordsEnumsOrInterfaces("org.agentledger.archfixture")
                .evaluate(fixture).getFailureReport().getDetails());
        assertThat(report).as("the hand-written plain class is refused").contains("org.agentledger.archfixture.BadCode");
        assertThat(report).as("the generated switch map and the records are not").doesNotContain("SwitchOverEnum");
    }

    @Test
    void negativeControl_theRulesDetectBadCode() {
        JavaClasses bad = new ClassFileImporter().importPackages("org.agentledger.archfixture");
        assertThat(bad.contain("org.agentledger.archfixture.BadCode")).isTrue();
        List<ArchRule> rules = List.of(ArchitectureRules.noSetAccessible(), ArchitectureRules.reflectionOnlyIn(),
                ArchitectureRules.noProcessExecution(), ArchitectureRules.noStandardStreamsAndOtherLoggers(),
                ArchitectureRules.noEnvironmentAccessAndValueAnnotation(), ArchitectureRules.noAmbientTime(),
                ArchitectureRules.noThreadLocalAndNoMutableStatics(), ArchitectureRules.staticFieldsAreFinal());
        for (ArchRule rule : rules) {
            assertThat(rule.evaluate(bad).hasViolation()).as(rule.getDescription()).isTrue();
        }
        // the time rule flags ambient now(), the default zones, Date and currentTimeMillis, but not now(clock)
        String report = String.join("\n", ArchitectureRules.noAmbientTime().evaluate(bad).getFailureReport()
                .getDetails());
        assertThat(report).contains("ambientNow", "ambientLocal", "ambientOffset", "defaultZone", "defaultTimeZone",
                "oldDate", "millis").doesNotContain("goodNow");
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
