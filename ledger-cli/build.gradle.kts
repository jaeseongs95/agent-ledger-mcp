import java.io.StringReader
import java.util.Properties

plugins {
    // Spring Boot Gradle plugin: bootJar builds the one executable jar (java -jar ledger-cli.jar); the version is the catalog Boot version.
    id("org.springframework.boot") version libs.versions.springBoot.get()
}

dependencies {
    // Spring Boot core, logging, MessageSource. The database beans exist only when a JDBC driver is registered (see
    // org.veridger.cli.database), because secret-set and driver-register must run before any JDBC driver exists on this
    // machine; the application excludes DataSourceAutoConfiguration, the DataSource comes from the registered driver.
    implementation("org.springframework.boot:spring-boot-starter")
    // The ledger services (key registration ...) with the database module (Spring Data JDBC, Hikari; no JDBC driver) and the
    // domain: the same modules the admin web uses for its console.
    implementation(project(":ledger-application"))
    // Bean Validation for the @Validated settings records: a bad value stops the startup (version from the Boot BOM)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Reads and fingerprints the public key of a session key (JDK only, shared with the hook and the server).
    implementation(project(":ledger-attestation"))
    // Shared owner-only POSIX/ACL file protection (JDK only, shared with the server and the hook).
    implementation(project(":ledger-files"))
    // The MCP Java SDK client of mcp-check (the SDK the server runs on) and the Jackson 3 mapper it writes JSON with (Boot BOM).
    implementation(libs.mcp.sdk)
    implementation("tools.jackson.core:jackson-databind")
    // The seed bundle (the initial data of a new installation) and its reader; the H2 driver that reads it comes with this
    // module and is on the runtime class path of this tool only (the root build, DRV-014, allows it nowhere else).
    implementation(project(":ledger-seed"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // ArchUnit 1.5.1: see gradle/libs.versions.toml
    testImplementation(libs.archunit)
    // The shared architecture and source rules (ARC-033: no operating system detection) live in the attestation test fixtures.
    testImplementation(testFixtures(project(":ledger-attestation")))
    // OwnerOnlyFiles (owner-only file rights) needs a domain class, so it stays in the domain test fixtures.
    testImplementation(testFixtures(project(":ledger-domain")))
    // The test database support (the PostgreSQL of the test PC: settings, pools, schema per test, PostgreSQL driver).
    testImplementation(testFixtures(project(":ledger-database")))
}

// WindowsEffectiveRights (a test class, PCS-031) calls the Windows API with the foreign function API; javac reports its restricted
// methods as [restricted] warnings, and the build turns warnings into errors. The one category is switched off for the compilation of
// the tests of this module only: the main sources of every module keep the whole of -Xlint:all as errors (the one class of the
// product that calls the operating system, WindowsAccountNames of ledger-files, suppresses it for itself).
tasks.named<JavaCompile>("compileTestJava") {
    options.compilerArgs.add("-Xlint:-restricted")
}

// The plain jar (the classes of this module with the classifier plain, which the Boot plugin gives it) stays enabled: the migration
// tool (ledger-migrate-1x) is built on the command line machinery and the secret store of this module and takes its classes from
// it, so its secret store cannot differ from this one. It takes no library of this module with it (isTransitive = false there).
tasks.jar {
    enabled = true
}

tasks.bootJar {
    archiveFileName = "ledger-cli.jar"
    // On Windows this program asks the operating system for the name of a well-known account (the service account, SYSTEM, the
    // administrators) by its SID, with the foreign function API of the JDK (java.lang.foreign), in one class (WindowsAccountNames of
    // ledger-files; R9-D0095-A). Java 25 restricts it and warns on the standard error of a program that has not allowed it, so the jar
    // allows it itself with the main attribute of its manifest, which applies to the code of an executable jar started with
    // `java -jar` (the installers, the services and the README all start it so): no command line option, no change of an output.
    // Sources: JEP 454 (https://openjdk.org/jeps/454, "Restricted methods"), JEP 472 (https://openjdk.org/jeps/472, which names the
    // manifest attribute) and the JAR File Specification of Java SE 25 ("Main Attributes").
    manifest {
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}

// The seed bundle (SETUP): the build writes the H2 file ledger-seed.mv.db from the text files of src/seed with a Java program
// (SeedBuilder of ledger-seed, run on the JVM of the build) and packs it with the tool as a resource. The binary is generated,
// never committed; the text files are the source and the only place the defaults of an installation are written.
val seedBuilder = configurations.create("seedBuilder") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    seedBuilder(platform(libs.spring.boot.bom))
    seedBuilder(project(":ledger-seed"))
}
val seedSources = layout.projectDirectory.dir("src/seed")
val seedOutput = layout.buildDirectory.dir("generated/seed")
val generateSeed = tasks.register<JavaExec>("generateSeed") {
    group = "build"
    description = "Writes the seed bundle ledger-seed.mv.db (H2) from the text files of src/seed."
    classpath = seedBuilder
    mainClass = "org.veridger.seed.SeedBuilder"
    args(seedSources.asFile.absolutePath, seedOutput.get().asFile.absolutePath)
    inputs.dir(seedSources).withPropertyName("seedSources")
    outputs.dir(seedOutput).withPropertyName("seedOutput")
}
// The one place of the install defaults is src/seed/install-defaults.properties: the seed bundle carries it for the Java code, and
// this puts the instance name into application.properties, whose placeholders are read before any bean exists (SET-053).
val installDefaultsFile = seedSources.file("install-defaults.properties")
// The file is read through a provider, so that the configuration cache knows it as an input of the build and a change of it is seen.
val installDefaultsInstanceName = providers.fileContents(installDefaultsFile).asBytes.map { bytes ->
    Properties().apply { load(StringReader(String(bytes, Charsets.UTF_8))) }.getProperty("instance-name")
}
// the value is a local one: the action below must not hold the build script (the configuration cache stores the action)
run {
    val instanceName = installDefaultsInstanceName.get()
    tasks.processResources {
        from(generateSeed) {
            include("ledger-seed.mv.db")
        }
        inputs.file(installDefaultsFile).withPropertyName("installDefaults")
        filesMatching("application.properties") {
            filter<org.apache.tools.ant.filters.ReplaceTokens>("tokens" to mapOf("instance-name" to instanceName))
        }
    }
}

// DOC-001 .. DOC-029: the documentation tests read the READMEs, the release notes and the sources of the other modules as
// text. The root of the repository is passed in, never guessed, and the files are inputs, so a change of a document or of
// the code a document mirrors runs the tests again.
tasks.test {
    // PCS-031: the test of the effective rights of the service account calls the Windows API (GetEffectiveRightsFromAclW) with the
    // foreign function API, which Java 25 restricts; and so does the product, in one class (R9-D0095-A). The executable jar allows
    // it with its manifest, a test JVM is given the option.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("ledger.docs.root", rootProject.layout.projectDirectory.asFile.absolutePath)
    inputs.files(rootProject.fileTree(rootProject.layout.projectDirectory) {
        include("README*.md", "docs/**/*.md", "clients/**", "settings.gradle.kts", "build.gradle.kts",
            "ledger-*/src/main/java/**/*.java", "ledger-cli/src/seed/**", "ledger-database/src/main/resources/**",
            "deploy/*", "ledger-*/build.gradle.kts", "ledger-*/src/test*/java/**/*.java", "bin/**", "web/**", "mcp/**",
            "sql/**", "tests/**")
    }).withPropertyName("documentationSources")
}

// mcp-check is tested against an MCP server started in the test: the MCP Java SDK server over Streamable HTTP in an embedded
// Tomcat. Tomcat brings the servlet classes, and the tests of the CLI itself assert that the CLI has none (CLI-002), so these
// tests have a source set and a test task of their own: it sees what the tests see (the test classes and their dependencies)
// and adds Tomcat. Boot excludes tomcat-annotations-api and uses the Jakarta annotations API instead; so does this.
val serverTest: SourceSet = sourceSets.create("serverTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output + configurations.testCompileClasspath.get()
    runtimeClasspath += output + compileClasspath + configurations.testRuntimeClasspath.get()
}
configurations["serverTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["serverTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    "serverTestImplementation"("org.apache.tomcat.embed:tomcat-embed-core") {
        exclude(group = "org.apache.tomcat", module = "tomcat-annotations-api")
    }
    "serverTestImplementation"("jakarta.annotation:jakarta.annotation-api")
}
val serverTestTask = tasks.register<Test>("serverTest") {
    description = "Runs the tests that need an MCP server (embedded Tomcat)."
    group = "verification"
    testClassesDirs = serverTest.output.classesDirs
    classpath = serverTest.runtimeClasspath
    shouldRunAfter(tasks.test)
}
tasks.named("check") { dependsOn(serverTestTask) }
