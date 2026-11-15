package org.pgledger.migrate1x;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M1X-001 and M1X-006: the module of the tool is part of the build, builds its own jar, uses only the modules below it, and no other
 * module uses it. The repository root is passed in by the build ({@code ledger.docs.root}), never guessed.
 */
class RepositoryLayoutTest {

    private static final Path ROOT = Path.of(System.getProperty("ledger.docs.root"));
    private static final String MODULE = "ledger-migrate-1x";
    /**
     * The modules of the body that the tool uses (the domain and the database come with the application module; ledger-cli is used
     * for its command line machinery and secret store, without its libraries).
     */
    private static final Set<String> USED = Set.of(":ledger-application", ":ledger-attestation", ":ledger-domain",
            ":ledger-database", ":ledger-cli");
    private static final Pattern PROJECT = Pattern.compile("project\\((?:path\\s*=\\s*)?\"(:[a-z0-9-]+)\"");

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("M1X-001 the module is included in settings.gradle.kts, its application jar is ledger-migrate-1x.jar, and it uses only the domain, database, application and attestation modules")
    void m1x001_theModuleIsBuiltAsItsOwnJar() {
        assertThat(read(ROOT.resolve("settings.gradle.kts"))).contains("\"" + MODULE + "\"");
        String build = read(ROOT.resolve(MODULE).resolve("build.gradle.kts"));
        assertThat(build).contains("archiveFileName = \"ledger-migrate-1x.jar\"");
        List<String> used = new ArrayList<>();
        Matcher matcher = PROJECT.matcher(build);
        while (matcher.find()) {
            used.add(matcher.group(1));
        }
        assertThat(used).isNotEmpty().allMatch(USED::contains);
        assertThat(read(ROOT.resolve(MODULE).resolve("src/main/java/org/pgledger/migrate1x/Migrate1xApplication.java")))
                .contains("public static void main(String[] args)");
    }

    @Test
    @DisplayName("M1X-006 no build script of another module names ledger-migrate-1x (the system test excepted: it starts the jar) and no main source of another module imports org.pgledger.migrate1x")
    void m1x006_nothingUsesTheTool() throws IOException {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> modules = Files.list(ROOT)) {
            for (Path module : modules.filter(p -> p.getFileName().toString().startsWith("ledger-")).sorted().toList()) {
                String name = module.getFileName().toString();
                if (name.equals(MODULE)) {
                    continue;
                }
                Path build = module.resolve("build.gradle.kts");
                if (!name.equals("ledger-system-test") && Files.isRegularFile(build) && read(build).contains(MODULE)) {
                    problems.add(build + " names " + MODULE);
                }
                Path sources = module.resolve("src/main/java");
                if (Files.isDirectory(sources)) {
                    try (Stream<Path> files = Files.walk(sources)) {
                        for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                            if (read(file).contains("org.pgledger.migrate1x")) {
                                problems.add(file + " imports org.pgledger.migrate1x");
                            }
                        }
                    }
                }
            }
        }
        assertThat(problems).isEmpty();
    }
}
