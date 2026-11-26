// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.application.attachment.AttachmentFlow;
import org.agentledger.application.attachment.AttachmentProperties;
import org.agentledger.application.attachment.AttachmentStore;
import org.agentledger.application.attachment.StorageKeys;
import org.agentledger.application.attachment.UploadCommands.Begin;
import org.agentledger.application.attachment.UploadCommands.BeginFile;
import org.agentledger.application.attachment.UploadCommands.Begun;
import org.agentledger.application.support.AttachmentContext;
import org.agentledger.application.support.Expect;
import org.agentledger.application.support.AttachmentSettings;
import org.agentledger.application.support.Scenario;
import org.agentledger.application.support.StoreFixture;
import org.agentledger.application.support.TestTrees;
import org.agentledger.application.support.Uploads;
import org.agentledger.database.repository.UploadStagingRepository;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.ConflictException;
import org.agentledger.domain.error.InfrastructureCode;
import org.agentledger.domain.error.InfrastructureException;
import org.agentledger.domain.error.LedgerException;
import org.agentledger.domain.error.TrustCode;
import org.agentledger.domain.error.ValidationCode;
import org.agentledger.domain.model.UploadStaging;
import org.agentledger.domain.rule.UploadState;
import org.agentledger.domain.value.AuthorId;
import org.agentledger.domain.value.Sha256;
import org.agentledger.domain.value.StorageKey;
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
        String actor = s.actorWith("issue");
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
    @DisplayName("R5-F3: on a full disk, an idempotent begin replay succeeds without touching disk, and invalid or over-limit requests fail VALIDATION_FAILED/ARTIFACT_LIMIT without creating the attachment directory")
    void r5F3_lowDiskIdempotentReplayAndInvalidOrOverLimitBegin(Scenario s) throws Exception {
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        AttachmentProperties properties = s.bean(AttachmentProperties.class);
        Path dir = properties.directory();
        TestTrees.delete(dir);
        assertThat(dir).doesNotExist();

        // 1) Invalid begin fails VALIDATION_FAILED without touching disk or creating directory
        Expect.failure(() -> flow.begin(actor, new Begin(" ", List.of(), null)), ValidationCode.VALIDATION_FAILED);
        assertThat(dir).as("invalid begin must not create attachment directory").doesNotExist();

        // 2) Over-limit (100 GB > max-file-bytes) fails ARTIFACT_LIMIT without touching disk or creating directory
        Begin huge = new Begin("r5f3-huge-" + Scenario.uuid(),
                List.of(new BeginFile("huge.bin", "application/octet-stream", 100_000_000_000L, "a".repeat(64))), null);
        Expect.failure(() -> flow.begin(actor, huge), ConflictCode.ARTIFACT_LIMIT);
        assertThat(dir).as("over-limit begin must not create attachment directory").doesNotExist();

        // 3) Existing begun bundle replays idempotently on a full disk instead of failing ARTIFACT_STORAGE_FULL
        String opKey = "r5f3-replay-" + Scenario.uuid();
        byte[] body = Uploads.body(512, 55);
        Begin replayCmd = Uploads.begin(opKey, body);
        BeginFile declared = replayCmd.files().getFirst();
        String attachmentId = UUID.randomUUID().toString();
        String uploadId = UUID.randomUUID().toString();
        Instant now = s.bean(Clock.class).instant().truncatedTo(ChronoUnit.MILLIS);
        Instant expiresAt = now.plus(properties.uploadTtl());
        s.ctx().tx(() -> {
            s.bean(UploadStagingRepository.class).insert(new UploadStaging(
                    uploadId, attachmentId, 0, AuthorId.of(actor), opKey, declared.fileName(), declared.mediaType(),
                    declared.size(), Sha256.of(declared.sha256()), 0L, 0L, 0L, null, s.bean(StorageKeys.class).next(),
                    properties.activeKeyId(), UploadState.STAGING, expiresAt, now, now));
            return null;
        });

        Begun replayed = flow.begin(actor, replayCmd);
        assertThat(replayed.attachmentId()).isEqualTo(attachmentId);
        assertThat(replayed.files()).singleElement().satisfies(f -> {
            assertThat(f.seq()).isZero();
            assertThat(f.uploadId()).isEqualTo(uploadId);
        });
        assertThat(dir).as("idempotent replay must not create attachment directory").doesNotExist();

        // 4) Conflicting payload for the same operationKey fails ARTIFACT_OPERATION_CONFLICT without touching disk
        Expect.failure(() -> flow.begin(actor, Uploads.begin(opKey, Uploads.body(256, 56))),
                ConflictCode.ARTIFACT_OPERATION_CONFLICT);
        assertThat(dir).as("conflicting replay must not create attachment directory").doesNotExist();
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

    @Test
    @DisplayName("ATA-043: a begin before the attachment directory exists makes it (private) and measures its disk; a missing parent is made too (SFR-006); when it cannot be made (a regular file stands where a parent belongs) the answer is ARTIFACT_STORAGE_IO_ERROR, not a margin on another folder")
    void ata043_beginBeforeTheDirectoryExists() throws java.io.IOException {
        StoreFixture fixture = StoreFixture.create();
        try {
            Path directory = fixture.properties().directory();
            TestTrees.delete(directory);
            assertThat(directory).as("the folder of the installation is not there yet").doesNotExist();

            fixture.store().requireDiskSpace(600);

            assertThat(directory).as("the folder is made the way every write makes it").isDirectory();
            fixture.files().requireDirectory(directory);

            // changed by SFR-006: a missing parent is made too (the product makes its own folders); what cannot be made is a folder
            // below a regular file, and that is still the failure it is, with nothing measured on another folder
            Path aFile = Files.writeString(fixture.root().resolve("a-file"), "not a folder");
            Path belowAFile = aFile.resolve("data").resolve("attachments");
            AttachmentStore unreachable = new AttachmentStore(
                    AttachmentSettings.properties(belowAFile, fixture.properties().keyDirectory(), fixture.keyId()), fixture.files(),
                    fixture.keys(), fixture.cipher());
            assertThatThrownBy(() -> unreachable.requireDiskSpace(600)).isInstanceOfSatisfying(InfrastructureException.class,
                    failure -> assertThat(failure.code()).isEqualTo(InfrastructureCode.ARTIFACT_STORAGE_IO_ERROR));
            assertThat(aFile).as("the file is not replaced").hasContent("not a folder");

            Path noParent = fixture.root().resolve("no-such-parent").resolve("attachments");
            AttachmentStore parentMissing = new AttachmentStore(
                    AttachmentSettings.properties(noParent, fixture.properties().keyDirectory(), fixture.keyId()), fixture.files(),
                    fixture.keys(), fixture.cipher());
            parentMissing.requireDiskSpace(600);
            assertThat(noParent).as("the parent and the folder are made").isDirectory();
        } finally {
            fixture.close();
        }
    }
}
