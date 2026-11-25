plugins {
    // Spring Boot Gradle plugin: bootJar builds the one executable jar (java -jar ledger-admin-web.jar); the version is the catalog Boot version.
    id("org.springframework.boot") version libs.versions.springBoot.get()
}

dependencies {
    // The ledger services (projects, grants, common codes, read services) with the database module and the domain.
    implementation(project(":ledger-application"))
    // Spring MVC with server-side rendering by Thymeleaf; the CSRF token is added to th:action forms by Spring Security.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    // Login, session, CSRF, Host allow list (firewall) and the security headers in one Spring Security chain.
    implementation("org.springframework.boot:spring-boot-starter-security")
    // The bearer token filter of Spring Security (BearerTokenAuthenticationFilter and its token resolver) reads the admin token of API clients; no JWT is used.
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    // OIDC oauth2Login: the provider is configuration (spring.security.oauth2.client.*), no provider is named in code.
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    // Passkeys: the WebAuthn support of Spring Security (sign-in, registration, the check of every approval; WebAuthn4J is its
    // engine, Apache-2.0) and the one-time token sign-in that carries the code of the owner's shell are in spring-security-core/web.
    implementation(libs.spring.security.webauthn)
    // RestClient (with its builder auto-configuration) for the MCP health check of the overview.
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    // /healthz is the Actuator health endpoint.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("tools.jackson.core:jackson-databind")
    // YAML writer of the driver registration file (the same Jackson 3 mapper family, version from the Boot BOM).
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")

    // The shared architecture and source rules live in the attestation test fixtures; TestOwners and OwnerOnlyFiles
    // (they need a domain class) stay in the domain test fixtures.
    testImplementation(testFixtures(project(":ledger-attestation")))
    testImplementation(testFixtures(project(":ledger-domain")))
    // The tests play the client hook of an author: they make an Ed25519 key pair and sign as the hook does (JDK only).
    testImplementation(project(":ledger-attestation"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // MockMvc and @AutoConfigureMockMvc (Boot 4 keeps the MVC test support in its own starter).
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // Spring Security test support: the security filter chain in MockMvc.
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    // ArchUnit 1.5.1: not in the Boot BOM, see gradle/libs.versions.toml
    testImplementation(libs.archunit)
    // The test database support (the PostgreSQL of the test PC: settings, pools, schema per test, PostgreSQL driver).
    testImplementation(testFixtures(project(":ledger-database")))
}

tasks.test {
    // The tests reach the Windows boundary (the names of the well-known accounts, R9-D0095-A) with the foreign function API, which
    // Java 25 restricts; the executable jar allows it with its manifest, a test JVM is given the option.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // The Host allow list tests send a Host header that differs from the address they connect to.
    jvmArgs("-Djdk.httpclient.allowRestrictedHeaders=host")
}

tasks.bootJar {
    archiveFileName = "ledger-admin-web.jar"
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
