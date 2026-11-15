plugins {
    `java-test-fixtures`
}

dependencies {
    api(project(":ledger-domain"))
    // Spring JDBC, Spring Data JDBC and Hikari. No JDBC driver: the driver is registered by the user at run time.
    api("org.springframework.boot:spring-boot-starter-data-jdbc")
    // DatabaseDriver names the product of a JDBC URL or of a connection (SqlBuilder); the starter above brings the module, it
    // is named here because the code uses its class directly (version from the Boot BOM)
    implementation("org.springframework.boot:spring-boot-jdbc")
    // Bean Validation for the @Validated settings records: a bad value stops the startup (version from the Boot BOM)
    api("org.springframework.boot:spring-boot-starter-validation")
    // YAML reader and writer (Jackson 3, Boot BOM) of the driver registration file that the admin web and the CLI write.
    api("tools.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation(testFixtures(project(":ledger-domain")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // The test slices of Spring Boot 4 are modules of their own (version from the Boot BOM): @JdbcTest and @DataJdbcTest
    // for the repository tests (principle 7.2), on the PostgreSQL of the test PC (see the test fixtures below).
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
    // JSON test data is written as records and serialised with Jackson 3 (Boot BOM)
    testImplementation("tools.jackson.core:jackson-databind")
    // ArchUnit: not in the Boot BOM, see gradle/libs.versions.toml
    testImplementation(libs.archunit)
}

// The test database support (TEST_CASES.md 1.4a): the settings, the connection pools and the schema per test on the PostgreSQL of
// the test PC, shared by the tests of the modules that use the database. H2 is not a test database. The PostgreSQL driver is a
// test fixture dependency and so is on the test class path of every module that uses the fixtures; the product ships none, and
// the runtime class path check does not look at test fixtures.
dependencies {
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesApi("org.postgresql:postgresql")
    // OwnerOnlyFiles: the copy of the driver jar that a test registers (the product refuses a jar that others can write)
    testFixturesImplementation(testFixtures(project(":ledger-domain")))
    testFixturesImplementation("tools.jackson.core:jackson-databind")
}
