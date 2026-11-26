// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/** The three hosts and the case sources built on them; one case is one invocation of a parameterized test. */
final class Hosts {

    /** The thirteen write tools of the external contract, written out here and not taken from the product. */
    static final List<String> WRITE_OPS = List.of(
            "issue_save", "spec_save", "task_save", "criterion_save", "transition", "record_deliverable",
            "record_case", "link", "correct", "batch", "review", "review_correct", "whoami");

    /**
     * The five attachment tools (ATA-021), written out here and not taken from the product. Their operation is the whole
     * canonical snake_case name, which has no {@code ledger_} prefix; their claim carries no operation key, like whoami.
     */
    static final List<String> ARTIFACT_OPS = List.of(
            "artifact_begin", "artifact_chunk", "artifact_finish", "artifact_status", "artifact_read");

    static final List<HostScenario> ALL = List.of(new ClaudeHost(), new CodexHost(), AntigravityHost.INSTANCE);

    private Hosts() {}

    /**
     * The canonical tool name an attestation is issued for: {@code ledger_<op>}, or the attachment tool's own name (an
     * operation that starts with {@code artifact} is a whole tool name, also the look-alikes the negative tests use).
     */
    static String canonical(String op) {
        return op.startsWith("artifact") ? op : "ledger_" + op;
    }

    /** Whether the claim of the tool names the operationKey argument: all but whoami and the attachment tools. */
    static boolean carriesOperationKey(String op) {
        return !"whoami".equals(op) && !ARTIFACT_OPS.contains(op);
    }

    /** Every host with every attachment tool. */
    static Stream<Arguments> withArtifactOps() {
        return cross(ARTIFACT_OPS);
    }

    static Stream<HostScenario> all() {
        return ALL.stream();
    }

    /** Every host with every write tool. */
    static Stream<Arguments> withWriteOps() {
        return cross(WRITE_OPS);
    }

    /** Every host with every given value: one case per pair. */
    static <T> Stream<Arguments> cross(Collection<? extends T> values) {
        return ALL.stream().flatMap(host -> values.stream()
                .map(value -> Arguments.argumentSet(host + " " + value, host, value)));
    }
}
