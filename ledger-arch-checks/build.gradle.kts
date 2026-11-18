dependencies {
    testImplementation(project(":ledger-domain"))
    testImplementation(project(":ledger-attestation"))
    testImplementation(project(":ledger-files"))
    testImplementation(project(":ledger-database"))
    testImplementation(project(":ledger-application"))
    testImplementation(project(":ledger-mcp-server"))
    testImplementation(project(":ledger-admin-web"))
    testImplementation(project(":ledger-cli"))
    testImplementation(project(":ledger-seed"))
    testImplementation(project(":ledger-client-hook"))
    testImplementation(project(":ledger-migrate-1x"))

    testImplementation(testFixtures(project(":ledger-attestation")))
    testImplementation(libs.archunit)
    testImplementation("org.springframework.boot:spring-boot-starter")
    testImplementation("org.springframework:spring-tx")
}
