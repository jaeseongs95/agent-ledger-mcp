import org.springframework.boot.gradle.tasks.aot.ProcessAot

// The resident program of the client hook (D-0096, user decision 2026-11-22: 「jar는 상주 프로그램」): one executable Spring Boot
// jar, ledger-hook-resident.jar, started by the light client (module ledger-hook-client, ledger-client-hook.jar) with
// `java -jar ledger-hook-resident.jar start <state-folder> <build>`. It keeps running between the host calls and answers each call
// with the beans that answered the one-shot hook before: the one place where Spring and Jackson are loaded, once instead of for
// every call. The hosts never run this jar.
// The hook is a Spring Boot application without a web server (user decision 2026-11-15: 「Spring으로 전환」): the Spring
// Boot plugin builds the executable jar (bootJar), and Spring Boot AOT (processAot) pre-computes bean definitions at
// build time (principle 5.3) so the one start of the program skips runtime ASM scanning and condition probing.
plugins {
    // Spring Boot Gradle plugin: bootJar builds the one executable jar (java -jar ledger-client-hook.jar); the version is the catalog Boot version.
    id("org.springframework.boot") version libs.versions.springBoot.get()
}

val residentJarName = "ledger-hook-resident.jar"
val clientJarName = "ledger-client-hook.jar"

configurations.implementation {
    // YAML, unused logging bridges, and Micrometer are excluded so cold CLI startup does not mount or scan unused jars.
    exclude(group = "org.yaml", module = "snakeyaml")
    exclude(group = "org.apache.logging.log4j", module = "log4j-to-slf4j")
    exclude(group = "org.apache.logging.log4j", module = "log4j-api")
    exclude(group = "org.slf4j", module = "jul-to-slf4j")
    exclude(group = "io.micrometer", module = "micrometer-observation")
    exclude(group = "io.micrometer", module = "micrometer-commons")
}

dependencies {
    // Declared in class-load frequency order so BOOT-INF/classpath.idx places the hottest nested jars first.
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.core:jackson-core")
    implementation("org.springframework:spring-core")
    implementation("org.springframework:spring-beans")
    implementation("org.springframework:spring-context")
    implementation("org.springframework.boot:spring-boot")
    // Spring Boot core, logging (SLF4J + Logback) and the configuration properties binding; no web server, no JDBC, no data access (HK-018).
    implementation("org.springframework.boot:spring-boot-starter")
    // Jackson 3 (tools.jackson) with the JsonMapper that Boot configures (JacksonAutoConfiguration, the settings are
    // spring.jackson.* in application.properties); the versions come from the Boot BOM (the JDK has no JSON parser).
    implementation("org.springframework.boot:spring-boot-starter-jackson")

    // The attestation token and the Ed25519 key text forms (pure JDK): the hook signs with them, the server verifies
    // with the same classes. bootJar packs the project jar of this module into BOOT-INF/lib.
    implementation(project(":ledger-attestation"))
    // Owner-only POSIX/ACL key-folder and key-file protection (pure JDK), shared with the server and the CLI.
    implementation(project(":ledger-files"))
    // The wire format, the state folder and the address file that the light client and this program both use (java.base only).
    implementation(project(":ledger-hook-client"))

    // ApplicationContextRunner and the other test support of Spring Boot (the slice tests of the beans, principle 7.2).
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // ArchUnit 1.5.1: not in the Boot BOM, see gradle/libs.versions.toml; checks the no-network/no-file rules of HK-018.
    testImplementation(libs.archunit)
    // The shared architecture and source rules (ARC-033: no operating system detection) and the contract vectors (author
    // format, tool names) live in the attestation test fixtures; the server tests against the same vectors: the hook and
    // the server are two programs that each define those, so one set of files keeps them equal. Test classpath only.
    // The hook uses nothing of the domain module, not even in its tests (the hook jar is built from the runtime classpath).
    testImplementation(testFixtures(project(":ledger-attestation")))
}

val javaToolchains = extensions.getByType<JavaToolchainService>()
val javaExt = extensions.getByType<JavaPluginExtension>()

val processAot = tasks.register<ProcessAot>("processAot") {
    group = "build"
    description = "Generates Spring Boot AOT bean definitions for HookApplication."
    dependsOn(tasks.classes)
    applicationMainClass = "org.pgledger.hook.Main"
    classpath(sourceSets.main.get().runtimeClasspath)
    sourcesOutput = layout.buildDirectory.dir("generated/aotSources")
    resourcesOutput = layout.buildDirectory.dir("generated/aotResources")
    classesOutput = layout.buildDirectory.dir("generated/aotClasses")
    groupId = project.group.toString()
    artifactId = project.name
    javaLauncher = javaToolchains.launcherFor(javaExt.toolchain)
}

val compileAotJava = tasks.register<JavaCompile>("compileAotJava") {
    group = "build"
    description = "Compiles generated Spring Boot AOT sources for the hook jar."
    dependsOn(processAot)
    source(processAot.flatMap { it.sourcesOutput })
    classpath = sourceSets.main.get().runtimeClasspath + files(processAot.flatMap { it.classesOutput })
    destinationDirectory = layout.buildDirectory.dir("classes/java/aot")
    javaCompiler = javaToolchains.compilerFor(javaExt.toolchain)
    options.compilerArgs.remove("-Werror")
    options.compilerArgs.add("-Xlint:none")
}

val springAotJar = tasks.register<Jar>("springAotJar") {
    group = "build"
    description = "Packages compiled Spring Boot AOT classes into a nested library jar for bootJar."
    archiveBaseName = "spring-aot"
    archiveVersion = project.version.toString()
    from(compileAotJava) {
        exclude("previous-compilation-data.bin")
    }
    from(processAot.flatMap { it.classesOutput })
    from(resources.text.fromString("org.pgledger.hook.HookLoggingSystem\$LogbackConfigurator\n")) {
        into("META-INF/services")
        rename { "ch.qos.logback.classic.spi.Configurator" }
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// The resident program lies next to the light client in the plugin folder (ResidentJar.FILE_NAME); the hosts call the light client.
// The plain jar of the module (classifier "plain", not executable) is not used by anything.
tasks.bootJar {
    archiveFileName = residentJarName
    includeTools = false
    classpath(springAotJar)
    // The licence and notice texts of each dependency stay in the jar under META-INF/third-party/<jar>/, as they did in the
    // hand-merged jar of the earlier versions (the dependency jars themselves are nested unchanged in BOOT-INF/lib, each with
    // its own META-INF; this copy keeps them in one place that a reader of the jar finds without opening the nested jars).
    // The dependency jars (Jackson, the Spring libraries and the project jar of ledger-attestation) are built before they are read.
    dependsOn(configurations.runtimeClasspath, springAotJar)
    val licenseFiles = listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*-LICENSE")
    configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.forEach { dependency ->
        from(zipTree(dependency)) {
            include(licenseFiles)
            eachFile { path = "META-INF/third-party/${dependency.nameWithoutExtension}/$name" }
            includeEmptyDirs = false
        }
    }
}

// The light client jar of the other module, which the plugin directories and the tests need.
val hookClientJar = configurations.create("hookClientJar") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    add("hookClientJar", project(mapOf("path" to ":ledger-hook-client", "configuration" to "hookClientJar")))
}

// Plugin directories for the three hosts: manifests and README of clients/<client> plus lib/<both jars>.
// build/client-plugins/<client> is what the installation copies to the host's plugin location.
val stagedClients = listOf("claude", "codex", "antigravity")
val stageTasks = stagedClients.map { client ->
    tasks.register<Copy>("stage${client.replaceFirstChar { it.uppercase() }}Plugin") {
        group = "distribution"
        description = "Stages the $client plugin directory with lib/$clientJarName and lib/$residentJarName."
        from(rootProject.layout.projectDirectory.dir("clients/$client"))
        from(tasks.bootJar) { into("lib") }
        from(hookClientJar) { into("lib") }
        into(layout.buildDirectory.dir("client-plugins/$client"))
        includeEmptyDirs = false
    }
}
// The two jars of the hook next to each other (the light client finds the resident program beside itself): what the tests of the
// server and the system test start as "the hook jar". A plugin directory has them in lib/ (the tasks above).
val stageHookRuntime = tasks.register<Copy>("stageHookRuntime") {
    group = "distribution"
    description = "Copies $clientJarName and $residentJarName next to each other for the tests of the other modules."
    from(tasks.bootJar)
    from(hookClientJar)
    into(layout.buildDirectory.dir("hook-runtime"))
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
    inputs.files(tasks.bootJar).withPropertyName("residentJar").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(hookClientJar).withPropertyName("clientJar").withPathSensitivity(PathSensitivity.NONE)
    inputs.dir(layout.buildDirectory.dir("client-plugins")).withPropertyName("stagedPlugins").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir("clients")).withPropertyName("clients").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("olderJava", providers.environmentVariable("LEDGER_HOOK_OLDER_JAVA").orElse(""))
    // The cold start of the jar is measured and recorded (HK-017): the files are an output of the test task.
    outputs.dir(layout.buildDirectory.dir("hook-metrics")).withPropertyName("hookMetrics")
    // Not cached: the tests start the real jar as processes and measure the wall time of each start (HK-017), and the time of
    // the machine is an input that Gradle cannot see. An unchanged run may still be up to date.
    outputs.doNotCacheIf("the tests measure the wall time of real java processes (HK-017)") { true }
    // The tests start the real jar and read the manifests; the paths are passed in, never guessed.
    // ledger.hook.jar is the light client that a host runs, in a staged plugin folder with the resident program next to it
    // (the client finds the program beside itself); ledger.hook.resident.jar is the resident program (Spring Boot jar).
    systemProperty("ledger.hook.jar", layout.buildDirectory.file("client-plugins/codex/lib/$clientJarName").get().asFile.absolutePath)
    systemProperty("ledger.hook.resident.jar", tasks.bootJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty("ledger.hook.staging", layout.buildDirectory.dir("client-plugins").get().asFile.absolutePath)
    systemProperty("ledger.hook.metrics", layout.buildDirectory.dir("hook-metrics").get().asFile.absolutePath)
    systemProperty("ledger.hook.clients", rootProject.layout.projectDirectory.dir("clients").asFile.absolutePath)
}
