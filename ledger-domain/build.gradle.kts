plugins {
    `java-test-fixtures`
}

dependencies {
    // Spring Data mapping annotations (@Table, @Id, @Version, @CreatedBy ...) are allowed on the aggregates; no web, no Jackson.
    api("org.springframework.data:spring-data-relational")

    // The one SHA-256 helper of the product (Sha256Hex). ledger-attestation is JDK-only, so the domain stays free of web and Jackson.
    implementation(project(":ledger-attestation"))

    testFixturesImplementation(platform(libs.spring.boot.bom))
    // The shared rules (ArchitectureRules, PrincipleRules, SourceRules, CodingScan) live in the attestation test fixtures,
    // not here: a test dependency of ledger-attestation on this module would close a project cycle with the main
    // dependency above. These fixtures keep what needs a domain class (TestOwners, OwnerOnlyFiles) and hand the rules on.
    testFixturesApi(testFixtures(project(":ledger-attestation")))
    testFixturesApi("org.assertj:assertj-core")
    testFixturesApi("org.junit.jupiter:junit-jupiter")

    // ArchUnit 1.5.1: see gradle/libs.versions.toml
    testImplementation(libs.archunit)
}
