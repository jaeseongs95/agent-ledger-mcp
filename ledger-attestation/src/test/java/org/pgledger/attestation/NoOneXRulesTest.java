package org.pgledger.attestation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.testing.NoOneXRules;

/** ARC-1X-03 on itself: the text rule finds what it is made for and never passes because a root is missing. */
class NoOneXRulesTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("ARC-1X-03 a root that does not exist is a problem, not a silent pass (a rule that cannot find the sources checks nothing)")
    void aMissingRootIsAProblem() {
        assertThat(NoOneXRules.oneXText(tmp.resolve("no-such-folder"))).hasSize(1).first().asString().contains("no-such-folder");
    }

    @Test
    @DisplayName("ARC-1X-03 a Java, SQL, properties, YAML or HTML file that names 1.x is found with its file and line; other file types and clean files are not")
    void textThatNamesOneXIsFound() throws IOException {
        Files.writeString(tmp.resolve("A.java"), "class A {\n    // as 1.x did\n}\n");
        Files.writeString(tmp.resolve("b.sql"), "-- the legacy table\n");
        Files.writeString(tmp.resolve("c.txt"), "legacy\n");
        Files.writeString(tmp.resolve("D.java"), "class D {\n    // a TransitionException\n}\n");

        assertThat(NoOneXRules.oneXText(tmp)).hasSize(2).anyMatch(problem -> problem.contains("A.java:2"))
                .anyMatch(problem -> problem.contains("b.sql:1"));
    }
}
