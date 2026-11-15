package org.pgledger.hook;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The fifteen tools of the server that write and therefore carry the host attestation (the external contract): ten
 * ledger tools named {@code ledger_<operation>} and the five attachment tools named {@code artifact-<operation>}. Read
 * tools (ledger_get, ledger_history, ledger_search, ledger_defects, ...) are deliberately absent.
 *
 * <p>The nine ledger write tools carry the {@code operationKey} of the call in the claim. whoami and the attachment tools
 * do not: their claim's operation key is empty (the begin is made idempotent by its own {@code operationKey}, which the
 * digest of the arguments covers, and chunk and finish are bound to the upload by {@code uploadId}, they take no
 * {@code operationKey}).
 */
public enum WriteTool {
    SAVE("ledger_save", true),
    TRANSITION("ledger_transition", true),
    RECORD_FIX("ledger_record_fix", true),
    RECORD_CASE("ledger_record_case", true),
    LINK("ledger_link", true),
    CORRECT("ledger_correct", true),
    BATCH("ledger_batch", true),
    REVIEW("ledger_review", true),
    REVIEW_CORRECT("ledger_review_correct", true),
    WHOAMI("ledger_whoami", false),
    ARTIFACT_BEGIN("artifact-begin", false),
    ARTIFACT_CHUNK("artifact-chunk", false),
    ARTIFACT_FINISH("artifact-finish", false),
    ARTIFACT_STATUS("artifact-status", false),
    ARTIFACT_READ("artifact-read", false);

    private final String canonicalName;
    private final boolean carriesOperationKey;

    WriteTool(String canonicalName, boolean carriesOperationKey) {
        this.canonicalName = canonicalName;
        this.carriesOperationKey = carriesOperationKey;
    }

    /**
     * The canonical tool name of the server, {@code ledger_<operation>} or {@code artifact-<operation>}: what the
     * attestation is issued for.
     */
    public String canonicalName() {
        return canonicalName;
    }

    /** Whether the claim of the call names the {@code operationKey} argument of the call (otherwise the claim's key is empty). */
    boolean carriesOperationKey() {
        return carriesOperationKey;
    }

    /**
     * Regex alternation of all canonical names as ONE capturing group, for the tool name patterns of the host dialects:
     * the name is read from that group and checked again with {@link #ofName}.
     */
    static String alternation() {
        return Arrays.stream(values()).map(WriteTool::canonicalName).collect(Collectors.joining("|", "(", ")"));
    }

    /** The write tool of the canonical name (exactly one of the fifteen), if it is one. */
    static Optional<WriteTool> ofName(String found) {
        return Arrays.stream(values()).filter(tool -> Objects.equals(tool.canonicalName, found)).findFirst();
    }
}
