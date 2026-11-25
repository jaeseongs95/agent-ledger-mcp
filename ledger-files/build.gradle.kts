// Pure-JDK owner-only file and ACL protection shared by the server (attachments), the CLI (secret store), and the client hook (session keys).
// Keeps POSIX and Windows ACL creation/settlement and LinkOption.NOFOLLOW_LINKS inspection in one dependency-free module.
dependencies {
    testImplementation(libs.archunit)
    testImplementation(testFixtures(project(":ledger-attestation")))
}

// R9-D0095-A: the test of the boundary class calls the Windows API with the foreign function API, which Java 25 restricts. The
// executable jars of the programs that reach it allow it with the manifest attribute Enable-Native-Access (their build files); a
// test JVM has no manifest, so it is given the option.
tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
