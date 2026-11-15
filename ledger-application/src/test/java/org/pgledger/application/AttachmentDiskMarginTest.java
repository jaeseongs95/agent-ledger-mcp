package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.attachment.AttachmentFlow;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.AttachmentStore;
import org.pgledger.application.support.AttachmentContext;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.support.StoreFixture;
import org.pgledger.application.support.Uploads;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.StorageKey;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.unit.DataSize;

/**
 * ATA-039 (design edition 8): {@code disk-margin-bytes}. The margin is set larger than any disk, so that "usable - size < margin" holds
 * without a disk that is really full: a begin and a chunk are refused with ARTIFACT_STORAGE_FULL, its details name the three numbers,
 * the caller learns nothing of the disk before the gate has passed, and nothing is written.
 */
@AttachmentContext
@TestPropertySource(properties = {"ledger.attachments.disk-margin-bytes=1000000GB"})
class AttachmentDiskMarginTest {

    @Test
    @DisplayName("ATA-039: a begin with a margin that the disk cannot keep is refused with ARTIFACT_STORAGE_FULL (the three numbers in the details), no row; a caller without an attestation gets the gate's answer, not the disk's")
    void ata039_beginOnAFullDisk(Scenario s) {
        String actor = s.actorWith("defect");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);

        LedgerException full = Expect.failure(() -> flow.begin(actor, Uploads.begin(Uploads.body(600, 51))),
                ConflictCode.ARTIFACT_STORAGE_FULL);

        assertThat(full.details()).containsKeys("requestedBytes", "usableBytes", "marginBytes");
        assertThat(full.details().get("requestedBytes")).containsExactly("600");
        assertThat(Long.parseLong(full.details().get("marginBytes").getFirst())).isEqualTo(DataSize.ofGigabytes(1_000_000).toBytes());
        assertThat(s.count("SELECT COUNT(*) FROM upload_staging WHERE owner = ?", actor)).as("no row").isZero();
        Expect.failure(() -> flow.begin(null, Uploads.begin(Uploads.body(600, 52))), TrustCode.HOST_ATTESTATION_REQUIRED);
    }

    @Test
    @DisplayName("ATA-039: a chunk with a margin that the disk cannot keep is refused with ARTIFACT_STORAGE_FULL and nothing is appended; the same call with the default margin passes")
    void ata039_chunkOnAFullDisk() throws Exception {
        StoreFixture fixture = StoreFixture.create();
        try {
            AttachmentProperties current = fixture.properties();
            AttachmentProperties full = new AttachmentProperties(current.directory(), current.keyDirectory(),
                    current.activeKeyId(), current.maxFileBytes(), current.stagingBudgetBytes(), current.attachmentBudgetBytes(),
                    current.maxOpenPerOwner(), DataSize.ofGigabytes(1_000_000), current.uploadTtl(), current.orphanGrace(),
                    current.reaperInterval());
            AttachmentStore refusing = new AttachmentStore(full, fixture.files(), fixture.keys(), fixture.cipher());
            StorageKey key = fixture.storageKeys().next();
            byte[] chunk = Uploads.body(300, 53);

            assertThatThrownBy(() -> refusing.append(key, fixture.keyId(), 0, 0, chunk, Sha256.ofBytes(chunk)))
                    .isInstanceOfSatisfying(ConflictException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(ConflictCode.ARTIFACT_STORAGE_FULL);
                        assertThat(failure.details().get("requestedBytes")).containsExactly(Long.toString(300 + 32));
                    });
            Path file = fixture.fileOf(key);
            assertThat(!Files.exists(file) || Files.size(file) == 0).as("nothing was appended").isTrue();

            long stored = fixture.store().append(key, fixture.keyId(), 0, 0, chunk, Sha256.ofBytes(chunk));
            assertThat(stored).as("the default margin of 64 MB leaves room on the disk of the test").isEqualTo(300 + 32);
        } finally {
            fixture.close();
        }
    }
}
