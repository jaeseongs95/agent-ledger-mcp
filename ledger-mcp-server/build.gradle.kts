plugins {
    // Spring Boot Gradle plugin: bootJar builds the one executable jar (java -jar ledger-mcp-server.jar); the version is the catalog Boot version.
    id("org.springframework.boot") version libs.versions.springBoot.get()
}

// The built client hook (ledger-client-hook.jar, the light client, with the resident program ledger-hook-resident.jar next to it),
// only for the tests: the round trip test (MCW-040) signs with the real hook, started as a process, and calls this server with
// what it printed. It is not part of the server. The stageHookRuntime task of the hook module puts the two jars into one
// folder; its path is known without resolving a configuration (the hook module is configured first).
evaluationDependsOn(":ledger-client-hook")
val hookRuntime = project(":ledger-client-hook").tasks.named<Copy>("stageHookRuntime")

dependencies {
    // The services of the ledger (write gate, receipts, history, queries) with the database module and the domain.
    implementation(project(":ledger-application"))
    // The digest of the arguments of a call (RFC 8785 canonical form, SHA-256): the tool binding computes it with the
    // class the client hook signs with, so the signed arguments and the received arguments are compared by one rule.
    implementation(project(":ledger-attestation"))
    // Spring AI MCP server over Streamable HTTP on Spring MVC (version from the Spring AI BOM declared at the root).
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Bearer token and Host allow list in one Spring Security filter chain.
    implementation("org.springframework.boot:spring-boot-starter-security")
    // The bearer token filter of Spring Security (BearerTokenAuthenticationFilter and its token resolver); no JWT is used.
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    // /healthz is the Actuator health endpoint.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("tools.jackson.core:jackson-databind")

    // The shared architecture and source rules live in the attestation test fixtures; TestOwners (it needs a domain
    // class) stays in the domain test fixtures.
    testImplementation(testFixtures(project(":ledger-attestation")))
    testImplementation(testFixtures(project(":ledger-domain")))
    // The tests play the client hook (they sign attestations with the shared token format) and start the built hook jar.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // ArchUnit 1.5.1: not in the Boot BOM, see gradle/libs.versions.toml
    testImplementation(libs.archunit)
    // The test database support (the PostgreSQL of the test PC: settings, pools, schema per test, PostgreSQL driver).
    testImplementation(testFixtures(project(":ledger-database")))
}

tasks.test {
    // The tests reach the Windows boundary (the names of the well-known accounts, R9-D0095-A) with the foreign function API, which
    // Java 25 restricts; the executable jar allows it with its manifest, a test JVM is given the option.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    dependsOn(hookRuntime)
    // the path of the hook jar is passed in, never guessed (it is the light client of the hook, the resident program is beside it)
    systemProperty("ledger.hook.jar", hookRuntime.get().destinationDir.resolve("ledger-client-hook.jar").absolutePath)
    // The JDK HttpClient refuses to send a Host header unless the property allows it. The Host allow list test
    // (MCC-016) must send a Host that differs from the address it connects to.
    jvmArgs("-Djdk.httpclient.allowRestrictedHeaders=host")
}

tasks.bootJar {
    archiveFileName = "ledger-mcp-server.jar"
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
