// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.testing;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import java.lang.reflect.AccessibleObject;
import java.time.Clock;
import java.util.Date;
import java.util.Set;
import java.util.TimeZone;

/**
 * Architecture rules shared by the modules. Each method is one rule of the test catalogue (ARC-*); a module
 * test imports its own production classes and checks the rules that apply to it.
 */
public final class ArchitectureRules {

    private ArchitectureRules() {
    }

    /** The production classes (no test classes) of a package tree. */
    public static JavaClasses productionClasses(String rootPackage) {
        return new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(rootPackage);
    }

    /** ARC-001. */
    public static ArchRule noLombok() {
        return noClasses().should().dependOnClassesThat().resideInAnyPackage("lombok..");
    }

    /** ARC-002 (bytecode part): no setAccessible. */
    public static ArchRule noSetAccessible() {
        return noClasses().should().callMethodWhere(DescribedPredicate.describe("calls setAccessible or trySetAccessible",
                call -> ("setAccessible".equals(call.getTarget().getName())
                        || "trySetAccessible".equals(call.getTarget().getName()))
                        && call.getTargetOwner().isAssignableTo(AccessibleObject.class)));
    }

    private static final Set<String> REFLECTIVE_CLASS_METHODS = Set.of("forName", "newInstance",
            "getDeclaredConstructor", "getConstructor", "getMethod", "getDeclaredMethod", "getDeclaredField",
            "getField", "getDeclaredMethods", "getMethods", "getDeclaredFields", "getDeclaredConstructors");

    /** ARC-003: {@code java.lang.reflect} and reflective {@code Class} calls only in the listed packages. */
    public static ArchRule reflectionOnlyIn(String... allowedPackages) {
        DescribedPredicate<JavaMethodCall> reflectiveCall = DescribedPredicate.describe(
                "calls a reflective method of java.lang.Class",
                call -> call.getTargetOwner().isEquivalentTo(Class.class)
                        && REFLECTIVE_CLASS_METHODS.contains(call.getTarget().getName()));
        return noClasses().that().resideOutsideOfPackages(allowedPackages)
                .should().dependOnClassesThat().resideInAPackage("java.lang.reflect..")
                .orShould().callMethodWhere(reflectiveCall);
    }

    /** ARC-004. */
    public static ArchRule noProcessExecution() {
        return noClasses().should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .orShould().callMethod(Runtime.class, "exec", String.class)
                .orShould().callMethod(Runtime.class, "exec", String[].class);
    }

    /** ARC-006. */
    public static ArchRule noStandardStreamsAndOtherLoggers() {
        return noClasses().should().accessField(System.class, "out")
                .orShould().accessField(System.class, "err")
                .orShould().callMethod(Throwable.class, "printStackTrace")
                .orShould().dependOnClassesThat().resideInAnyPackage("java.util.logging..", "org.apache.log4j..",
                        "org.apache.logging..", "org.apache.commons.logging..");
    }

    /** ARC-007 (bytecode part): no System.getenv and no @Value. */
    public static ArchRule noEnvironmentAccessAndValueAnnotation() {
        return noClasses().should().callMethod(System.class, "getenv")
                .orShould().callMethod(System.class, "getenv", String.class)
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.beans.factory.annotation.Value");
    }

    /** ARC-010. */
    public static ArchRule noJsonTreeModels() {
        return noClasses().should().dependOnClassesThat().haveFullyQualifiedName("tools.jackson.databind.JsonNode")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("tools.jackson.databind.node.ObjectNode")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.fasterxml.jackson.databind.JsonNode");
    }

    /** ARC-033 (bytecode part): no decision by the path separator and no operating system switch of a test. */
    public static ArchRule noOperatingSystemDetection() {
        return noClasses().should().accessField(java.io.File.class, "separator")
                .orShould().accessField(java.io.File.class, "separatorChar")
                .orShould().accessField(java.io.File.class, "pathSeparator")
                .orShould().accessField(java.io.File.class, "pathSeparatorChar")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.junit.jupiter.api.condition.OS")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.junit.jupiter.api.condition.EnabledOnOs")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.junit.jupiter.api.condition.DisabledOnOs");
    }

    /** ARC-018. */
    public static ArchRule noThreadLocalAndNoMutableStatics() {
        return noClasses().should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ThreadLocal");
    }

    /** ARC-018, second half. */
    public static ArchRule staticFieldsAreFinal() {
        return fields().that().areStatic().should().beFinal();
    }

    private static final String JSON_MAPPER = "tools.jackson.databind.json.JsonMapper";

    /**
     * TXD-007 (principle 3.7 and 2.2 category 2): a Spring module makes no {@code JsonMapper} of its own, no
     * {@code JsonMapper.builder()} and no {@code new JsonMapper()}: the Spring Boot bean is injected, so that every JSON is
     * written and read with the same settings.
     */
    public static ArchRule noOwnJsonMapper() {
        DescribedPredicate<JavaMethodCall> builder = DescribedPredicate.describe("calls JsonMapper.builder(..)",
                call -> JSON_MAPPER.equals(call.getTargetOwner().getName()) && "builder".equals(call.getTarget().getName()));
        DescribedPredicate<JavaConstructorCall> constructor = DescribedPredicate.describe("calls a constructor of JsonMapper",
                call -> JSON_MAPPER.equals(call.getTargetOwner().getName()));
        return noClasses().should().callMethodWhere(builder).orShould().callConstructorWhere(constructor);
    }

    private static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String SERVICE = "org.springframework.stereotype.Service";

    /**
     * ARC-013 / TXD-005 (principle 4.4): nothing opens a transaction by hand. A transaction is declared with
     * {@code @Transactional} on a service method; {@code TransactionTemplate} (made with {@code new}, or injected) is not used.
     */
    public static ArchRule noTransactionTemplate() {
        return noClasses().should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.transaction.support.TransactionTemplate");
    }

    private static final Set<String> SAVEPOINT_OWNERS = Set.of("org.springframework.transaction.TransactionStatus",
            "org.springframework.transaction.SavepointManager");
    private static final Set<String> SAVEPOINT_METHODS = Set.of("createSavepoint", "rollbackToSavepoint",
            "releaseSavepoint");

    /**
     * TXF-001 (principle 4.4): no class manages a transaction or a savepoint by hand: {@code TransactionAspectSupport} (the
     * handle of the current transaction: {@code currentTransactionStatus()}, {@code setRollbackOnly()}) is not used, and neither
     * are the savepoint methods of {@code TransactionStatus} ({@code createSavepoint}, {@code rollbackToSavepoint},
     * {@code releaseSavepoint}). Nor is there a transaction by annotation that stands in for one made by hand: no
     * {@code Propagation.NESTED} (a savepoint), no {@code REQUIRES_NEW} and no {@code MANDATORY} anywhere in production code, which
     * {@code PrincipleRules.nonDefaultPropagation} (TXP-001) checks in every module. One use case is one service method and one
     * transaction (principle 4.4); a write that loses a race is not taken back alone and continued, it ends the call with the
     * translated error and the whole transaction is rolled back.
     */
    public static ArchRule noHandMadeTransactionControl() {
        // Spring is no dependency of this module (it knows no framework class), so the names are texts
        DescribedPredicate<JavaMethodCall> savepointCall = DescribedPredicate.describe(
                "call createSavepoint, rollbackToSavepoint or releaseSavepoint of TransactionStatus or SavepointManager",
                call -> SAVEPOINT_OWNERS.contains(call.getTargetOwner().getName())
                        && SAVEPOINT_METHODS.contains(call.getTarget().getName()));
        return noClasses().should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.transaction.interceptor.TransactionAspectSupport")
                .orShould().callMethodWhere(savepointCall);
    }

    private static final String CONFIGURATION = "org.springframework.context.annotation.Configuration";

    /**
     * TXD-010 (principles 3.9 and 4.4): no class makes its own database plumbing: no {@code JdbcClient.create(..)}, no
     * {@code new TransactionTemplate(..)} and no {@code new DataSourceTransactionManager(..)} in production code outside a
     * {@code @Configuration} class. The {@code JdbcClient} and the transaction manager are beans of the container (Spring Boot's,
     * or a {@code @Bean} method of a configuration for a second data source), and a transaction is {@code @Transactional}.
     *
     * @param allowedClassNames classes that still do, each to be named in the test with its reason (a finding if there are
     *                          more than the test says)
     */
    public static ArchRule noOwnJdbcPlumbing(String... allowedClassNames) {
        java.util.Set<String> allowed = java.util.Set.of(allowedClassNames);
        DescribedPredicate<JavaClass> notAConfiguration = DescribedPredicate.describe(
                "are no @Configuration class and not one of the named exceptions",
                type -> !type.isAnnotatedWith(CONFIGURATION) && !allowed.contains(type.getName()));
        DescribedPredicate<JavaMethodCall> createsJdbcClient = DescribedPredicate.describe("call JdbcClient.create(..)",
                call -> "org.springframework.jdbc.core.simple.JdbcClient".equals(call.getTargetOwner().getName())
                        && "create".equals(call.getTarget().getName()));
        DescribedPredicate<JavaConstructorCall> createsTransactionPlumbing = DescribedPredicate.describe(
                "call a constructor of TransactionTemplate or DataSourceTransactionManager",
                call -> "org.springframework.transaction.support.TransactionTemplate".equals(call.getTargetOwner().getName())
                        || "org.springframework.jdbc.datasource.DataSourceTransactionManager"
                                .equals(call.getTargetOwner().getName()));
        return noClasses().that(notAConfiguration).should().callMethodWhere(createsJdbcClient)
                .orShould().callConstructorWhere(createsTransactionPlumbing);
    }

    /**
     * ARC-013 / TXD-005: the transaction boundary is the service layer. A class that is no {@code @Service} (a repository, a
     * controller, a command, a tool, a configuration) is not annotated with {@code @Transactional}.
     */
    public static ArchRule transactionalOnlyOnServiceClasses() {
        return noClasses().that().areNotAnnotatedWith(SERVICE).should().beAnnotatedWith(TRANSACTIONAL);
    }

    /** ARC-013 / TXD-005: the same for a method: only a method of a {@code @Service} is {@code @Transactional}. */
    public static ArchRule transactionalOnlyOnServiceMethods() {
        return noMethods().that().areDeclaredInClassesThat().areNotAnnotatedWith(SERVICE).should()
                .beAnnotatedWith(TRANSACTIONAL);
    }

    /** ARC-019 / ARC-024: no time without the injected Clock, no default zone, no old date classes. */
    public static ArchRule noAmbientTime(String... allowedPackages) {
        DescribedPredicate<JavaMethodCall> nowWithoutClock = DescribedPredicate.describe(
                "calls a java.time now(..) overload that does not take a Clock",
                call -> "now".equals(call.getTarget().getName())
                        && "java.time".equals(call.getTargetOwner().getPackageName())
                        && call.getTarget().getRawParameterTypes().stream()
                                .noneMatch(p -> p.isAssignableTo(Clock.class)));
        return noClasses().that().resideOutsideOfPackages(allowedPackages)
                .should().callMethodWhere(nowWithoutClock)
                .orShould().callMethod(java.time.ZoneId.class, "systemDefault")
                .orShould().callMethod(Clock.class, "systemDefaultZone")
                .orShould().callMethod(TimeZone.class, "getDefault")
                .orShould().callMethod(TimeZone.class, "setDefault", TimeZone.class)
                .orShould().callMethod(java.util.Calendar.class, "getInstance")
                .orShould().callMethod(System.class, "currentTimeMillis")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(Date.class.getName());
    }

    /** ARC-009: the domain depends on nothing of web, Jackson or the database module. */
    public static ArchRule domainIsIndependent() {
        return noClasses().that().resideInAPackage("org.pgledger.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("tools.jackson..", "com.fasterxml..",
                        "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..",
                        "org.pgledger.database..", "org.springframework.jdbc..");
    }

    /** ARC-022 / DOM-035: aggregates are records, enums or interfaces. */
    public static ArchRule modelTypesAreRecordsEnumsOrInterfaces() {
        return modelTypesAreRecordsEnumsOrInterfaces("org.pgledger.domain.model");
    }

    /**
     * The same rule for another package (the negative control points it at a fixture package). Classes the compiler generates
     * (synthetic, such as the switch map {@code X$1} of a {@code switch} over an enum, or anonymous) are not hand-written
     * model types and are skipped; a hand-written plain class is still refused.
     */
    public static ArchRule modelTypesAreRecordsEnumsOrInterfaces(String packageName) {
        return com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes().that()
                .resideInAPackage(packageName)
                .should(new com.tngtech.archunit.lang.ArchCondition<JavaClass>("be a record, an enum or an interface") {
                    @Override
                    public void check(JavaClass item, com.tngtech.archunit.lang.ConditionEvents events) {
                        boolean generated = item.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.SYNTHETIC)
                                || item.isAnonymousClass();
                        boolean ok = generated || item.isRecord() || item.isEnum() || item.isInterface();
                        events.add(new com.tngtech.archunit.lang.SimpleConditionEvent(item, ok,
                                item.getName() + (ok ? " is fine" : " is not a record, enum or interface")));
                    }
                });
    }

    /** ARC-022 / DOM-035: no boolean or zone-bearing time component in an aggregate (batch operations excepted). */
    public static ArchRule aggregatesHaveNoBooleanNorLocalTime() {
        return noFields().that().areDeclaredInClassesThat().resideInAPackage("org.pgledger.domain.model")
                .and().areDeclaredInClassesThat().haveNameNotMatching(".*BatchOp.*")
                .should().haveRawType(boolean.class)
                .orShould().haveRawType(Boolean.class)
                .orShould().haveRawType(java.time.LocalDateTime.class)
                .orShould().haveRawType(java.time.ZonedDateTime.class)
                .orShould().haveRawType(java.time.OffsetDateTime.class)
                .orShould().haveRawType(java.time.ZoneId.class);
    }
}
