package org.pgledger.testing;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Rules of version 2.0 about version 1.x (ARC-1X-01 .. 03): the body of the product knows nothing of 1.x. What reads the
 * tables, settings and files of 1.x lives in the separate migration tool, which depends on the body and not the other way
 * round. Each module of the body checks the same three rules on its own classes and sources.
 */
public final class NoOneXRules {

    /** The package of the migration tool: no class of the body depends on it. */
    public static final String MIGRATION_TOOL_PACKAGE = "org.pgledger.migrate1x..";

    /**
     * The name of a class or package that is named after 1.x, whatever the case of "legacy" and "1x" is. The "one-x" spelled
     * out is case-sensitive ({@code OneX}, {@code ONE_X}), because "onex" in lower case is inside ordinary words such as
     * {@code TransitionException}.
     */
    private static final String ONE_X_NAME = ".*(?:(?i:legacy|1x)|OneX|ONE_X|\\.onex[.$]).*";

    /** The text that names 1.x in a source or resource file (the one expression of the inventory of 1.x mentions). */
    private static final Pattern ONE_X_TEXT = Pattern.compile("LEGACY|[Ll]egacy|\\b1\\.x\\b|[^0-9a-z]1x|OneX|ONE_X|one-x");

    private static final Set<String> TEXT_FILE_TYPES = Set.of("java", "sql", "properties", "yaml", "yml", "html");

    private NoOneXRules() {
    }

    /** ARC-1X-01: no class has a name or a package that names 1.x. */
    public static ArchRule noClassNamedAfterOneX() {
        return noClasses().should().haveNameMatching(ONE_X_NAME);
    }

    /** ARC-1X-02: no class depends on the migration tool. */
    public static ArchRule noDependencyOnTheMigrationTool() {
        return noClasses().should().dependOnClassesThat().resideInAPackage(MIGRATION_TOOL_PACKAGE);
    }

    /**
     * ARC-1X-03: no Java, SQL, properties, YAML or HTML file under the given roots (a root that does not exist is
     * skipped) names 1.x. Each problem names the file, the line and the text found.
     */
    public static List<String> oneXText(Path... roots) {
        List<String> problems = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            for (Path file : textFiles(root)) {
                List<String> lines = lines(file);
                for (int i = 0; i < lines.size(); i++) {
                    var matcher = ONE_X_TEXT.matcher(lines.get(i));
                    if (matcher.find()) {
                        problems.add(file + ":" + (i + 1) + ": '" + matcher.group().strip() + "'");
                    }
                }
            }
        }
        return problems;
    }

    /** ARC-1X-03 for the module under test: its {@code src/main/java} and {@code src/main/resources} (the tests run in the module directory). */
    public static List<String> oneXTextInMainSources() {
        return oneXText(Path.of("src", "main", "java"), Path.of("src", "main", "resources"));
    }

    private static List<Path> textFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).filter(p -> TEXT_FILE_TYPES.contains(extension(p))).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
