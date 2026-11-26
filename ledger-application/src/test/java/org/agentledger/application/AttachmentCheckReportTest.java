// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.application.attachment.AttachmentCheck;
import org.agentledger.application.attachment.AttachmentFlow;
import org.agentledger.application.attachment.AttachmentKeys;
import org.agentledger.application.attachment.AttachmentMaintenanceService;
import org.agentledger.application.attachment.AttachmentProperties;
import org.agentledger.application.attachment.AttachmentStore;
import org.agentledger.application.attachment.PrivateFiles;
import org.agentledger.application.attachment.SegmentCipher;
import org.agentledger.application.attachment.StorageKeys;
import org.agentledger.application.support.AttachmentContext;
import org.agentledger.application.support.AttachmentSettings;
import org.agentledger.application.support.LedgerAppContext;
import org.agentledger.application.support.Scenario;
import org.agentledger.application.support.TestTrees;
import org.agentledger.application.support.Uploads;
import org.agentledger.domain.error.InfrastructureCode;
import org.agentledger.domain.error.InfrastructureException;
import org.agentledger.domain.value.Sha256;
import org.agentledger.domain.value.StorageKey;
import org.agentledger.testing.TestOwners;
import org.springframework.test.context.TestPropertySource;

/**
 * R9-D0073 and R9-D0076 on the check of the attachments. The context is a context of its own (the grace below is not the default), so
 * that the files of these tests are in no other test's folder.
 */
@AttachmentContext
@TestPropertySource(properties = "ledger.attachments.orphan-grace=23h")
class AttachmentCheckReportTest {

    @Test
    @DisplayName("R9-D0073: the purge never deletes a quarantined file whose row exists again: it is moved back, and only the files no row names are deleted")
    void r9D0073_thePurgeKeepsTheFileOfALiveRow(Scenario s) {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        AttachmentCheck check = s.bean(AttachmentCheck.class);
        AttachmentStore store = s.bean(AttachmentStore.class);
        store.prepare();
        byte[] body = Uploads.body(300, 7301);
        String live = Uploads.upload(flow, actor, body, 100).attachmentId();
        StorageKey liveKey = Uploads.currentStorage(ctx, live).storageKey();
        // the state after a restore of an older backup and then of the database that has the attachment: the file of a row is in orphans/
        store.quarantine(liveKey);
        StorageKey stray = ctx.bean(StorageKeys.class).next();
        byte[] chunk = Uploads.body(40, 7302);
        store.append(stray, ctx.bean(AttachmentKeys.class).active().id(), 0, 0, chunk, Sha256.ofBytes(chunk));
        store.quarantine(stray);
        assertThat(Uploads.quarantinedFileOf(ctx, liveKey)).exists();

        int deleted = check.purgeQuarantine(TestOwners.OWNER);

        assertThat(deleted).as("only the file that no row names is deleted").isEqualTo(1);
        assertThat(Uploads.quarantinedFileOf(ctx, stray)).doesNotExist();
        check.run(true);
        assertThat(Uploads.readAll(flow, actor, live, 1000)).as("the live attachment still reads after the purge and the check")
                .isEqualTo(body);
    }

    @Test
    @DisplayName("R9-D0076: a key that is missing is ARTIFACT_KEY_MISSING and stops the check; it is never reported as a damaged file")
    void r9D0076_aMissingKeyIsNotADamagedFile(Scenario s) {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        String id = Uploads.upload(flow, actor, Uploads.body(300, 7601), 100).attachmentId();
        AttachmentProperties real = s.bean(AttachmentProperties.class);
        Path noKeys = TestTrees.create("ledger-no-keys-");
        try {
            AttachmentProperties lost = AttachmentSettings.properties(real.directory(), noKeys, real.activeKeyId());
            PrivateFiles files = new PrivateFiles();
            SecureRandom random = new SecureRandom();
            AttachmentStore withoutKeys = new AttachmentStore(lost, files, new AttachmentKeys(lost, files, random),
                    new SegmentCipher(random));
            AttachmentCheck check = new AttachmentCheck(s.bean(AttachmentMaintenanceService.class), withoutKeys, lost,
                    ctx.bean(Clock.class));

            assertThatThrownBy(() -> check.run(false)).isInstanceOfSatisfying(InfrastructureException.class,
                    failure -> assertThat(failure.code()).isEqualTo(InfrastructureCode.ARTIFACT_KEY_MISSING));
        } finally {
            TestTrees.delete(noKeys);
        }
        assertThat(s.bean(AttachmentCheck.class).run(false).damaged()).as("with its keys the same attachment is not damaged")
                .doesNotContain(id);
    }
}
