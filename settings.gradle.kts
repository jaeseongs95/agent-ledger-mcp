rootProject.name = "agent-ledger-mcp"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// ledger-system-test is test code only: it starts the built jars of the other modules (docs/testing/TEST_CASES.md section 21).
// ledger-seed holds the seed bundle (the initial data of a new installation in one H2 file, SETUP) and is used by ledger-cli only.
// ledger-migrate-1x is the tool that moves a 1.x installation to 2.0 (docs/testing/TEST_CASES.md section 43): it uses the
// domain, database and application modules, and no other module uses it.
include("ledger-domain", "ledger-attestation", "ledger-files", "ledger-database", "ledger-application", "ledger-mcp-server",
    "ledger-admin-web", "ledger-cli", "ledger-seed", "ledger-hook-client", "ledger-client-hook", "ledger-migrate-1x", "ledger-system-test",
    "ledger-arch-checks")
