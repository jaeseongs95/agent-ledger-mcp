package org.pgledger.hook;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/** The three hosts and the case sources built on them; one case is one invocation of a parameterized test. */
final class Hosts {

    /** The ten write tools of the external contract, written out here and not taken from the product. */
    static final List<String> WRITE_OPS = List.of(
            "save", "transition", "record_fix", "record_case", "link", "correct", "batch", "review", "review_correct", "whoami");

    /**
     * The five attachment tools (ATA-021), written out here and not taken from the product. Their operation is the whole
     * canonical name, which is hyphenated and has no {@code ledger_} prefix; they carry no request id, like whoami.
     */
    static final List<String> ARTIFACT_OPS = List.of(
            "artifact-begin", "artifact-chunk", "artifact-finish", "artifact-status", "artifact-read");

    static final List<HostScenario> ALL = List.of(new ClaudeHost(), new CodexHost(), AntigravityHost.INSTANCE);

    private Hosts() {}

    /**
     * The canonical tool name an attestation is issued for: {@code ledger_<op>}, or the attachment tool's own name (an
     * operation that starts with {@code artifact} is a whole tool name, also the look-alikes the negative tests use).
     */
    static String canonical(String op) {
        return op.startsWith("artifact") ? op : "ledger_" + op;
    }

    /** Whether the call of the tool has a request id: all but whoami and the attachment tools. */
    static boolean hasRequestId(String op) {
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
