// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.application.read.Cursors;
import org.pgledger.database.repository.KeywordMatch;
import org.pgledger.database.repository.SearchQueryRepository.Filter;
import org.pgledger.database.repository.SearchQueryRepository.Kind;
import org.pgledger.domain.value.PageCursor;

/**
 * R9-D0078: the cursor of a search page is read by the server that is started after the one that made it. The digest of the
 * filters in the cursor came from the text of the filter, and the text of a {@code Set.copyOf} lists the elements in an
 * order that is chosen again by every start of the JVM, so a restart made a good cursor {@code CURSOR_OTHER_FILTERS}. No
 * thread of one JVM can show that, so the cursor is made by a second JVM (this class is its main) and read by this one.
 */
class SearchCursorRestartTest {

    /** More than one element in both sets, so that two starts of the JVM list them in another order. */
    private static Filter filter() {
        return new Filter(null, Set.of(Kind.values()), List.of("alpha", "beta"), KeywordMatch.ANY,
                Set.of("OPEN", "MERGED", "VERIFIED", "CANCELLED", "NOT_AN_ISSUE", "DISPUTED"), null, null);
    }

    /** The second JVM: prints the cursor of the first page of the search that the test asks for. */
    public static void main(String[] args) {
        System.out.println(new Cursors().encode(PageCursor.Kind.SEARCH, filter(), "2026-11-05T00:00:00Z|issue", "X-I-0001"));
    }

    @Test
    @DisplayName("R9-D0078: a search cursor made by another start of the JVM is read for the same filters")
    void aCursorOfAnotherJvmIsReadForTheSameFilters(@TempDir Path dir) throws Exception {
        Path arguments = dir.resolve("jvm.args");
        Files.writeString(arguments, "-cp\n\"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"\n"
                + SearchCursorRestartTest.class.getName() + "\n", StandardCharsets.UTF_8);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "@" + arguments).redirectErrorStream(true)
                .redirectOutput(dir.resolve("out.txt").toFile()).start();
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("the second JVM ends").isTrue();
        List<String> lines = Files.readAllLines(dir.resolve("out.txt"), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(String.join("\n", lines)).isZero();
        String cursor = lines.getLast();

        var position = new Cursors().decode(cursor, PageCursor.Kind.SEARCH, filter());

        assertThat(position).isPresent();
        assertThat(position.orElseThrow().id()).isEqualTo("X-I-0001");
    }
}
