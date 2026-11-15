// One executable jar for the three client hooks: `java -jar ledger-client-hook.jar <claude|codex|antigravity>`, and for
// the key command `java -jar ledger-client-hook.jar session-key <server> <author>`.
// The hook is a Spring Boot application without a web server (user decision 2026-11-15: 「Spring으로 전환」): the Spring
// Boot plugin builds the executable jar (bootJar). The start time of the JVM is no reason to avoid Spring (principle 5.3);
// it is measured and handled with the AOT cache, which this build does not set up yet.
plugins {
    // Spring Boot Gradle plugin: bootJar builds the one executable jar (java -jar ledger-client-hook.jar); the version is the catalog Boot version.
    id("org.springframework.boot") version libs.versions.springBoot.get()
}

val hookJarName = "ledger-client-hook.jar"

dependencies {
    // Spring Boot core, logging (SLF4J + Logback) and the configuration properties binding; no web server, no JDBC, no data access (HK-018).
    implementation("org.springframework.boot:spring-boot-starter")
    // Jackson 3 (tools.jackson), version from the Boot BOM (the JDK has no JSON parser).
    implementation("tools.jackson.core:jackson-databind")

    // The attestation token and the Ed25519 key text forms (pure JDK): the hook signs with them, the server verifies
    // with the same classes. bootJar packs the project jar of this module into BOOT-INF/lib.
    implementation(project(":ledger-attestation"))

    // ApplicationContextRunner and the other test support of Spring Boot (the slice tests of the beans, principle 7.2).
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // ArchUnit 1.5.1: not in the Boot BOM, see gradle/libs.versions.toml; checks the no-network/no-file rules of HK-018.
    testImplementation(libs.archunit)
    // The shared architecture and source rules (ARC-033: no operating system detection) live in the domain test fixtures.
    // Test classpath only: the hook jar is built from the runtime classpath and stays free of the domain module.
    testImplementation(testFixtures(project(":ledger-domain")))
    // The contract vectors (author format, tool names) that the server tests against as well: the hook and the server are
    // two programs that each define those, so one set of files keeps them equal. Test classpath only.
    testImplementation(testFixtures(project(":ledger-attestation")))
}

// The executable jar keeps its name: the plugin manifests of the three hosts call lib/ledger-client-hook.jar. The plain jar
// of the module (classifier "plain", not executable) is not used by anything.
tasks.bootJar {
    archiveFileName = hookJarName
    // The licence and notice texts of each dependency stay in the jar under META-INF/third-party/<jar>/, as they did in the
    // hand-merged jar of the earlier versions (the dependency jars themselves are nested unchanged in BOOT-INF/lib, each with
    // its own META-INF; this copy keeps them in one place that a reader of the jar finds without opening the nested jars).
    // The dependency jars (Jackson, the Spring libraries and the project jar of ledger-attestation) are built before they are read.
    dependsOn(configurations.runtimeClasspath)
    val licenseFiles = listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*-LICENSE")
    configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.forEach { dependency ->
        from(zipTree(dependency)) {
            include(licenseFiles)
            eachFile { path = "META-INF/third-party/${dependency.nameWithoutExtension}/$name" }
            includeEmptyDirs = false
        }
    }
}

// Plugin directories for the three hosts: manifests and README of clients/<client> plus lib/<jar>.
// build/client-plugins/<client> is what the installation copies to the host's plugin location.
val stagedClients = listOf("claude", "codex", "antigravity")
val stageTasks = stagedClients.map { client ->
    tasks.register<Copy>("stage${client.replaceFirstChar { it.uppercase() }}Plugin") {
        group = "distribution"
        description = "Stages the $client plugin directory with lib/$hookJarName."
        from(rootProject.layout.projectDirectory.dir("clients/$client"))
        from(tasks.bootJar) { into("lib") }
        into(layout.buildDirectory.dir("client-plugins/$client"))
        includeEmptyDirs = false
    }
}
val stageClientPlugins = tasks.register("stageClientPlugins") {
    group = "distribution"
    description = "Stages all three client plugin directories."
    dependsOn(stageTasks)
}
tasks.assemble { dependsOn(stageClientPlugins) }

tasks.test {
    dependsOn(tasks.bootJar, stageClientPlugins)
    // What the tests read besides their class path, so that a change of it runs them again: the jar, the staged plugin
    // directories, the manifests of the clients, and the JDK older than 25 of the machine (HK-021, optional).
    inputs.files(tasks.bootJar).withPropertyName("hookJar").withPathSensitivity(PathSensitivity.NONE)
    inputs.dir(layout.buildDirectory.dir("client-plugins")).withPropertyName("stagedPlugins").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir("clients")).withPropertyName("clients").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("legacyJava", providers.environmentVariable("LEDGER_HOOK_LEGACY_JAVA").orElse(""))
    // The cold start of the jar is measured and recorded (HK-017): the files are an output of the test task.
    outputs.dir(layout.buildDirectory.dir("hook-metrics")).withPropertyName("hookMetrics")
    // Not cached: the tests start the real jar as processes and measure the wall time of each start (HK-017), and the time of
    // the machine is an input that Gradle cannot see. An unchanged run may still be up to date.
    outputs.doNotCacheIf("the tests measure the wall time of real java processes (HK-017)") { true }
    // The tests start the real jar and read the manifests; the paths are passed in, never guessed.
    systemProperty("ledger.hook.jar", tasks.bootJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty("ledger.hook.staging", layout.buildDirectory.dir("client-plugins").get().asFile.absolutePath)
    systemProperty("ledger.hook.metrics", layout.buildDirectory.dir("hook-metrics").get().asFile.absolutePath)
    systemProperty("ledger.hook.clients", rootProject.layout.projectDirectory.dir("clients").asFile.absolutePath)
}
