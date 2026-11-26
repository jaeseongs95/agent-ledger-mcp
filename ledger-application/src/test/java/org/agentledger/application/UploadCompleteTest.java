// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.application.attachment.AttachmentFlow;
import org.agentledger.application.attachment.AttachmentProperties;
import org.agentledger.application.attachment.AttachmentStore;
import org.agentledger.application.attachment.StorageKeys;
import org.agentledger.application.attachment.UploadCommands.Begun;
import org.agentledger.application.attachment.UploadService;
import org.agentledger.application.attachment.UploadService.ClosedFile;
import org.agentledger.application.attachment.UploadService.FinishPlan;
import org.agentledger.application.gate.WriteGate;
import org.agentledger.application.support.AttachmentContext;
import org.agentledger.application.support.LedgerAppContext;
import org.agentledger.application.support.Scenario;
import org.agentledger.application.support.Uploads;
import org.agentledger.database.repository.AttachmentFileRepository;
import org.agentledger.database.repository.AttachmentRepository;
import org.agentledger.database.repository.AttachmentStorageRepository;
import org.agentledger.database.repository.SqlBuilder;
import org.agentledger.database.repository.UploadStagingRepository;
import org.agentledger.domain.error.LedgerException;
import org.agentledger.domain.model.UploadStaging;
import org.agentledger.domain.rule.UploadState;

/**
 * R9-D0072: the completion of a bundle is one transaction, and a row that cannot be completed (it is gone, for instance removed by the
 * reaper after the plan) ends it with an error and a rollback. The transaction must not take its own earlier writes (the first row of
 * the bundle, completed a moment before) for a finish that somebody else committed.
 */
@AttachmentContext
class UploadCompleteTest {

    /** The repository of the staging rows that removes the second row of the bundle at the moment the second row is completed. */
    private static UploadStagingRepository reaperBeforeTheSecondRow(LedgerAppContext ctx) {
        AtomicInteger calls = new AtomicInteger();
        return new UploadStagingRepository(ctx.jdbc(), ctx.bean(SqlBuilder.class)) {
            @Override
            public boolean complete(String id, long finalStoredBytes, Instant updatedAt) {
                if (calls.incrementAndGet() == 2) {
                    ctx.jdbc().sql("DELETE FROM upload_staging WHERE id = ?").param(id).update();
                }
                return super.complete(id, finalStoredBytes, updatedAt);
            }
        };
    }

    @Test
    @DisplayName("R9-D0072: a row of the bundle that is gone while the bundle is completed fails the completion and rolls it back: no Finished without files, no half completed bundle")
    void r9D0072_aGoneRowFailsTheCompletion(Scenario s) {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        AttachmentStore store = s.bean(AttachmentStore.class);
        UploadService planner = s.bean(UploadService.class);
        byte[] first = Uploads.body(300, 72);
        byte[] second = Uploads.body(300, 73);
        Begun begun = flow.begin(actor, Uploads.beginBundle("op-r9d0072-" + Scenario.uuid(),
                List.of(Uploads.beginFile("a.md", "text/markdown", first), Uploads.beginFile("b.md", "text/markdown", second))));
        Uploads.send(flow, actor, begun.files().get(0).uploadId(), 0, first);
        Uploads.send(flow, actor, begun.files().get(1).uploadId(), 0, second);
        String attachmentId = begun.attachmentId();
        List<UploadStaging> rows = ((FinishPlan.Close) planner.planFinish(actor, attachmentId)).rows();
        List<ClosedFile> closed = new ArrayList<>();
        for (UploadStaging row : rows) {
            closed.add(new ClosedFile(row.id(), store.finish(row.storageKey(), row.keyId(), row.storedBytes(), row.nextSequence(),
                    row.declaredSize(), row.declaredSha256())));
        }
        UploadService completing = new UploadService(ctx.bean(WriteGate.class), reaperBeforeTheSecondRow(ctx),
                ctx.bean(AttachmentRepository.class), ctx.bean(AttachmentFileRepository.class),
                ctx.bean(AttachmentStorageRepository.class), ctx.bean(AttachmentProperties.class), ctx.bean(StorageKeys.class),
                ctx.bean(Clock.class));

        assertThatThrownBy(() -> ctx.tx(() -> completing.completeUpload(actor, attachmentId, List.copyOf(closed))))
                .as("the completion fails with an error; it never answers Finished with no files")
                .isInstanceOf(LedgerException.class);

        assertThat(ctx.bean(AttachmentRepository.class).findById(attachmentId)).as("no attachment row").isEmpty();
        assertThat(ctx.bean(AttachmentFileRepository.class).ofAttachment(attachmentId)).isEmpty();
        assertThat(Uploads.row(ctx, begun.files().get(0).uploadId()).state()).as("the first row was not completed for good")
                .isEqualTo(UploadState.STAGING);
    }
}
