// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.archchecks;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.cli.database.LedgerRefusals;
import org.pgledger.domain.error.LedgerException;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cross-module architecture, exception-seal, component, and boundary rules consolidated in {@code ledger-arch-checks}.
 */
class BoundariesAndExceptionsTest {

    private static final JavaClasses ALL_MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.pgledger");

    @Test
    @DisplayName("ARC-BND-01: LedgerException hierarchy is closed and sealed")
    void ledgerExceptionIsSealedAndClosed() {
        assertThat(LedgerException.class.isSealed()).isTrue();
        Class<?>[] permitted = LedgerException.class.getPermittedSubclasses();
        assertThat(permitted).isNotEmpty();
        assertThat(Arrays.stream(permitted)).allMatch(sub -> sub.getPackageName().equals(LedgerException.class.getPackageName()));
    }

    @Test
    @DisplayName("ARC-BND-02 (F10): deleted ServiceAccountAccess wrapper is absent and ServicePrincipalAcl lives in ledger-files")
    void noServiceAccountAccessWrapperAnywhere() {
        noClasses().should().dependOnClassesThat()
                .haveFullyQualifiedName("org.pgledger.application.attachment.ServiceAccountAccess")
                .check(ALL_MAIN);
    }

    @Test
    @DisplayName("ARC-BND-03 (D7): LedgerRefusals is a Spring @Component bean without static translate helpers")
    void ledgerRefusalsIsComponentWithoutStaticTranslate() {
        assertThat(LedgerRefusals.class.isAnnotationPresent(Component.class)).isTrue();
        for (Method m : LedgerRefusals.class.getDeclaredMethods()) {
            if ("translate".equals(m.getName())) {
                assertThat(Modifier.isStatic(m.getModifiers())).as("translate must be an instance method").isFalse();
            }
        }
        classes().that().haveFullyQualifiedName(LedgerRefusals.class.getName())
                .should().beAnnotatedWith(Component.class)
                .check(ALL_MAIN);
    }

    @Test
    @DisplayName("ARC-BND-04: @Transactional is confined to @Service classes outside ledger-migrate-1x")
    void transactionalOnlyOnServices() {
        noClasses().that().areNotAnnotatedWith(Service.class)
                .and().resideOutsideOfPackage("org.pgledger.migrate1x..")
                .should().beAnnotatedWith(Transactional.class)
                .check(ALL_MAIN);
        noMethods().that().areDeclaredInClassesThat().areNotAnnotatedWith(Service.class)
                .and().areDeclaredInClassesThat().resideOutsideOfPackage("org.pgledger.migrate1x..")
                .should().beAnnotatedWith(Transactional.class)
                .check(ALL_MAIN);
    }

    @Test
    @DisplayName("ARC-BND-05: pure JDK and domain modules stay free of illegal Spring or downstream layer dependencies")
    void pureModulesDependOnNoDownstreamOrSpring() {
        noClasses().that().resideInAnyPackage("org.pgledger.attestation..", "org.pgledger.files..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "org.pgledger.domain..", "org.pgledger.database..",
                        "org.pgledger.application..", "org.pgledger.mcp..", "org.pgledger.admin..", "org.pgledger.cli..")
                .check(ALL_MAIN);
        noClasses().that().resideInAPackage("org.pgledger.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.jdbc..", "org.springframework.transaction..", "org.springframework.web..",
                        "org.pgledger.database..", "org.pgledger.application..",
                        "org.pgledger.mcp..", "org.pgledger.admin..", "org.pgledger.cli..")
                .check(ALL_MAIN);
    }

    @Test
    @DisplayName("ARC-BND-06 (F9): file protection and attachment store never call symlink-following Files.setOwner or setPosixFilePermissions")
    void noSymlinkFollowingOwnerOrPosixMutators() {
        noClasses().that().resideInAnyPackage("org.pgledger.files..", "org.pgledger.application.attachment..")
                .should().callMethod(Files.class, "setOwner", Path.class, UserPrincipal.class)
                .orShould().callMethod(Files.class, "setPosixFilePermissions", Path.class, Set.class)
                .check(ALL_MAIN);
    }
}
