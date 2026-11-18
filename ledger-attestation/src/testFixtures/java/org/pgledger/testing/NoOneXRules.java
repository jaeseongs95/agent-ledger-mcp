// SPDX-License-Identifier: AGPL-3.0-or-later

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
 * round. Each module of the body checks the same three rules on its own classes and sources. The fourth rule (OPK-017)
 * keeps the retired 1.x names of the request key out of the same sources.
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

    /** The names of the retired request key (OPK-017); case-sensitive, so the words "request id" and "OPERATION_CONFLICT" stay free. */
    private static final Pattern RETIRED_REQUEST_KEY = Pattern.compile("request_id|[Rr]equestId|REQUEST_ID");

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
     * ARC-1X-03: no Java, SQL, properties, YAML or HTML file under the given roots names 1.x. Each problem names the file, the
     * line and the text found; a root that does not exist is a problem too (a rule that finds nothing to scan checks nothing).
     */
    public static List<String> oneXText(Path... roots) {
        return scan(ONE_X_TEXT, roots);
    }

    /**
     * ARC-1X-03 for the module under test: its {@code src/main/java}, which has to be there, and its {@code src/main/resources}
     * when it has them (the tests run in the module directory).
     */
    public static List<String> oneXTextInMainSources() {
        return oneXText(mainRoots());
    }

    /**
     * OPK-017: no Java, SQL, properties (the message bundles), YAML or HTML file under the given roots uses a name of the
     * retired request key: {@code request_id} (also in {@code batch_request_id}), {@code RequestId} / {@code requestId} or
     * {@code REQUEST_ID}. The key of a write is the {@code operationKey} (D14); the 1.x names live only in the migration
     * tool, which does not call this rule. The ordinary words "request id" (a space) are not matched, nor is the kept
     * error code {@code OPERATION_CONFLICT} or the table name {@code request_receipt}. A root that does not exist is a
     * problem too.
     */
    public static List<String> retiredRequestKeyText(Path... roots) {
        return scan(RETIRED_REQUEST_KEY, roots);
    }

    /** OPK-017 for the module under test: the same roots as {@link #oneXTextInMainSources()}. */
    public static List<String> retiredRequestKeyTextInMainSources() {
        return retiredRequestKeyText(mainRoots());
    }

    private static Path[] mainRoots() {
        Path resources = Path.of("src", "main", "resources");
        return Files.isDirectory(resources) ? new Path[] {Path.of("src", "main", "java"), resources}
                : new Path[] {Path.of("src", "main", "java")};
    }

    private static List<String> scan(Pattern pattern, Path... roots) {
        List<String> problems = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                // a rule that cannot find what it scans checks nothing: that is a failure, never a pass
                problems.add(root + ": the folder to scan does not exist (the test runs in the module folder)");
                continue;
            }
            for (Path file : textFiles(root)) {
                List<String> lines = lines(file);
                for (int i = 0; i < lines.size(); i++) {
                    var matcher = pattern.matcher(lines.get(i));
                    if (matcher.find()) {
                        problems.add(file + ":" + (i + 1) + ": '" + matcher.group().strip() + "'");
                    }
                }
            }
        }
        return problems;
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
