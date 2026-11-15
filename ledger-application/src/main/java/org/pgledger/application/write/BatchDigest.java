package org.pgledger.application.write;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.Patch;
import org.pgledger.domain.value.Sha256;

/**
 * The digest of the whole content of a batch, which the receipt of the batch key compares: the operations in their order, each
 * with its kind and every value as the caller sent it. The same key with the same operations is the same batch again; an
 * operation added, removed, changed or moved, or another tool under the key, is another request (OPERATION_CONFLICT). A key of
 * {@code fields} that is written as a cleared field is told apart from a key that is left out.
 */
final class BatchDigest {

    private BatchDigest() {
    }

    static Sha256 of(List<BatchOpInput> ops) {
        List<CanonicalPayload> items = new ArrayList<>();
        for (BatchOpInput op : ops) {
            items.add(operation(op));
        }
        return CanonicalPayload.digest("BATCH", CanonicalPayload.Fields.empty().with("ops",
                new CanonicalPayload.Items(items)));
    }

    private static CanonicalPayload operation(BatchOpInput op) {
        return switch (op) {
            case BatchOpInput.Save s -> CanonicalPayload.Fields.empty().text("op", "save").text("entity", s.entity())
                    .with("fields", fields(s.fields())).text("reason", s.reason()).text("id", s.id())
                    .text("expected_version", text(s.expectedVersion()));
            case BatchOpInput.Transition t -> CanonicalPayload.Fields.empty().text("op", "transition")
                    .text("entity", t.entity()).text("id", t.id()).text("expected_version", text(t.expectedVersion()))
                    .text("to", t.to()).text("reason", t.reason()).text("evidence_uri", t.evidenceUri())
                    .text("evidence_sha256", t.evidenceSha256()).text("merged_into", t.mergedInto())
                    .text("attachment_id", t.attachmentId());
            case BatchOpInput.RecordFix f -> CanonicalPayload.Fields.empty().text("op", "record_fix")
                    .text("defect_id", f.defectId()).text("repo", f.repo()).text("commit_sha", f.commitSha())
                    .text("reason", f.reason()).text("branch", f.branch()).text("ci_ref", f.ciRef())
                    .text("ci_conclusion", f.ciConclusion()).text("ci_completed_at", f.ciCompletedAt())
                    .text("mark_source_fixed", text(f.markSourceFixed())).text("attachment_id", f.attachmentId());
            case BatchOpInput.RecordCase c -> CanonicalPayload.Fields.empty().text("op", "record_case")
                    .text("defect_id", c.defectId()).text("observation", c.observation()).text("reason", c.reason())
                    .text("evidence", c.evidence()).text("user_quote", c.userQuote())
                    .text("observed_at", c.observedAt()).text("attachment_id", c.attachmentId());
            case BatchOpInput.Link l -> CanonicalPayload.Fields.empty().text("op", "link").text("kind", l.kind())
                    .text("a", l.a()).text("b", l.b()).text("reason", l.reason()).text("remove", text(l.remove()));
            case BatchOpInput.Correct c -> CanonicalPayload.Fields.empty().text("op", "correct")
                    .text("history_id", text(c.historyId())).text("reason", c.reason())
                    .text("attachment_id", c.attachmentId());
        };
    }

    private static CanonicalPayload fields(FieldsInput input) {
        if (input == null) {
            return null;
        }
        CanonicalPayload.Fields fields = CanonicalPayload.Fields.empty();
        for (Map.Entry<String, Patch<String>> key : input.mentioned().entrySet()) {
            String value = key.getValue().orNull();
            // a field written as cleared has an empty list, a field with a text has the text
            fields = value == null ? fields.with(key.getKey(), new CanonicalPayload.Items(List.of()))
                    : fields.text(key.getKey(), value);
        }
        return fields;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
