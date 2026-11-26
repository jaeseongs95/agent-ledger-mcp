// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RUN-005, RUN-018 and RUN-030: what the schema work no longer has is named nowhere in the sources of the product: not in the code, the
 * tests, the settings, the installers, the READMEs and the documents. These are source scans (the text of every file under the
 * repository but the build output and this file), so a module that still names a removed setting, or a document that still
 * describes it, is found here and not by a reader.
 */
class SchemaSettingsSourceTest {

    /** The key of the long text type and its option, the environment variable of the binding, and the old spelling of the placeholder. */
    private static final Pattern LONG_TEXT_SETTING = Pattern.compile(
            "(?i)long-text-type|LEDGER_SCHEMA_LONG|ledger\\.schema\\.long|\\$\\{longtext}");
    /** The wait for another runner that polled the history: the setting, its default and the interval of the poll. */
    private static final Pattern PEER_WAIT = Pattern.compile("(?i)peer[-_]?wait|PEER_POLL|appliedByAnotherProcess");
    /** The standard view of the character sets of the database, and the method that read it (the encoding is proved by a probe now, RUN-030). */
    private static final Pattern CHARACTER_SET_VIEW = Pattern.compile("(?i)character_sets|\\bcharacterSets\\b");
    /** A name of a character set written as a literal, or a name folded in Java to compare it. */
    private static final Pattern CHARACTER_SET_NAME_COMPARISON = Pattern.compile(
            "(?i)\"utf-?8|toUpperCase|toLowerCase|equalsIgnoreCase|replaceAll");
    /** The words of the probe queries that only the dialect of SqlBuilder writes (a length of a cast bind value, the character sets of the schema and the server). */
    private static final Pattern PROBE_QUERY_TEXT = Pattern.compile(
            "(?i)(LENGTH|LEN)\\s*\\(\\s*CAST\\s*\\(\\s*\\?|SYSDUMMY1|DEFAULT_CHARACTER_SET_NAME|character_set_server");
    private static final Pattern SLEEP = Pattern.compile("Thread\\s*\\.\\s*sleep|\\.sleep\\(|\\bsleep\\s*\\(");
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("build", ".git", ".gradle", ".idea", "node_modules", "out");
    private static final Set<String> TEXT_FILES = Set.of("java", "properties", "yaml", "yml", "md", "kts", "sql", "sh", "cmd",
            "xml", "json", "txt", "toml");

    private static boolean isText(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_FILES.contains(name.substring(dot + 1));
    }

    /** The files of the repository (relative, with '/') whose text matches {@code pattern}, this file left out. */
    private static List<String> filesMatching(Pattern pattern, Predicate<String> ofInterest) throws IOException {
        // a Gradle test runs in the directory of its module; the repository is its parent
        Path root = Path.of("").toAbsolutePath().getParent();
        assumeTrue(Files.exists(root.resolve("settings.gradle.kts")), "run from a module of the repository: " + root);
        Path self = Path.of("src", "test", "java", "org", "veridger", "database", "SchemaSettingsSourceTest.java").toAbsolutePath();
        List<String> found = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                return SKIPPED_DIRECTORIES.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (isText(file) && !file.toAbsolutePath().equals(self) && ofInterest.test(relative)
                        && pattern.matcher(StandardCharsets.UTF_8.decode(ByteBuffer.wrap(Files.readAllBytes(file)))).find()) {
                    found.add(relative);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }

    @Test
    @DisplayName("RUN-005 no source, setting, document or installer of the repository names the setting of the long text type or the old placeholder")
    void run005_noSettingForTheLongTextType() throws IOException {
        assertThat(filesMatching(LONG_TEXT_SETTING, file -> true)).as("files that still name a removed setting").isEmpty();
    }

    @Test
    @DisplayName("RUN-030 the encoding is proved by the probe of the dialect: no main source reads the character set view or compares a character set name, and the probe queries are in SqlBuilder only")
    void run030_theEncodingIsProvedByTheProbeOfTheDialect() throws IOException {
        Predicate<String> mainSource = file -> file.contains("/src/main/");
        assertThat(filesMatching(CHARACTER_SET_VIEW, mainSource)).as("main sources that read the list of character sets").isEmpty();
        assertThat(filesMatching(CHARACTER_SET_NAME_COMPARISON, file -> file.endsWith("/database/schema/DatabaseEncoding.java")))
                .as("a comparison of a character set name in the judgement").isEmpty();
        assertThat(filesMatching(PROBE_QUERY_TEXT, file -> mainSource.test(file) && file.endsWith(".java")))
                .as("the text of the probe queries is the builder's alone")
                .containsExactly("ledger-database/src/main/java/org/veridger/database/repository/SqlBuilder.java");
    }

    @Test
    @DisplayName("RUN-018 the runners never wait for each other themselves: no setting or constant of a wait for a peer anywhere, and no sleep in the schema code")
    void run018_theDatabaseWaitsNotTheRunner() throws IOException {
        assertThat(filesMatching(PEER_WAIT, file -> true)).as("files that still name the wait for another runner").isEmpty();
        assertThat(filesMatching(SLEEP, file -> file.startsWith("ledger-database/src/main/java/org/veridger/database/schema/")))
                .as("files of the schema code that sleep").isEmpty();
    }
}
