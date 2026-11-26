// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.attestation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.veridger.testing.NoOneXRules;

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

    @Test
    @DisplayName("OPK-017 a root that does not exist is a problem for the retired request key rule too")
    void aMissingRootIsAProblemForTheRetiredNames() {
        assertThat(NoOneXRules.retiredRequestKeyText(tmp.resolve("no-such-folder"))).hasSize(1).first().asString()
                .contains("no-such-folder");
    }

    @Test
    @DisplayName("OPK-017 request_id, batch_request_id, RequestId, requestId and REQUEST_ID in a Java, SQL, properties, YAML or HTML file are found with file and line; the words 'request id', operationKey, OPERATION_CONFLICT and request_receipt, other file types and the migration tool's own roots (never scanned) are not")
    void retiredRequestKeyNamesAreFound() throws IOException {
        Files.writeString(tmp.resolve("A.java"), "class A {\n    String request_id;\n}\n");
        Files.writeString(tmp.resolve("b.sql"), "-- ok\nCREATE TABLE t (batch_request_id VARCHAR(36));\n");
        Files.writeString(tmp.resolve("c.properties"), "param.x=the request id of a call\nparam.y=uses RequestId\n");
        Files.writeString(tmp.resolve("E.java"), "record E(String requestId) {}\nenum F { REQUEST_ID }\n");
        Files.writeString(tmp.resolve("g.txt"), "request_id\n");
        Files.writeString(tmp.resolve("H.java"),
                "class H {\n    String operationKey = \"OPERATION_CONFLICT request_receipt\";\n}\n");

        assertThat(NoOneXRules.retiredRequestKeyText(tmp)).hasSize(5)
                .anyMatch(problem -> problem.contains("A.java:2") && problem.contains("'request_id'"))
                .anyMatch(problem -> problem.contains("b.sql:2") && problem.contains("'request_id'"))
                .anyMatch(problem -> problem.contains("c.properties:2") && problem.contains("'RequestId'"))
                .anyMatch(problem -> problem.contains("E.java:1") && problem.contains("'requestId'"))
                .anyMatch(problem -> problem.contains("E.java:2") && problem.contains("'REQUEST_ID'"));
    }
}
