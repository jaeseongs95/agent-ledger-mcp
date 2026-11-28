// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R9-D0069: the repository checks run for review-ready pull requests and are reused by release verification.
 */
class CiWorkflowTest {

    private static final String REPOSITORY_CHECKS = ".github/workflows/repository-checks.yml";
    private static final String REVIEW_READY = "    if: github.event_name != 'pull_request' || !github.event.pull_request.draft";

    @Test
    @DisplayName("Repository checks run for review-ready pull requests and reuse in release verification")
    void repositoryChecksRunForReviewReadyPullRequests() {
        List<String> lines = Repository.read(REPOSITORY_CHECKS).lines().toList();
        int triggers = lines.indexOf("on:");
        assertThat(triggers).isNotNegative();
        List<String> events = lines.subList(triggers + 1, lines.size()).stream().takeWhile(line -> !line.isBlank()).toList();

        assertThat(events).containsSubsequence("  pull_request:", "    types: [opened, reopened, ready_for_review]",
                "  workflow_dispatch:", "  workflow_call:").doesNotContain("  push:");
        String workflow = String.join("\n", lines);
        assertThat(workflow).contains("  ubuntu:\n" + REVIEW_READY, "  windows:\n" + REVIEW_READY);
        assertThat(Repository.read(".github/workflows/release-candidate-verification.yml"))
                .contains("uses: ./.github/workflows/repository-checks.yml", "coverage-artifacts: true");
    }

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
