dependencies {
    api(project(":ledger-database"))
    // The signed author attestation (token format and Ed25519 primitives, JDK only), shared with the client hook.
    implementation(project(":ledger-attestation"))
    // Jakarta Validation (Hibernate Validator, Boot BOM): the service input records carry the constraints; the
    // records are public API of this module, so the consumers need the annotations on their compile classpath.
    api("org.springframework.boot:spring-boot-starter-validation")
    // Jackson 3 (Boot BOM): history changes, before_data and receipt responses are records written as JSON text.
    // Spring Boot's starter makes the JsonMapper bean that LedgerJson derives its mappers from.
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("tools.jackson.core:jackson-databind")

    // TestOwners needs a domain class, so it stays in the domain test fixtures.
    testImplementation(testFixtures(project(":ledger-domain")))
    // The shared architecture and source rules, and the contract vectors of the author format and of the tool names, which
    // the client hook tests against as well: the server's side of that contract is tested here (ContractVectorsTest).
    testImplementation(testFixtures(project(":ledger-attestation")))
    // The test database support (the PostgreSQL of the test PC: settings, pools, schema per test, PostgreSQL driver).
    testImplementation(testFixtures(project(":ledger-database")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // ArchUnit 1.5.1: not in the Boot BOM, see gradle/libs.versions.toml
    testImplementation(libs.archunit)
}

// The test support of this module (the application context of the service tests, the scenario helpers, the key ring) is offered
// to the tests of ledger-migrate-1x, whose import runs in a context of the real services: the compiled test classes as one jar,
// consumed through the configuration testSupport only. No main code of any module depends on it.
val testSupportJar = tasks.register<Jar>("testSupportJar") {
    archiveClassifier = "test-support"
    from(the<org.gradle.api.tasks.SourceSetContainer>()["test"].output)
}
val testSupport = configurations.create("testSupport") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(testSupport.name, testSupportJar)
