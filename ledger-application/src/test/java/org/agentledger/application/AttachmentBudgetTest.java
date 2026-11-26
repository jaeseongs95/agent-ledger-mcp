// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.application.attachment.AttachmentFlow;
import org.agentledger.application.support.AttachmentContext;
import org.agentledger.application.support.Expect;
import org.agentledger.application.support.Scenario;
import org.agentledger.application.support.Uploads;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.LedgerException;
import org.springframework.test.context.TestPropertySource;

/**
 * ATA-030: {@code attachment-budget-bytes} 4 KiB (and {@code max-file-bytes} 2 KiB, so that a begin of 2 KiB is under the file limit) with
 * kept attachments of 3 KiB. A begin that would pass the budget is refused with ARTIFACT_LIMIT and no row; what is kept is neither deleted nor
 * made unreadable.
 */
@AttachmentContext
@TestPropertySource(properties = {"ledger.attachments.max-file-bytes=2KB", "ledger.attachments.attachment-budget-bytes=4KB"})
class AttachmentBudgetTest {

    private static final int KIB = 1024;

    @Test
    @DisplayName("ATA-030: with kept attachments of 3 KiB a begin of 2 KiB is refused with ARTIFACT_LIMIT and no row; the kept attachments still read and nothing is deleted; a begin of 1 KiB still fits")
    void ata030_theBudgetOfTheKeptAttachments(Scenario s) {
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        List<byte[]> bodies = List.of(Uploads.body(KIB, 11), Uploads.body(KIB, 12), Uploads.body(KIB, 13));
        List<String> kept = bodies.stream().map(body -> Uploads.upload(flow, actor, body, 512).attachmentId()).toList();
        long files = s.count("SELECT COUNT(*) FROM attachment_file");
        long rows = s.count("SELECT COUNT(*) FROM attachment");
        assertThat(rows).isEqualTo(3);

        LedgerException refused = Expect.failure(() -> flow.begin(actor, Uploads.begin(Uploads.body(2 * KIB, 14))),
                ConflictCode.ARTIFACT_LIMIT);

        assertThat(refused.args()).anyMatch(arg -> arg.contains("kept attachments"));
        assertThat(refused.details().get("limit")).as("ATA-040: the detail names the limit").containsExactly("attachment-budget");
        assertThat(s.count("SELECT COUNT(*) FROM upload_staging WHERE owner = ? AND state = 'STAGING'", actor)).as("no row").isZero();
        assertThat(s.count("SELECT COUNT(*) FROM attachment")).as("nothing is deleted").isEqualTo(rows);
        assertThat(s.count("SELECT COUNT(*) FROM attachment_file")).isEqualTo(files);
        for (int i = 0; i < kept.size(); i++) {
            assertThat(Uploads.readAll(flow, actor, kept.get(i), 300)).as("kept attachment %d still reads", i).isEqualTo(bodies.get(i));
        }
        assertThat(flow.begin(actor, Uploads.begin(Uploads.body(KIB, 15))).attachmentId())
                .as("3 KiB kept and 1 KiB declared is the budget")
                .isNotBlank();
    }
}
