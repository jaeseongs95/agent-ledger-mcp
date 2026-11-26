// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.veridger.domain.value.CanonicalPayload;
import org.veridger.domain.value.Patch;
import org.veridger.domain.value.Sha256;

/**
 * Canonical digest of all operations in a {@code ledger_batch} request for receipt idempotency.
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
            case BatchOpInput.RecordDeliverable d -> CanonicalPayload.Fields.empty().text("op", "record_deliverable")
                    .text("target_entity", d.targetEntity()).text("target_id", d.targetId()).text("kind", d.kind())
                    .text("deliverable_ref", d.deliverableRef()).text("reason", d.reason())
                    .text("location", d.location()).text("summary", d.summary()).text("check_ref", d.checkRef())
                    .text("check_result", d.checkResult()).text("checked_at", d.checkedAt())
                    .text("mark_done", text(d.markDone())).text("attachment_id", d.attachmentId());
            case BatchOpInput.RecordCase c -> CanonicalPayload.Fields.empty().text("op", "record_case")
                    .text("issue_id", c.issueId()).text("observation", c.observation()).text("reason", c.reason())
                    .text("evidence", c.evidence()).text("source_quote", c.sourceQuote())
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
            fields = value == null ? fields.with(key.getKey(), new CanonicalPayload.Items(List.of()))
                    : fields.text(key.getKey(), value);
        }
        return fields;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
