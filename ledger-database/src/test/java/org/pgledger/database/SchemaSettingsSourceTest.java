package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RUN-005: the settings that the schema work no longer has are named nowhere in the sources of the product: not in the code, the
 * tests, the settings, the installers, the READMEs and the documents. The long text type of the schema files is read from the driver
 * ({@code LongTextType}), so there is no setting for it and no option of the installer; the old spelling of its placeholder is gone
 * from the schema files too. A source scan (the text of every file under the repository but the build output and this file), so a
 * module that still names the key, or a document that still describes it, is found here and not by a reader.
 */
class SchemaSettingsSourceTest {

    /** The key and its option, the environment variable of the binding, and the old spelling of the placeholder. */
    private static final Pattern REMOVED = Pattern.compile("(?i)long-text-type|LEDGER_SCHEMA_LONG|ledger\\.schema\\.long|\\$\\{longtext}");
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("build", ".git", ".gradle", ".idea", "node_modules", "out");
    private static final Set<String> TEXT_FILES = Set.of("java", "properties", "yaml", "yml", "md", "kts", "sql", "sh", "cmd",
            "xml", "json", "txt", "toml");

    private static boolean isText(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_FILES.contains(name.substring(dot + 1));
    }

    @Test
    @DisplayName("RUN-005 no source, setting, document or installer of the repository names the setting of the long text type or the old placeholder")
    void run005_noSettingForTheLongTextType() throws IOException {
        // a Gradle test runs in the directory of its module; the repository is its parent
        Path root = Path.of("").toAbsolutePath().getParent();
        assumeTrue(Files.exists(root.resolve("settings.gradle.kts")), "run from a module of the repository: " + root);
        Path self = Path.of("src", "test", "java", "org", "pgledger", "database", "SchemaSettingsSourceTest.java").toAbsolutePath();
        List<String> found = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                return SKIPPED_DIRECTORIES.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (isText(file) && !file.toAbsolutePath().equals(self)
                        && REMOVED.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).find()) {
                    found.add(root.relativize(file).toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(found).as("files that still name a removed setting").isEmpty();
    }
}
