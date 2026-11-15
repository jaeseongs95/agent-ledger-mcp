plugins {
    `java-test-fixtures`
}

dependencies {
    // Spring Data mapping annotations (@Table, @Id, @Version, @CreatedBy ...) are allowed on the aggregates; no web, no Jackson.
    api("org.springframework.data:spring-data-relational")

    // The one SHA-256 helper of the product (Sha256Hex). ledger-attestation is JDK-only, so the domain stays free of web and Jackson.
    implementation(project(":ledger-attestation"))

    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesApi(libs.archunit)
    testFixturesApi("org.assertj:assertj-core")
    testFixturesApi("org.junit.jupiter:junit-jupiter")

    // ArchUnit 1.5.1: see gradle/libs.versions.toml
    testImplementation(libs.archunit)
}
