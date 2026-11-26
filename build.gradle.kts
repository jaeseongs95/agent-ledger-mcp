import com.github.jk1.license.filter.DependencyFilter
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryMarkdownReportRenderer
import com.github.jk1.license.render.JsonReportRenderer
import com.github.jk1.license.render.ReportRenderer
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.process.CommandLineArgumentProvider

plugins {
    base
    alias(libs.plugins.spotless)
    alias(libs.plugins.license.report)
}

group = "org.veridger"
version = "4.1.0"

spotless {
    java {
        target("*/src/**/*.java")
        licenseHeader("// SPDX-License-Identifier: AGPL-3.0-or-later\n\n", "package ")
    }
}

licenseReport {
    outputDir = layout.buildDirectory.dir("reports/dependency-license").get().asFile.absolutePath
    projects = arrayOf(project) + subprojects.toTypedArray()
    configurations = arrayOf("runtimeClasspath")
    renderers = arrayOf<ReportRenderer>(JsonReportRenderer("index.json", false), InventoryMarkdownReportRenderer())
    filters = arrayOf<DependencyFilter>(LicenseBundleNormalizer())
    allowedLicensesFile = layout.projectDirectory.file("docs/allowed-licenses.json").asFile
}

tasks.matching { it.name in setOf("generateLicenseReport", "checkLicense", "checkLicensePreparation") }.configureEach {
    notCompatibleWithConfigurationCache("dependency-license-report 3.1.5 tasks retain Project references")
}

abstract class VerifyThirdPartyNotices : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val noticesFile: RegularFileProperty

    @get:Input
    abstract val runtimeGroups: SetProperty<String>

    @TaskAction
    fun verify() {
        val content = noticesFile.get().asFile.readText(Charsets.UTF_8)
        require(content.contains("AGPL-3.0-or-later")) { "THIRD_PARTY_NOTICES.md must state the project license AGPL-3.0-or-later" }
        val missing = runtimeGroups.get().sorted().filter { group ->
            group != "org.veridger" && !content.contains(group)
        }
        if (missing.isNotEmpty()) {
            throw GradleException("THIRD_PARTY_NOTICES.md is missing runtime dependency groups: $missing")
        }
    }
}

// Reads an optional Gradle project property (-P or gradle.properties); a malformed value fails the configuration with a clear message.
fun ledgerTestProperty(name: String): String? = providers.gradleProperty(name).orNull

fun positiveInt(name: String, value: String): Int {
    val number = value.toIntOrNull()
    require(number != null && number >= 1) { "$name must be an integer of at least 1, but is '$value'" }
    return number
}

// Portable defaults of the local test PostgreSQL instance. Password file paths are never hardcoded in the repository:
// when omitted, the PostgreSQL JDBC driver reads the user's pgpass file, or callers pass -Pledger.test.jdbc-password-file /
// -Pledger.test.app-password-file on the command line or in ~/.gradle/gradle.properties.
val ledgerTestDatabase = linkedMapOf(
    "ledger.test.jdbc-url" to "jdbc:postgresql://127.0.0.1:55432/ledger_test",
    "ledger.test.jdbc-user" to "ledger_test",
    "ledger.test.app-user" to "ledger_test_app",
)
val ledgerTestPasswordFiles = listOf(
    "ledger.test.jdbc-password-file",
    "ledger.test.app-password-file",
)

// The modules whose tests use the test database. Their test tasks are never up to date and never taken from, or stored in, the
// build cache: the state of the database is an input that Gradle cannot see (docs/testing/TEST_CASES.md section 30, BLD-DB-01).
val databaseModules = setOf("ledger-database", "ledger-application", "ledger-mcp-server", "ledger-admin-web", "ledger-cli",
    "ledger-migrate-1x", "ledger-system-test")

// The one test database is a shared external resource: each test JVM opens up to 10 connections to its role, which has a
// connection limit. With parallel execution on, the test tasks of the database modules must not run at the same time (docs/testing/
// TEST_CASES.md section 30, BLD-DB-03). Every such task uses this service, and the service allows one user at a time; no other task
// is held back by it, and it is a correctness constraint of the resource, not a limit of the machine (workers stay a command argument).
abstract class TestDatabaseService : BuildService<BuildServiceParameters.None>

val testDatabaseService = gradle.sharedServices.registerIfAbsent("ledgerTestDatabase", TestDatabaseService::class.java) {
    maxParallelUsages = 1
}

// ARC-005 / DRV-014: no JDBC driver, JPA, migration tool, Lombok or CLI framework on any runtime classpath.
// "org.hibernate.orm" (not "org.hibernate"): the Jakarta Validation implementation is org.hibernate.validator, which is allowed; the ORM stays forbidden.
val forbiddenRuntime = listOf(
    "org.postgresql", "com.h2database", "com.mysql", "org.mariadb.jdbc", "com.oracle.database.jdbc",
    "com.microsoft.sqlserver", "org.liquibase", "org.flywaydb", "org.hibernate.orm", "jakarta.persistence",
    "org.projectlombok", "info.picocli", "org.springframework.shell", "org.zeroturnaround",
)
// The one exception (DRV-014, SETUP): the H2 driver, on ledger-seed and on ledger-cli (which uses it) only. H2 gives the format of
// the seed bundle, the initial data of a new installation that `ledger-cli setup` moves into the ledger database, and nothing
// else: the ledger database is always a DBMS the operator installs (user decision 「dbms 설치가 전제라니까?」, 「h2는 그런목적으로만
// 쓰라고.」), the product refuses to register H2 as it, and the MCP server and the admin web keep zero JDBC drivers. The system test
// (DRV-013) looks for the driver in the built jars of the three applications.
val embeddedDriverGroup = "com.h2database"
val embeddedDriverModules = setOf("ledger-seed", "ledger-cli")

// Fails when a forbidden artifact is on the runtime classpath of the module. The resolved artifacts of the runtime classpath are
// an input (a provider that Gradle evaluates when it needs the value), so the task reads neither the project nor a configuration
// while it runs and works with the configuration cache.
abstract class VerifyRuntimeClasspath : DefaultTask() {
    @get:Input
    abstract val moduleName: Property<String>

    /** group:name:version of every external artifact on the runtime classpath. */
    @get:Input
    abstract val runtimeArtifacts: SetProperty<String>

    @get:Input
    abstract val forbiddenGroups: SetProperty<String>

    /** Groups that are allowed on this module although they are forbidden on the others (the H2 driver of the seed bundle). */
    @get:Input
    abstract val allowedGroups: SetProperty<String>

    @TaskAction
    fun verify() {
        val problems = runtimeArtifacts.get().sorted().filter { coordinates ->
            val group = coordinates.substringBefore(':')
            group !in allowedGroups.get() && forbiddenGroups.get().any { group == it || group.startsWith("$it.") }
        }.map { "${moduleName.get()}: $it" }
        if (problems.isNotEmpty()) {
            throw GradleException("forbidden runtime dependencies: $problems")
        }
    }
}

// A system property that holds the absolute path of one jar of a file collection. The path is found when the test JVM is
// started, not while the build is configured and not by a task action that holds a configuration (the configuration cache
// refuses that); the jars themselves are the input (by content, not by location).
abstract class JarPathProperty : CommandLineArgumentProvider {
    @get:Classpath
    abstract val jars: ConfigurableFileCollection

    @get:Input
    abstract val propertyName: Property<String>

    @get:Input
    abstract val filePrefix: Property<String>

    override fun asArguments(): Iterable<String> =
        listOf("-D${propertyName.get()}=" + jars.files.single { it.name.startsWith(filePrefix.get()) }.absolutePath)
}

subprojects {
    apply(plugin = "java-library")

    group = "org.veridger"
    version = "4.1.0"

    val projectName = project.name
    val usesDatabase = projectName in databaseModules

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    dependencies {
        // The Boot BOM pins every managed version; the Spring AI BOM is declared for the later MCP modules.
        "implementation"(platform(rootProject.libs.spring.boot.bom))
        "implementation"(platform(rootProject.libs.spring.ai.bom))
        // Jackson 3.2.1, which the WebAuthn4J of Spring Security 7.1.2 needs, above the 3.1.7 of the Boot BOM (see libs.versions.toml).
        "implementation"(platform(rootProject.libs.jackson.bom))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // The PostgreSQL JDBC driver jar, for the tests that register a driver jar by its path (the test selects the one jar).
    // Test-only: no runtime classpath of any module has a driver (verifyRuntimeClasspath). Only the modules with a database test.
    val postgresDriverJar = configurations.create("postgresDriverJar") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    if (usesDatabase) {
        dependencies {
            "postgresDriverJar"(platform(rootProject.libs.spring.boot.bom))
            "postgresDriverJar"("org.postgresql:postgresql")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 25
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Werror", "-Xlint:all", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // All time is UTC: the JVM default zone is pinned for the tests as well.
        jvmArgs("-Duser.timezone=UTC", "-Dfile.encoding=UTF-8")
        // Optional limits of the test JVMs for machines with few resources. Without a property nothing is limited
        // (Gradle defaults). Pass them on the command line, e.g.
        // -Pledger.test.forks=1 -Pledger.test.maxHeapSize=1g -Pledger.test.activeProcessorCount=1
        ledgerTestProperty("ledger.test.forks")?.let { maxParallelForks = positiveInt("ledger.test.forks", it) }
        ledgerTestProperty("ledger.test.maxHeapSize")?.let {
            require(Regex("[1-9][0-9]*[kKmMgG]?").matches(it)) {
                "ledger.test.maxHeapSize must be a size such as 512m or 1g, but is '$it'"
            }
            maxHeapSize = it
        }
        ledgerTestProperty("ledger.test.activeProcessorCount")?.let {
            jvmArgs("-XX:ActiveProcessorCount=${positiveInt("ledger.test.activeProcessorCount", it)}")
        }
        // The architecture and source rule tests read the sources and the build script of their module by a path relative to
        // the module (src/main/java, src/test/java, build.gradle.kts): they are inputs, so a change that no class file shows (a
        // comment, a build switch) runs the tests again and a cached result is never reused for other sources.
        inputs.files(project.fileTree(project.layout.projectDirectory) {
            include("src/main/java/**", "src/test/java/**", "build.gradle.kts")
        }).withPropertyName("sourceScans").withPathSensitivity(PathSensitivity.RELATIVE)
        testLogging {
            events("passed", "failed", "skipped")
            showStandardStreams = false
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        if (usesDatabase) {
            // The test database is the PostgreSQL of the test PC (docs/testing/TEST_CASES.md 1.4a, 1.4b). Each setting is a Gradle
            // property with a default and reaches the test JVM as a system property. The two password file properties carry
            // the PATH of a one-line file only: the test support reads the file, and the value is never a property, a log
            // line or a message. A test that cannot reach the database fails; it is not skipped.
            ledgerTestDatabase.forEach { (name, fallback) ->
                systemProperty(name, ledgerTestProperty(name) ?: fallback)
            }
            ledgerTestPasswordFiles.forEach { name ->
                ledgerTestProperty(name)?.let { systemProperty(name, it) }
            }
            // The path of the PostgreSQL JDBC driver jar (test-only configuration), for the tests that register a driver. The version
            // comes from the Boot BOM (a platform needs the transitive resolution); the driver jar is the file named
            // postgresql-<version>.jar among the resolved files.
            jvmArgumentProviders.add(objects.newInstance<JarPathProperty>().apply {
                jars.from(postgresDriverJar)
                propertyName = "ledger.test.postgres-jar"
                filePrefix = "postgresql-"
            })
            // A test of a database is never up to date and never taken from or stored in the build cache: the state of the
            // database is an input that Gradle cannot see. Both are stated; the build cache does not look at upToDateWhen.
            outputs.upToDateWhen { false }
            outputs.doNotCacheIf("the state of the test database is an input that Gradle cannot see") { true }
            // one database test task at a time (the connection limit of the test database role)
            usesService(testDatabaseService)
        }
    }

    // One check per module, wired to the check of the module: `./gradlew check` and `./gradlew verifyRuntimeClasspath` run it for every module.
    val verifyRuntimeClasspath = tasks.register<VerifyRuntimeClasspath>("verifyRuntimeClasspath") {
        group = "verification"
        description = "Fails when a forbidden artifact is on the runtime classpath of the module."
        moduleName = projectName
        forbiddenGroups.addAll(forbiddenRuntime)
        if (projectName in embeddedDriverModules) {
            allowedGroups.add(embeddedDriverGroup)
        }
        runtimeArtifacts.addAll(configurations.getByName("runtimeClasspath").incoming
            .artifactView { componentFilter { it is ModuleComponentIdentifier } }
            .artifacts.resolvedArtifacts
            .map { artifacts ->
                artifacts.map { artifact ->
                    (artifact.id.componentIdentifier as ModuleComponentIdentifier).let { "${it.group}:${it.module}:${it.version}" }
                }
            })
    }
    val checkThirdPartyNotices = tasks.register<VerifyThirdPartyNotices>("checkThirdPartyNotices") {
        group = "verification"
        description = "Verifies that THIRD_PARTY_NOTICES.md covers every external runtime dependency group of the module."
        noticesFile = rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")
        runtimeGroups.addAll(configurations.getByName("runtimeClasspath").incoming
            .artifactView { componentFilter { it is ModuleComponentIdentifier } }
            .artifacts.resolvedArtifacts
            .map { artifacts ->
                artifacts.map { (it.id.componentIdentifier as ModuleComponentIdentifier).group }
            })
    }
    tasks.named("check") { dependsOn(verifyRuntimeClasspath, checkThirdPartyNotices) }
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}
