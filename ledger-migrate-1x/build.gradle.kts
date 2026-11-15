// The tool that moves a 1.x installation to 2.0 (docs/testing/TEST_CASES.md section 43, M1X): stop-1x, import-secrets-1x, import-1x and
// retire-1x. A Spring Boot application with its own main on the same JVM, shipped as its own jar next to the other jars. It reads
// the 1.x ledger and writes through the repositories of the 2.0 modules below; none of the 2.0 modules depends on it, and none
// of the jars of the 2.0 services holds a class of it (M1X-002, M1X-006).

plugins {
    // Spring Boot Gradle plugin: bootJar builds the one executable jar (java -jar ledger-migrate-1x.jar); the version is the catalog Boot version.
    id("org.springframework.boot") version libs.versions.springBoot.get()
}

dependencies {
    // Spring Boot core, logging, MessageSource. The database beans exist only for a command that uses the database and only when a
    // JDBC driver is registered (the same arrangement as ledger-cli): the product ships no driver, the operator registers one.
    implementation("org.springframework.boot:spring-boot-starter")
    // The ledger repositories and services of 2.0 (with the database module: Spring Data JDBC, Hikari, no JDBC driver; and the
    // domain). The import writes through them.
    implementation(project(":ledger-application"))
    // Bean Validation for the @Validated settings record of the 1.x source: a bad value stops the startup (version from the Boot BOM)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // The command line machinery (runner, refusals and their report, texts, output) and the secret store of ledger-cli: the tool runs on
    // them and copies none of them, so its secret store is the one the 2.0 services read and cannot drift. Only the classes of that
    // module (its plain jar) are taken, none of its libraries (H2, ACME, the MCP client): the tool needs none of them.
    implementation(project(":ledger-cli")) {
        isTransitive = false
    }

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // ArchUnit 1.5.1: see gradle/libs.versions.toml
    testImplementation(libs.archunit)
    // The shared architecture and source rules (ARC-033: no operating system detection) live in the attestation test fixtures.
    testImplementation(testFixtures(project(":ledger-attestation")))
    // OwnerOnlyFiles (owner-only file rights) needs a domain class, so it stays in the domain test fixtures.
    testImplementation(testFixtures(project(":ledger-domain")))
    // The test database support (the PostgreSQL of the test PC: settings, pools, schema per test, PostgreSQL driver).
    testImplementation(testFixtures(project(":ledger-database")))
    // The context of the service tests of the application module (LedgerAppContext, Scenario, the key ring): the import is tested
    // in a context of the real services. The configuration is offered by ledger-application for this purpose.
    testImplementation(project(path = ":ledger-application", configuration = "testSupport"))
    // JSON test data is read with Jackson 3 (Boot BOM)
    testImplementation("tools.jackson.core:jackson-databind")
}

tasks.bootJar {
    archiveFileName = "ledger-migrate-1x.jar"
}

// M1X-006: the source scans of this module read the build scripts and the sources of the other modules as text. The root of the
// repository is passed in, never guessed, and the files are inputs, so a change of them runs the tests again.
tasks.test {
    systemProperty("ledger.docs.root", rootProject.layout.projectDirectory.asFile.absolutePath)
    inputs.files(rootProject.fileTree(rootProject.layout.projectDirectory) {
        include("settings.gradle.kts", "ledger-*/build.gradle.kts", "ledger-*/src/main/java/**/*.java")
    }).withPropertyName("repositorySources")
}
