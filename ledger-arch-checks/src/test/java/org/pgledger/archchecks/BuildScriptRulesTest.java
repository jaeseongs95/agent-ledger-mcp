package org.pgledger.archchecks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cross-module Gradle build script rules (ARC-BLD): no hardcoded BOM-managed dependency versions and no forbidden
 * libraries declared in any module script.
 */
class BuildScriptRulesTest {

    private static final Pattern HARDCODED_BOM_COORD = Pattern.compile(
            "\"(org\\.springframework(?:\\.[a-z0-9-]+)*|tools\\.jackson(?:\\.[a-z0-9-]+)*|org\\.postgresql|org\\.junit(?:\\.[a-z0-9-]+)*|org\\.assertj|org\\.awaitility):[a-zA-Z0-9._-]+:[^\"\\s]+\"");
    private static final List<String> BANNED_TOKENS = List.of(
            "org.projectlombok", "org.flywaydb", "org.liquibase", "org.hibernate.orm", "info.picocli",
            "org.springframework.shell");

    static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("settings.gradle.kts not found from working directory");
    }

    private static List<Path> moduleBuildScripts() {
        Path root = repoRoot();
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                    .map(dir -> dir.resolve("build.gradle.kts"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("ARC-BLD-01: no module build.gradle.kts pins a version on a BOM-managed coordinate")
    void noManualVersionsOnBomManagedDependencies() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path script : moduleBuildScripts()) {
            String text = Files.readString(script);
            Matcher matcher = HARDCODED_BOM_COORD.matcher(text);
            while (matcher.find()) {
                problems.add(script.getParent().getFileName() + "/build.gradle.kts: " + matcher.group());
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("ARC-BLD-02: no module build.gradle.kts declares a banned ORM, migration, Lombok, or CLI dependency")
    void noBannedDependenciesInBuildScripts() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path script : moduleBuildScripts()) {
            String text = Files.readString(script);
            for (String banned : BANNED_TOKENS) {
                if (text.contains(banned)) {
                    problems.add(script.getParent().getFileName() + "/build.gradle.kts mentions " + banned);
                }
            }
        }
        assertThat(problems).isEmpty();
    }
}
