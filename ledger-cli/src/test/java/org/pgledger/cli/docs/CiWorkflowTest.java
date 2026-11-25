// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R9-D0069: the continuous integration workflow is read as text (it runs on GitHub, never here). A newer push to a branch may
 * cancel the run of an older one only where nobody needs the older one's record: not on {@code main} and not on
 * {@code release/**}, where every commit is a candidate of a release and its check result is the evidence.
 */
class CiWorkflowTest {

    private static final String CI = ".github/workflows/ci.yml";

    @Test
    @DisplayName("R9-D0069 the concurrency group of ci.yml cancels an older run of the same ref only when the ref is neither main nor a release branch: cancel-in-progress is an expression that is false for refs/heads/main and for refs/heads/release/*, and the group still holds the ref")
    void r9d0069_runsOfMainAndReleaseBranchesAreNotCancelled() {
        List<String> lines = Repository.read(CI).lines().toList();
        int concurrency = lines.indexOf("concurrency:");
        assertThat(concurrency).as("the workflow has a concurrency group").isNotNegative();
        List<String> block = lines.subList(concurrency + 1, lines.size()).stream().takeWhile(line -> !line.isBlank()).toList();

        assertThat(block).anySatisfy(line -> assertThat(line.strip()).startsWith("group:").contains("github.ref"));
        String cancel = block.stream().map(String::strip).filter(line -> line.startsWith("cancel-in-progress:")).findFirst()
                .orElseThrow();
        assertThat(cancel).as("a constant true cancels the runs of every ref").isNotEqualTo("cancel-in-progress: true");
        assertThat(cancel).contains("${{").contains("github.ref != 'refs/heads/main'")
                .contains("!startsWith(github.ref, 'refs/heads/release/')");
    }
}
