package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.attachment.AttachmentFlow;
import org.pgledger.application.attachment.UploadCommands.Begun;
import org.pgledger.application.attachment.UploadCommands.Finished;
import org.pgledger.application.support.AttachmentContext;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.support.Uploads;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.LedgerException;
import org.springframework.test.context.TestPropertySource;

/**
 * ATA-010: {@code max-file-bytes} 1 KiB, {@code staging-budget-bytes} 2 KiB and {@code max-open-per-owner} 2 (the settings of the
 * context). A begin over a limit or the budget is refused with ARTIFACT_LIMIT and no row, and nothing is cut: what was accepted keeps its
 * declared size.
 */
@AttachmentContext
@TestPropertySource(properties = {"ledger.attachments.max-file-bytes=1KB", "ledger.attachments.staging-budget-bytes=2KB",
        "ledger.attachments.max-open-per-owner=2"})
class AttachmentLimitsTest {

    private static final int KIB = 1024;

    private static boolean mentions(LedgerException failure, String text) {
        return failure.args().stream().anyMatch(arg -> arg.contains(text));
    }

    @Test
    @DisplayName("ATA-010: a file over the limit, a third open upload and a begin over the budget of the open uploads are refused with ARTIFACT_LIMIT and no row; the accepted ones keep their size")
    void ata010_limitsAndTheStagingBudget(Scenario s) {
        String actor = s.actorWith("issue");
        String other = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] first = Uploads.body(KIB, 1);
        byte[] second = Uploads.body(KIB, 2);

        LedgerException tooLarge = Expect.failure(() -> flow.begin(actor, Uploads.begin(Uploads.body(2 * KIB, 3))),
                ConflictCode.ARTIFACT_LIMIT);
        Expect.failure(() -> flow.begin(actor, Uploads.begin(Uploads.body(KIB + 1, 4))), ConflictCode.ARTIFACT_LIMIT);
        assertThat(mentions(tooLarge, "larger")).isTrue();
        assertThat(tooLarge.details().get("limit")).as("ATA-040: the detail names the limit").containsExactly("file-size");
        assertThat(s.count("SELECT COUNT(*) FROM upload_staging WHERE owner = ?", actor)).as("no row for a refused begin").isZero();

        Begun one = flow.begin(actor, Uploads.begin(first));
        Begun two = flow.begin(actor, Uploads.begin(second));
        LedgerException third = Expect.failure(() -> flow.begin(actor, Uploads.begin(Uploads.body(KIB, 5))),
                ConflictCode.ARTIFACT_LIMIT);
        assertThat(mentions(third, "too many")).isTrue();
        assertThat(third.details().get("limit")).containsExactly("open-uploads");
        LedgerException overBudget = Expect.failure(() -> flow.begin(other, Uploads.begin(Uploads.body(KIB, 6))),
                ConflictCode.ARTIFACT_LIMIT);
        assertThat(mentions(overBudget, "budget")).as("another author, no upload of its own, but the budget is used").isTrue();
        assertThat(overBudget.details().get("limit")).containsExactly("staging-budget");

        assertThat(s.count("SELECT COUNT(*) FROM upload_staging WHERE owner = ?", actor)).isEqualTo(2);
        assertThat(s.count("SELECT COUNT(*) FROM upload_staging WHERE owner = ?", other)).isZero();
        assertThat(flow.uploadStatus(actor, one.files().getFirst().uploadId()).size()).as("nothing was cut").isEqualTo(KIB);
        assertThat(flow.uploadStatus(actor, two.files().getFirst().uploadId()).size()).isEqualTo(KIB);

        // an upload that is finished is no longer open: its budget and its place are free again
        Uploads.sendParts(flow, actor, one.files().getFirst().uploadId(), Uploads.parts(first, 512), 0);
        Finished finished = flow.finish(actor, one.attachmentId());
        assertThat(finished.files().getFirst().size()).isEqualTo(KIB);
        assertThat(flow.begin(other, Uploads.begin(Uploads.body(KIB, 7))).attachmentId()).isNotBlank();
        Expect.failure(() -> flow.begin(other, Uploads.begin(Uploads.body(KIB, 8))), ConflictCode.ARTIFACT_LIMIT);
    }
}
