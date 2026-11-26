// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.attachment.AttachmentFlow;
import org.veridger.application.attachment.AttachmentKeys;
import org.veridger.application.attachment.AttachmentProperties;
import org.veridger.application.attachment.AttachmentRekey;
import org.veridger.application.attachment.AttachmentStartCheck;
import org.veridger.application.attachment.PrivateFiles;
import org.veridger.application.attachment.UploadCommands.Finished;
import org.veridger.application.support.AttachmentContext;
import org.veridger.application.support.Expect;
import org.veridger.application.support.LedgerAppContext;
import org.veridger.application.support.Scenario;
import org.veridger.application.support.Uploads;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;
import org.veridger.testing.TestOwners;
import org.springframework.test.context.TestPropertySource;

/**
 * ATA-014 (the start check): the start fails when a key file that a current row needs is missing, and the failure names the key id and never
 * the key; a key that only a replaced file needs does not stop the start (the check verifies the keys that the current rows and the open
 * uploads point to). The test deletes key files, so the context is a context of its own (the reaper interval below only makes the cache key
 * differ from the one of {@link AttachmentReaperTest}, which sets another value).
 */
@AttachmentContext
@TestPropertySource(properties = "ledger.attachments.reaper-interval=2m")
class AttachmentStartCheckTest {

    @Test
    @DisplayName("ATA-014: the start check fails when the key file of a current row is missing, naming the key id and never the key; after the body is written under the active key the old key file is not needed")
    void ata014_theStartCheckAndTheKeyFiles(Scenario s) throws IOException {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        AttachmentStartCheck startCheck = s.bean(AttachmentStartCheck.class);
        AttachmentProperties properties = s.bean(AttachmentProperties.class);
        startCheck.verify();

        String keyA = s.bean(AttachmentKeys.class).create();
        byte[] body = Uploads.body(300, 1);
        Finished finished = Uploads.uploadWithKey(ctx, flow, actor, keyA, body, 100);
        startCheck.verify();

        Path keyFile = properties.keyDirectory().resolve(keyA);
        byte[] keyBytes = Files.readAllBytes(keyFile);
        Files.delete(keyFile);
        assertThatThrownBy(startCheck::verify).isInstanceOfSatisfying(InfrastructureException.class, failure -> {
            assertThat(failure.code()).isEqualTo(InfrastructureCode.ARTIFACT_KEY_MISSING);
            assertThat(failure.args()).as("the key id and nothing else").isEqualTo(List.of(keyA));
            assertThat(Expect.message(failure)).contains(keyA).doesNotContain(HexFormat.of().formatHex(keyBytes));
            assertThat(failure.getMessage()).doesNotContain(HexFormat.of().formatHex(keyBytes));
        });
        Expect.failure(() -> flow.read(actor, finished.attachmentId(), 0, 0, 10).data(), InfrastructureCode.ARTIFACT_KEY_MISSING);

        // the key comes back (the same bytes), the body is written again under the active key, and the old key is not needed any more
        PrivateFiles files = s.bean(PrivateFiles.class);
        files.createFile(keyFile);
        Files.write(keyFile, keyBytes);
        startCheck.verify();
        assertThat(s.bean(AttachmentRekey.class).rekey(TestOwners.OWNER, keyA)).isEqualTo(new AttachmentRekey.Result(1, 0));
        Files.delete(keyFile);

        startCheck.verify();
        assertThat(Uploads.readAll(flow, actor, finished.attachmentId(), 100)).isEqualTo(body);
        assertThat(Uploads.currentStorage(ctx, finished.attachmentId()).keyId()).isEqualTo(properties.activeKeyId());
    }
}
