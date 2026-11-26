// The wire format of the author attestation and the Ed25519 primitives behind it. JDK only (no dependency at all):
// the client hook signs with it and the server verifies with it, so the two sides cannot drift apart.

plugins {
    // The contract vectors (src/testFixtures/resources/contract) that the hook and the server both test against:
    // the author format and the tool names are defined in each of the two programs, so one set of vectors keeps them equal.
    // The shared architecture and source rules (ArchitectureRules, PrincipleRules, SourceRules, CodingScan, package
    // org.veridger.testing) live in these test fixtures as well. They know no domain class, so this module needs no
    // other project, not even in its tests: ledger-domain depends on this module (Sha256Hex), never the other way round.
    `java-test-fixtures`
}

dependencies {
    // ArchUnit 1.5.1: not in the Boot BOM, see gradle/libs.versions.toml
    testImplementation(libs.archunit)
    // The shared rules use ArchUnit and hand it on to every module that takes these fixtures.
    testFixturesApi(libs.archunit)
}
