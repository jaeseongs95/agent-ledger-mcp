// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.attachment.AttachmentFlow;
import org.veridger.application.attachment.AttachmentLinks;
import org.veridger.application.attachment.AttachmentProperties;
import org.veridger.application.attachment.AttachmentStore;
import org.veridger.application.attachment.UploadCommands.Begin;
import org.veridger.application.attachment.UploadCommands.BeginFile;
import org.veridger.application.attachment.UploadCommands.Begun;
import org.veridger.application.attachment.UploadCommands.BegunFile;
import org.veridger.application.attachment.UploadCommands.Chunk;
import org.veridger.application.attachment.UploadCommands.Finished;
import org.veridger.application.attachment.UploadCommands.KeptFile;
import org.veridger.application.attachment.UploadCommands.Received;
import org.veridger.application.attachment.UploadCommands.Status;
import org.veridger.application.support.AttachmentContext;
import org.veridger.application.support.Expect;
import org.veridger.application.support.FailingUploadService;
import org.veridger.application.support.FailingUploadService.Step;
import org.veridger.application.support.LedgerAppContext;
import org.veridger.application.support.Scenario;
import org.veridger.application.support.TestClock;
import org.veridger.application.support.Uploads;
import org.veridger.database.repository.UploadStagingRepository;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.LedgerException;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.model.UploadStaging;
import org.veridger.domain.rule.UploadState;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.StorageKey;

/**
 * The upload services of the attachments on the PostgreSQL of the test PC and the real files, through the entry point
 * {@link AttachmentFlow} (the five operations the tools call): ATA-001, 002, 003, 012 (the check of a record that points to an
 * attachment, at the service level), 013, 025, 026, 028 and 029. A failure of the service between two file steps is made with the test
 * double {@link FailingUploadService}, which throws once in the step it is told to and passes every other call on.
 */
@AttachmentContext
class AttachmentServiceTest {

    private static final int CHUNK = AttachmentProperties.CHUNK_BYTES;

    private static byte[] slice(byte[] body, int from, int to) {
        return Arrays.copyOfRange(body, from, to);
    }

    private static Path fileOf(LedgerAppContext ctx, String uploadId) {
        return Uploads.fileOf(ctx, Uploads.row(ctx, uploadId).storageKey());
    }

    // ------------------------------------------------------------------------------------------------ ATA-001

    @Test
    @DisplayName("ATA-001: begin, three chunks of 256, 256 and 88 KiB, finish, status and read in pages give the body and its declared SHA-256")
    void ata001_theNormalUpload(Scenario s) {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] body = Uploads.body(600 * 1024, 11);

        Begun begun = flow.begin(actor, Uploads.begin(body));
        assertThat(begun.attachmentId()).isNotBlank();
        assertThat(begun.files()).hasSize(1);
        String uploadId = begun.files().getFirst().uploadId();
        assertThat(uploadId).isNotBlank();
        assertThat(begun.maxChunkBytes()).as("the chunk size is the contract constant").isEqualTo(262_144);
        assertThat(begun.expiresAt()).isEqualTo(TestClock.START.plus(Duration.ofMinutes(10)));

        assertThat(Uploads.send(flow, actor, uploadId, 0, slice(body, 0, CHUNK))).isEqualTo(new Received(0, CHUNK));
        assertThat(Uploads.send(flow, actor, uploadId, 1, slice(body, CHUNK, 2 * CHUNK)))
                .isEqualTo(new Received(1, 2 * CHUNK));
        assertThat(Uploads.send(flow, actor, uploadId, 2, slice(body, 2 * CHUNK, body.length)))
                .isEqualTo(new Received(2, body.length));

        Status open = flow.uploadStatus(actor, uploadId);
        assertThat(open.kind()).isEqualTo("upload");
        assertThat(open.seq()).isZero();
        assertThat(open.state()).isEqualTo("STAGING");
        assertThat(open.received()).isEqualTo(body.length);
        assertThat(open.sequence()).isEqualTo(3L);
        assertThat(open.expiresAt()).isNotNull();

        Finished finished = flow.finish(actor, begun.attachmentId());
        assertThat(finished.attachmentId()).isEqualTo(begun.attachmentId());
        assertThat(finished.files()).containsExactly(new KeptFile(0, Uploads.NAME, Uploads.MEDIA_TYPE, body.length,
                Uploads.sha(body)));

        Status status = flow.attachmentStatus(actor, finished.attachmentId());
        assertThat(status.kind()).isEqualTo("attachment");
        assertThat(status.state()).isEqualTo("COMPLETED");
        assertThat(status.files()).isEqualTo(finished.files());
        assertThat(status.expiresAt()).as("a finished upload is answered in the attachment form: no expiry").isNull();
        assertThat(status.sequence()).as("the attachment form has no sequence").isNull();
        StorageKey storageKey = Uploads.currentStorage(ctx, finished.attachmentId()).storageKey();
        assertThat(status.toString()).as("no storage key and no path").doesNotContain(storageKey.hex())
                .doesNotContain(s.bean(AttachmentProperties.class).directory().toString());

        byte[] joined = Uploads.readAll(flow, actor, finished.attachmentId(), 100_000);
        assertThat(joined).isEqualTo(body);
        assertThat(Uploads.sha(joined)).isEqualTo(finished.files().getFirst().sha256());
        assertThat(Uploads.readAll(flow, actor, finished.attachmentId(), CHUNK)).isEqualTo(body);
        assertThat(Uploads.currentStorage(ctx, finished.attachmentId()).rev()).as("the finish writes rev 0").isZero();
    }

    @Test
    @DisplayName("ATA-001: an empty body and a body of one byte are uploads like any other")
    void ata001_smallBodies(Scenario s) {
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);

        Finished empty = flow.finish(actor, flow.begin(actor, Uploads.begin(new byte[0])).attachmentId());
        byte[] one = {42};
        Finished single = Uploads.upload(flow, actor, one);

        assertThat(empty.files().getFirst().size()).isZero();
        assertThat(Uploads.readAll(flow, actor, empty.attachmentId(), 100)).isEmpty();
        assertThat(single.files().getFirst().size()).isEqualTo(1);
        assertThat(Uploads.readAll(flow, actor, single.attachmentId(), 100)).isEqualTo(one);
    }

    @Test
    @DisplayName("ATA-001: any author with a live grant may read an attachment; an upload of another author is not found")
    void ata001_readersAndOwners(Scenario s) {
        String owner = s.actorWith("issue");
        String other = s.actorWith("task");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] body = Uploads.body(500, 12);
        Finished finished = Uploads.upload(flow, owner, body, 200);

        assertThat(Uploads.readAll(flow, other, finished.attachmentId(), 100)).as("another author reads it").isEqualTo(body);
        Begun second = flow.begin(owner, Uploads.begin(Uploads.body(100, 13)));
        String secondUploadId = second.files().getFirst().uploadId();
        Expect.failure(() -> Uploads.send(flow, other, secondUploadId, 0, Uploads.body(100, 13)),
                LookupCode.ARTIFACT_NOT_FOUND);
        Expect.failure(() -> flow.finish(other, second.attachmentId()), LookupCode.ARTIFACT_NOT_FOUND);
        Expect.failure(() -> flow.uploadStatus(other, secondUploadId), LookupCode.ARTIFACT_NOT_FOUND);
        Expect.failure(() -> flow.uploadStatus(owner, Scenario.uuid()), LookupCode.ARTIFACT_NOT_FOUND);
        Expect.failure(() -> flow.read(owner, Scenario.uuid(), 0, 0, 10), LookupCode.ARTIFACT_NOT_FOUND);
    }

    // ------------------------------------------------------------------------------------------------ ATA-002

    @Test
    @DisplayName("ATA-002: out of order, the last chunk again (idempotent), another content for it, an earlier sequence, a wrong chunk hash")
    void ata002_theRulesOfTheSequence(Scenario s) throws IOException {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] body = Uploads.body(1000, 21);
        List<byte[]> parts = Uploads.parts(body, 100, 200, 300);
        Begun begun = flow.begin(actor, Uploads.begin(body));
        String id = begun.files().getFirst().uploadId();

        Expect.failure(() -> Uploads.send(flow, actor, id, 1, parts.get(1)), ConflictCode.ARTIFACT_CHUNK_CONFLICT);
        Uploads.send(flow, actor, id, 0, parts.get(0));
        Uploads.send(flow, actor, id, 1, parts.get(1));
        Received last = Uploads.send(flow, actor, id, 2, parts.get(2));
        UploadStaging before = Uploads.row(ctx, id);
        long sizeBefore = Files.size(fileOf(ctx, id));
        assertThat(before.nextSequence()).isEqualTo(3);
        assertThat(sizeBefore).isEqualTo(before.storedBytes());

        Expect.failure(() -> Uploads.send(flow, actor, id, 5, parts.get(3)), ConflictCode.ARTIFACT_CHUNK_CONFLICT);
        assertThat(Uploads.send(flow, actor, id, 2, parts.get(2))).as("the last applied chunk again: the state as it is")
                .isEqualTo(last);
        Expect.failure(() -> Uploads.send(flow, actor, id, 2, Uploads.body(300, 99)), ConflictCode.ARTIFACT_CHUNK_CONFLICT);
        Expect.failure(() -> Uploads.send(flow, actor, id, 0, parts.get(0)), ConflictCode.ARTIFACT_CHUNK_CONFLICT);
        Expect.failure(() -> flow.chunk(actor, new Chunk(id, 3, Uploads.chunk(id, 3, parts.get(3)).data(),
                Uploads.sha(new byte[] {1}))), ConflictCode.CHUNK_HASH_MISMATCH);

        UploadStaging after = Uploads.row(ctx, id);
        assertThat(after.nextSequence()).isEqualTo(3);
        assertThat(after.receivedBytes()).isEqualTo(600);
        assertThat(after.storedBytes()).isEqualTo(before.storedBytes());
        assertThat(Files.size(fileOf(ctx, id))).as("nothing was appended in the failures").isEqualTo(sizeBefore);

        assertThat(Uploads.send(flow, actor, id, 3, parts.get(3))).isEqualTo(new Received(3, 1000));
        Finished finished = flow.finish(actor, begun.attachmentId());
        assertThat(Uploads.readAll(flow, actor, finished.attachmentId(), 300)).isEqualTo(body);
    }

    // ------------------------------------------------------------------------------------------------ ATA-003

    @Test
    @DisplayName("ATA-003: a declared SHA-256 that is not the bytes' fails the finish with ARTIFACT_HASH_MISMATCH; the bytes and the row stay and the finish fails again the same way")
    void ata003_aWrongDeclaredHash(Scenario s) throws IOException {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] body = Uploads.body(600, 31);
        Begin wrong = new Begin("op-" + Scenario.uuid(),
                List.of(new BeginFile(Uploads.NAME, Uploads.MEDIA_TYPE, body.length, Uploads.sha(Uploads.body(600, 32)))),
                null);
        Begun begun = flow.begin(actor, wrong);
        String id = begun.files().getFirst().uploadId();
        Uploads.sendParts(flow, actor, id, Uploads.parts(body, 200, 200), 0);
        long stored = Uploads.row(ctx, id).storedBytes();

        Expect.failure(() -> flow.finish(actor, begun.attachmentId()), ConflictCode.ARTIFACT_HASH_MISMATCH);
        Expect.failure(() -> flow.finish(actor, begun.attachmentId()), ConflictCode.ARTIFACT_HASH_MISMATCH);

        UploadStaging row = Uploads.row(ctx, id);
        assertThat(row.state()).isEqualTo(UploadState.STAGING);
        assertThat(row.storedBytes()).isEqualTo(stored);
        assertThat(Files.size(fileOf(ctx, id))).as("the bytes are still there and no end mark was added").isEqualTo(stored);
        assertThat(flow.uploadStatus(actor, id).state()).isEqualTo("STAGING");
    }

    @Test
    @DisplayName("ATA-003: fewer bytes than declared fail the finish with ARTIFACT_INCOMPLETE; after the missing chunk is sent the same upload finishes")
    void ata003_aMissingChunkCanBeSentAfterTheFailedFinish(Scenario s) throws IOException {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] body = Uploads.body(600, 33);
        List<byte[]> parts = Uploads.parts(body, 200, 200, 200);
        Begun begun = flow.begin(actor, Uploads.begin(body));
        String id = begun.files().getFirst().uploadId();
        Uploads.sendParts(flow, actor, id, parts.subList(0, 2), 0);
        long stored = Uploads.row(ctx, id).storedBytes();

        Expect.failure(() -> flow.finish(actor, begun.attachmentId()), ConflictCode.ARTIFACT_INCOMPLETE);

        assertThat(Uploads.row(ctx, id).state()).isEqualTo(UploadState.STAGING);
        assertThat(Files.size(fileOf(ctx, id))).as("the bytes remain").isEqualTo(stored);
        Uploads.send(flow, actor, id, 2, parts.get(2));
        Finished finished = flow.finish(actor, begun.attachmentId());
        assertThat(finished.files().getFirst().sha256()).isEqualTo(Uploads.sha(body));
        assertThat(Uploads.readAll(flow, actor, finished.attachmentId(), 250)).isEqualTo(body);
    }

    @Test
    @DisplayName("ATA-003: a chunk that would pass the declared size is refused with ARTIFACT_HASH_MISMATCH and nothing is appended")
    void ata003_aChunkPastTheDeclaredSizeIsRefused(Scenario s) {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] declared = Uploads.body(300, 34);
        String id = flow.begin(actor, Uploads.begin(declared)).files().getFirst().uploadId();

        Expect.failure(() -> Uploads.send(flow, actor, id, 0, Uploads.body(400, 35)), ConflictCode.ARTIFACT_HASH_MISMATCH);

        assertThat(Uploads.row(ctx, id).storedBytes()).isZero();
        assertThat(Uploads.row(ctx, id).nextSequence()).isZero();
    }

    // ------------------------------------------------------------------------------------------------ ATA-013

    @Test
    @DisplayName("ATA-013: a chunk appended and not recorded (the record step failed once): finish answers ARTIFACT_INCOMPLETE, the same chunk is applied once, finish passes")
    void ata013_theTailOfAFailedChunk(Scenario s) throws IOException {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        AttachmentFlow failing = new AttachmentFlow(new FailingUploadService(ctx, Step.RECORD_CHUNK),
                s.bean(AttachmentStore.class));
        byte[] body = Uploads.body(500, 41);
        List<byte[]> parts = Uploads.parts(body, 300);
        Begun begun = flow.begin(actor, Uploads.begin(body));
        String id = begun.files().getFirst().uploadId();
        Uploads.send(flow, actor, id, 0, parts.get(0));
        long recorded = Uploads.row(ctx, id).storedBytes();

        assertThatThrownBy(() -> Uploads.send(failing, actor, id, 1, parts.get(1))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected failure");

        UploadStaging row = Uploads.row(ctx, id);
        long withTail = Files.size(fileOf(ctx, id));
        assertThat(row.nextSequence()).as("the row does not know the chunk").isEqualTo(1);
        assertThat(row.storedBytes()).isEqualTo(recorded);
        assertThat(withTail).as("the file is longer than the row says").isGreaterThan(recorded);
        Expect.failure(() -> flow.finish(actor, begun.attachmentId()), ConflictCode.ARTIFACT_INCOMPLETE);
        assertThat(Files.size(fileOf(ctx, id))).as("the finish appended nothing").isEqualTo(withTail);

        assertThat(Uploads.send(flow, actor, id, 1, parts.get(1))).as("the same chunk again is accepted").isEqualTo(new Received(1, 500));
        UploadStaging healed = Uploads.row(ctx, id);
        assertThat(healed.nextSequence()).isEqualTo(2);
        assertThat(healed.storedBytes()).as("applied once: the tail is the segment, not another one").isEqualTo(withTail);
        assertThat(Files.size(fileOf(ctx, id))).isEqualTo(withTail);
        Finished finished = flow.finish(actor, begun.attachmentId());
        assertThat(finished.files().getFirst().sha256()).isEqualTo(Uploads.sha(body));
        assertThat(Uploads.readAll(flow, actor, finished.attachmentId(), 123)).isEqualTo(body);
    }

    @Test
    @DisplayName("ATA-013: a tail that is another chunk is not the one sent again: ARTIFACT_CHUNK_CONFLICT, and the tail is not cut off")
    void ata013_anotherChunkOverATail(Scenario s) throws IOException {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        AttachmentFlow failing = new AttachmentFlow(new FailingUploadService(ctx, Step.RECORD_CHUNK),
                s.bean(AttachmentStore.class));
        byte[] body = Uploads.body(400, 42);
        List<byte[]> parts = Uploads.parts(body, 200);
        Begun begun = flow.begin(actor, Uploads.begin(body));
        String id = begun.files().getFirst().uploadId();
        assertThatThrownBy(() -> Uploads.send(failing, actor, id, 0, parts.get(0))).isInstanceOf(IllegalStateException.class);
        long withTail = Files.size(fileOf(ctx, id));

        Expect.failure(() -> Uploads.send(flow, actor, id, 0, Uploads.body(200, 43)), ConflictCode.ARTIFACT_CHUNK_CONFLICT);

        assertThat(Files.size(fileOf(ctx, id))).as("the tail stays: it may be the segment of a request that still runs").isEqualTo(withTail);
        Uploads.send(flow, actor, id, 0, parts.get(0));
        Uploads.send(flow, actor, id, 1, parts.get(1));
        assertThat(flow.finish(actor, begun.attachmentId()).files().getFirst().sha256()).isEqualTo(Uploads.sha(body));
    }

    // ------------------------------------------------------------------------------------------------ ATA-028

    @Test
    @DisplayName("ATA-028: the end segment was appended and the transaction of the finish failed: finish again finds the tail to be the end segment, appends nothing and passes with the same attachment id")
    void ata028_theEndSegmentTail(Scenario s) throws IOException {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        AttachmentFlow failing = new AttachmentFlow(new FailingUploadService(ctx, Step.COMPLETE_UPLOAD),
                s.bean(AttachmentStore.class));
        byte[] body = Uploads.body(700, 51);
        Begun begun = Uploads.sendAll(flow, actor, body, 300, 300);
        String id = begun.files().getFirst().uploadId();
        String attachmentId = begun.attachmentId();
        long recorded = Uploads.row(ctx, id).storedBytes();

        assertThatThrownBy(() -> failing.finish(actor, attachmentId)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected failure");

        long withEndSegment = Files.size(fileOf(ctx, id));
        assertThat(withEndSegment).as("the end segment (4 + 12 + 0 + 16) was appended").isEqualTo(recorded + 32);
        assertThat(Uploads.row(ctx, id).state()).as("the row was not moved").isEqualTo(UploadState.STAGING);

        Finished finished = flow.finish(actor, attachmentId);

        assertThat(finished.attachmentId()).isEqualTo(attachmentId);
        assertThat(finished.files().getFirst().sha256()).isEqualTo(Uploads.sha(body));
        assertThat(Files.size(fileOf(ctx, id))).as("nothing more was appended").isEqualTo(withEndSegment);
        assertThat(Uploads.readAll(flow, actor, attachmentId, 200)).isEqualTo(body);
        assertThat(flow.finish(actor, attachmentId)).as("and a third finish gives the same answer").isEqualTo(finished);
    }

    // ------------------------------------------------------------------------------------------------ ATA-025

    @Test
    @DisplayName("ATA-025: the same operation key with the same content gives the same upload; other content is ARTIFACT_OPERATION_CONFLICT; the request receipt is not used")
    void ata025_beginTwiceWithTheSameOperationKey(Scenario s) {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        String other = s.actorWith("task");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] body = Uploads.body(300, 61);
        String operationKey = "op-" + Scenario.uuid();
        long receipts = s.count("SELECT COUNT(*) FROM request_receipt");

        Begun first = flow.begin(actor, Uploads.begin(operationKey, body));
        ctx.clock().set(TestClock.START.plusSeconds(30));
        Begun second = flow.begin(actor, Uploads.begin(operationKey, body));

        assertThat(second).as("the same upload, with the expiry it had").isEqualTo(first);
        byte[] changed = Uploads.body(301, 62);
        Expect.failure(() -> flow.begin(actor, Uploads.begin(operationKey, changed)), ConflictCode.ARTIFACT_OPERATION_CONFLICT);
        Expect.failure(() -> flow.begin(actor, new Begin(operationKey,
                List.of(new BeginFile("other.md", Uploads.MEDIA_TYPE, body.length, Uploads.sha(body))), null)),
                ConflictCode.ARTIFACT_OPERATION_CONFLICT);
        Expect.failure(() -> flow.begin(actor, new Begin(operationKey,
                List.of(new BeginFile(Uploads.NAME, Uploads.MEDIA_TYPE, body.length, Uploads.sha(Uploads.body(300, 63)))), null)),
                ConflictCode.ARTIFACT_OPERATION_CONFLICT);
        assertThat(s.count("SELECT COUNT(*) FROM upload_staging WHERE owner = ? AND operation_key = ?", actor, operationKey))
                .isEqualTo(1);
        assertThat(flow.begin(other, Uploads.begin(operationKey, body)).attachmentId()).as("the key belongs to its author")
                .isNotEqualTo(first.attachmentId());
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt")).as("no second decision point").isEqualTo(receipts);
    }

    @Test
    @DisplayName("ATA-025: an operation key is required, and a blank or too long one is refused")
    void ata025_theOperationKeyIsRequired(Scenario s) {
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] body = Uploads.body(10, 64);

        for (String bad : new String[] {null, "", "   ", "x".repeat(129)}) {
            LedgerException failure = Expect.failure(() -> flow.begin(actor, Uploads.begin(bad, body)),
                    ValidationCode.VALIDATION_FAILED);
            assertThat(Expect.firstIssue(failure).path()).isEqualTo("operationKey");
        }
    }

    // ------------------------------------------------------------------------------------------------ ATA-026

    @Test
    @DisplayName("ATA-026: no purpose and the purpose evidence are accepted; upload, staged and x are refused with ARTIFACT_UNSUPPORTED_PURPOSE and no row")
    void ata026_thePurpose(Scenario s) {
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] body = Uploads.body(10, 71);

        assertThat(flow.begin(actor, new Begin("op-" + Scenario.uuid(), List.of(Uploads.beginFile(body)), null)).attachmentId())
                .isNotBlank();
        assertThat(flow.begin(actor, new Begin("op-" + Scenario.uuid(), List.of(Uploads.beginFile(body)), "evidence"))
                .attachmentId()).isNotBlank();
        long rows = s.count("SELECT COUNT(*) FROM upload_staging WHERE owner = ?", actor);
        assertThat(rows).isEqualTo(2);

        for (String purpose : new String[] {"upload", "staged", "x"}) {
            String operationKey = "op-" + Scenario.uuid();
            LedgerException failure = Expect.failure(() -> flow.begin(actor, new Begin(operationKey,
                    List.of(Uploads.beginFile(body)), purpose)), ValidationCode.ARTIFACT_UNSUPPORTED_PURPOSE);
            assertThat(failure).isInstanceOf(ValidationException.class);
            assertThat(failure.args()).containsExactly(purpose);
            assertThat(Expect.message(failure)).as("a message in English").contains(purpose).contains("evidence");
            assertThat(ResourceBundle.getBundle("messages/ledger-errors", Locale.KOREAN).getString(failure.code().messageKey()))
                    .as("and one in Korean").contains("evidence").isNotEqualTo(Expect.message(failure));
            assertThat(s.count("SELECT COUNT(*) FROM upload_staging WHERE owner = ? AND operation_key = ?", actor, operationKey))
                    .as("no row for the refused purpose").isZero();
        }
        assertThat(s.count("SELECT COUNT(*) FROM upload_staging WHERE owner = ?", actor)).isEqualTo(rows);
    }

    // ------------------------------------------------------------------------------------------------ ATA-029

    @Test
    @DisplayName("ATA-029: while an append holds the lock past the end of the file, the read of another attachment and the status of the same upload answer, and a second chunk gets ARTIFACT_BUSY without waiting")
    void ata029_anAppendDoesNotBlockReadsAndStatus(Scenario s) throws IOException {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] kept = Uploads.body(400, 81);
        Finished keptAttachment = Uploads.upload(flow, actor, kept, 150);
        byte[] body = Uploads.body(500, 82);
        List<byte[]> parts = Uploads.parts(body, 200, 150);
        Begun begun = flow.begin(actor, Uploads.begin(body));
        String id = begun.files().getFirst().uploadId();
        Uploads.send(flow, actor, id, 0, parts.get(0));
        Path file = fileOf(ctx, id);
        long stored = Files.size(file);

        // the append of another request: the byte past the end of the file is locked through a second channel of the same file
        try (FileChannel appender = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                FileLock lock = appender.tryLock(Long.MAX_VALUE - 1, 1, false)) {
            assertThat(lock).isNotNull();

            assertThat(Uploads.readAll(flow, actor, keptAttachment.attachmentId(), 100)).as("a read of another attachment").isEqualTo(kept);
            Status status = flow.uploadStatus(actor, id);
            assertThat(status.state()).as("the status of the same upload").isEqualTo("STAGING");
            assertThat(status.received()).isEqualTo(200);
            Expect.failure(() -> Uploads.send(flow, actor, id, 1, parts.get(1)), ConflictCode.ARTIFACT_BUSY);
            assertThat(Files.size(file)).as("nothing was appended while the lock was held").isEqualTo(stored);
        }

        assertThat(Uploads.send(flow, actor, id, 1, parts.get(1))).as("the lock is gone: the same chunk is applied").isEqualTo(new Received(1, 350));
        assertThat(Uploads.send(flow, actor, id, 2, parts.get(2))).isEqualTo(new Received(2, 500));
        assertThat(Uploads.readAll(flow, actor, flow.finish(actor, begun.attachmentId()).attachmentId(), 500)).isEqualTo(body);
    }

    // ------------------------------------------------------------------------------------------------ ATA-012 (the check of a record)

    @Test
    @DisplayName("ATA-012: the check of a record that points to an attachment: an attachment of the writing author passes, one that does not exist or is another author's is ARTIFACT_NOT_FOUND, no id passes")
    void ata012_theAttachmentCheckOfARecord(Scenario s) {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        String other = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        AttachmentLinks links = s.bean(AttachmentLinks.class);
        String mine = Uploads.upload(flow, actor, Uploads.body(100, 91)).attachmentId();
        String theirs = Uploads.upload(flow, other, Uploads.body(100, 92)).attachmentId();

        ctx.tx(() -> {
            links.requireOwnedBy(mine, AuthorId.of(actor));
            links.requireOwnedBy(null, AuthorId.of(actor));
            return null;
        });
        Expect.failure(() -> ctx.tx(() -> {
            links.requireOwnedBy(theirs, AuthorId.of(actor));
            return null;
        }), LookupCode.ARTIFACT_NOT_FOUND);
        Expect.failure(() -> ctx.tx(() -> {
            links.requireOwnedBy(Scenario.uuid(), AuthorId.of(actor));
            return null;
        }), LookupCode.ARTIFACT_NOT_FOUND);
        LedgerException notUuid = Expect.failure(() -> ctx.tx(() -> {
            links.requireOwnedBy("not-a-uuid", AuthorId.of(actor));
            return null;
        }), ValidationCode.VALIDATION_FAILED);
        assertThat(Expect.firstIssue(notUuid).path()).isEqualTo("attachment_id");
        assertThat(Expect.failure(() -> ctx.tx(() -> {
            links.requireOwnedBy(theirs, AuthorId.of(actor));
            return null;
        }), LookupCode.ARTIFACT_NOT_FOUND).args()).as("the answer is the one of a missing attachment").containsExactly(theirs);
    }

    // ------------------------------------------------------------------------------------------------ the expiry

    @Test
    @DisplayName("an upload that has expired and was not yet removed takes no chunk and no finish: ARTIFACT_EXPIRED")
    void anExpiredUploadTakesNothing(Scenario s) {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] body = Uploads.body(200, 95);
        List<byte[]> parts = Uploads.parts(body, 100);
        Begun begun = flow.begin(actor, Uploads.begin(body));
        String id = begun.files().getFirst().uploadId();
        Uploads.send(flow, actor, id, 0, parts.get(0));

        ctx.clock().set(Uploads.row(ctx, id).expiresAt());

        Expect.failure(() -> Uploads.send(flow, actor, id, 1, parts.get(1)), ConflictCode.ARTIFACT_EXPIRED);
        Expect.failure(() -> flow.finish(actor, begun.attachmentId()), ConflictCode.ARTIFACT_EXPIRED);
        assertThat(s.bean(UploadStagingRepository.class).findById(id)).isPresent();
    }

    // ------------------------------------------------------------------------------------------------ ATA-044

    @Test
    @DisplayName("ATA-044: multi-file bundle extends sibling expiry, fails atomically if any file is incomplete, and commits all files together")
    void ata044_multiFileBundleLifecycleAtomicityAndExpiryExtension(Scenario s) {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] report = Uploads.body(300, 101);
        byte[] trace = Uploads.body(250, 102);

        Begun begun = flow.begin(actor, Uploads.beginBundle("op-bundle-" + Scenario.uuid(), List.of(
                Uploads.beginFile("review.md", "text/markdown", report),
                Uploads.beginFile("trace.log", "text/plain", trace))));
        assertThat(begun.files()).hasSize(2);
        BegunFile f0 = begun.files().get(0);
        BegunFile f1 = begun.files().get(1);
        assertThat(f0.seq()).isZero();
        assertThat(f1.seq()).isEqualTo(1);

        // Sending a chunk on file 0 extends expiry for file 1 too
        ctx.clock().set(TestClock.START.plus(Duration.ofMinutes(4)));
        Uploads.send(flow, null, f0.uploadId(), 0, report);
        assertThat(Uploads.row(ctx, f1.uploadId()).expiresAt()).isEqualTo(TestClock.START.plus(Duration.ofMinutes(14)));

        // Finishing while file 1 is incomplete leaves zero rows in attachment/attachment_file/attachment_storage
        Expect.failure(() -> flow.finish(actor, begun.attachmentId()), ConflictCode.ARTIFACT_INCOMPLETE);
        assertThat(s.count("SELECT COUNT(*) FROM attachment WHERE id = ?", begun.attachmentId())).isZero();
        assertThat(s.count("SELECT COUNT(*) FROM attachment_file WHERE attachment_id = ?", begun.attachmentId())).isZero();
        assertThat(s.count("SELECT COUNT(*) FROM attachment_storage WHERE attachment_id = ?", begun.attachmentId())).isZero();

        // Send remaining file via null-actor uploadId capability (editions 9/12), then finish
        assertThat(flow.uploadStatus(null, f1.uploadId()).received()).isZero();
        Uploads.send(flow, null, f1.uploadId(), 0, trace);
        Finished finished = flow.finish(actor, begun.attachmentId());
        assertThat(finished.files()).containsExactly(
                new KeptFile(0, "review.md", "text/markdown", report.length, Uploads.sha(report)),
                new KeptFile(1, "trace.log", "text/plain", trace.length, Uploads.sha(trace)));

        assertThat(Uploads.readAll(flow, actor, begun.attachmentId(), 0, 100)).isEqualTo(report);
        assertThat(Uploads.readAll(flow, actor, begun.attachmentId(), 1, 100)).isEqualTo(trace);
        assertThat(flow.attachmentStatus(actor, begun.attachmentId()).files()).isEqualTo(finished.files());
    }
}
