// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.docs;

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

    private static final String REPOSITORY_CHECKS = ".github/workflows/repository-checks.yml";

    @Test
    @DisplayName("R9-D0069 repository checks keep older runs on main and release branches and group runs by ref")
    void r9d0069_runsOfMainAndReleaseBranchesAreNotCancelled() {
        List<String> lines = Repository.read(REPOSITORY_CHECKS).lines().toList();
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
