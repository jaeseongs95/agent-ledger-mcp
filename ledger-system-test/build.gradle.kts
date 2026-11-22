// The system test of the packaged product (docs/testing/TEST_CASES.md section 21, SYS-001 .. SYS-012): test code only, no main
// code, and no module depends on this one. The tests start the built jars of ledger-cli and ledger-mcp-server and the hook jar
// as separate Java processes and reach the server with the MCP Java SDK client, against the PostgreSQL of the test PC.

// the bootJar tasks of the two applications are looked up here, so the projects are configured first
evaluationDependsOn(":ledger-cli")
evaluationDependsOn(":ledger-mcp-server")
// the admin web is not started here; its built jar is looked into for a JDBC driver (DRV-013, SET-041)
evaluationDependsOn(":ledger-admin-web")

// The built client hook (the light client ledger-client-hook.jar with the resident program next to it); the same arrangement as
// in ledger-mcp-server (the stageHookRuntime task of the hook module).
evaluationDependsOn(":ledger-client-hook")

// The built migration tool jar (ledger-migrate-1x.jar, M1X-002, M1X-041): started as a process and looked into; the one place a
// build script names the tool, as the system test starts the jars of all programs.
evaluationDependsOn(":ledger-migrate-1x")

dependencies {
    // The test database support: the settings of the database and the passwords read from their files, the name of a test
    // schema, and the PostgreSQL JDBC driver (a test fixture dependency: the tests connect as the owner to read the rows).
    testImplementation(testFixtures(project(":ledger-database")))
    // The MCP Java SDK client (the SDK the server runs on) and the Jackson 3 mapper it writes JSON with (Boot BOM).
    testImplementation(libs.mcp.sdk)
    testImplementation("tools.jackson.core:jackson-databind")
}

val hookRuntime = project(":ledger-client-hook").tasks.named<Copy>("stageHookRuntime")
val cliJar = project(":ledger-cli").tasks.named<Jar>("bootJar")
val mcpJar = project(":ledger-mcp-server").tasks.named<Jar>("bootJar")
val adminJar = project(":ledger-admin-web").tasks.named<Jar>("bootJar")
val migrateJar = project(":ledger-migrate-1x").tasks.named<Jar>("bootJar")

tasks.test {
    dependsOn(cliJar, mcpJar, adminJar, hookRuntime, migrateJar)
    // The paths are passed in, never guessed. The jars are inputs, so a change of a product jar runs the test again
    // (the database is an input too, which is why the test is never up to date, see the root build).
    inputs.files(cliJar, mcpJar, adminJar, hookRuntime, migrateJar)
    // the paths are known at configuration time (the archive file of a jar task), no configuration is resolved
    systemProperty("ledger.cli.jar", cliJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty("ledger.mcp.jar", mcpJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty("ledger.admin.jar", adminJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty("ledger.hook.jar", hookRuntime.get().destinationDir.resolve("ledger-client-hook.jar").absolutePath)
    systemProperty("ledger.migrate.jar", migrateJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}
