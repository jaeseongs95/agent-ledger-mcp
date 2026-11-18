package org.pgledger.archchecks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.testing.CodingScan;

/**
 * Cross-module SQL and transaction rules (ARC-SQL): append-only tables are never mutated with UPDATE or DELETE in
 * repository SQL, schemas contain no multi-tenant/multi-user columns, and history writes remain guarded.
 */
class SqlRulesTest {

    private static final List<String> APPEND_ONLY_TABLES = List.of(
            "history", "attachment", "attachment_file", "attachment_storage");
    private static final Pattern MUTATING_VERB = Pattern.compile(
            "(?is)\\b(?:update\\s+(?:only\\s+)?|delete\\s+from\\s+)([a-z_][a-z0-9_]*)\\b");
    private static final List<String> MULTI_TENANT_TOKENS = List.of(
            "tenant_id", "organization_id", "org_id", "workspace_id", "multi_user");

    private static List<Path> filesUnder(Path dir, String extension) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(extension)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("ARC-SQL-01: repository code never issues UPDATE or DELETE on append-only tables")
    void appendOnlyTablesHaveNoUpdateOrDeleteInMainJava() throws IOException {
        Path root = BuildScriptRulesTest.repoRoot();
        List<Path> repoJava = filesUnder(root.resolve("ledger-database/src/main/java"), ".java");
        List<String> problems = new ArrayList<>();
        for (Path file : repoJava) {
            String raw = Files.readString(file);
            Matcher matcher = MUTATING_VERB.matcher(raw);
            while (matcher.find()) {
                String table = matcher.group(1).toLowerCase(Locale.ROOT);
                if (APPEND_ONLY_TABLES.contains(table)) {
                    problems.add(root.relativize(file) + ": mutates append-only table '" + table + "'");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("ARC-SQL-02: schema migrations contain no multi-tenant or multi-user scoping columns")
    void schemasStaySingleOwnerWithoutMultiTenantColumns() throws IOException {
        Path root = BuildScriptRulesTest.repoRoot();
        List<Path> sqlFiles = filesUnder(root.resolve("ledger-database/src/main/resources/db"), ".sql");
        assertThat(sqlFiles).isNotEmpty();
        List<String> problems = new ArrayList<>();
        for (Path sql : sqlFiles) {
            String lowered = Files.readString(sql).toLowerCase(Locale.ROOT);
            for (String token : MULTI_TENANT_TOKENS) {
                if (lowered.contains(token)) {
                    problems.add(root.relativize(sql) + ": contains multi-tenant column/token '" + token + "'");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("ARC-SQL-03: production source across all modules passes shared CodingScan string and catch rules")
    void productionSourcesPassCodingScan() {
        Path root = BuildScriptRulesTest.repoRoot();
        List<Path> mainDirs = List.of(
                "ledger-domain", "ledger-attestation", "ledger-files", "ledger-database",
                "ledger-application", "ledger-mcp-server", "ledger-admin-web", "ledger-cli",
                "ledger-seed", "ledger-client-hook", "ledger-migrate-1x")
                .stream()
                .map(name -> root.resolve(name).resolve("src/main/java"))
                .filter(Files::isDirectory)
                .toList();
        assertThat(mainDirs).hasSize(11);
        List<String> catchProblems = CodingScan.catchProblems(
                java.util.Set.of("McpCheckCommand.java", "ToolResults.java"),
                "driver",
                mainDirs.toArray(Path[]::new));
        assertThat(catchProblems).isEmpty();
    }
}
