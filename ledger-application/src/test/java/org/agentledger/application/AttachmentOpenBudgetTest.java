// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.application.attachment.AttachmentFlow;
import org.agentledger.application.attachment.UploadCommands.Begun;
import org.agentledger.application.support.AttachmentContext;
import org.agentledger.application.support.Expect;
import org.agentledger.application.support.Scenario;
import org.agentledger.application.support.Uploads;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.LedgerException;
import org.springframework.test.context.TestPropertySource;

/**
 * ATA-042, design test 20 (edition 9): the attachment budget counts the declared sizes of the open uploads too. With
 * {@code attachment-budget-bytes} 4 KiB, a kept attachment of 2 KiB and an open upload that declared 1 KiB, a begin of 2 KiB (2 + 1 + 2 is
 * over the budget) is refused with ARTIFACT_LIMIT and {@code details.limit} {@code attachment-budget}; a begin of 1 KiB (exactly the
 * budget) still fits; what is kept still reads. (The staging budget has its own value, so that this test has a context of its own.)
 */
@AttachmentContext
@TestPropertySource(properties = {"ledger.attachments.max-file-bytes=2KB", "ledger.attachments.staging-budget-bytes=3KB",
        "ledger.attachments.attachment-budget-bytes=4KB"})
class AttachmentOpenBudgetTest {

    private static final int KIB = 1024;

    @Test
    @DisplayName("ATA-042: kept 2 KiB + an open upload of 1 KiB + a new begin of 2 KiB is over the attachment budget of 4 KiB: ARTIFACT_LIMIT (attachment-budget); a begin of 1 KiB fits")
    void ata042_theBudgetCountsTheOpenUploads(Scenario s) {
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] keptBody = Uploads.body(2 * KIB, 61);
        String kept = Uploads.upload(flow, actor, keptBody, 512).attachmentId();
        Begun open = flow.begin(actor, Uploads.begin(Uploads.body(KIB, 62)));
        long rows = s.count("SELECT COUNT(*) FROM upload_staging WHERE owner = ?", actor);

        LedgerException refused = Expect.failure(() -> flow.begin(actor, Uploads.begin(Uploads.body(2 * KIB, 63))),
                ConflictCode.ARTIFACT_LIMIT);

        assertThat(refused.details().get("limit")).containsExactly("attachment-budget");
        assertThat(s.count("SELECT COUNT(*) FROM upload_staging WHERE owner = ?", actor)).as("no row for the refused begin")
                .isEqualTo(rows);
        assertThat(flow.uploadStatus(actor, open.files().getFirst().uploadId()).state())
                .as("the open upload is not touched").isEqualTo("STAGING");
        assertThat(Uploads.readAll(flow, actor, kept, 300)).as("what is kept still reads").isEqualTo(keptBody);
        assertThat(flow.begin(actor, Uploads.begin(Uploads.body(KIB, 64))).attachmentId())
                .as("2 KiB kept, 1 KiB open and 1 KiB declared is exactly the budget").isNotBlank();
    }
}
