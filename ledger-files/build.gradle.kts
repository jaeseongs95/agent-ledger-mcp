// Pure-JDK owner-only file and ACL protection shared by the server (attachments), the CLI (secret store), and the client hook (session keys).
// Keeps POSIX and Windows ACL creation/settlement and LinkOption.NOFOLLOW_LINKS inspection in one dependency-free module.
dependencies {
    testImplementation(libs.archunit)
    testImplementation(testFixtures(project(":ledger-attestation")))
}
