// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.attachment.AttachmentFlow;
import org.veridger.application.attachment.AttachmentProperties;
import org.veridger.application.attachment.UploadCommands.Begin;
import org.veridger.application.attachment.UploadCommands.BeginFile;
import org.veridger.application.attachment.UploadCommands.Begun;
import org.veridger.application.attachment.UploadCommands.Chunk;
import org.veridger.application.attachment.UploadService;
import org.veridger.application.support.AttachmentContext;
import org.veridger.application.support.Expect;
import org.veridger.application.support.Scenario;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.value.Sha256;

/**
 * Boundary and failure-path validation tests for {@link AttachmentFlow} and {@link UploadService}.
 */
@AttachmentContext
class AttachmentFlowValidationTest {

    @Test
    @DisplayName("ATA-VAL-01 AttachmentFlow and UploadService reject invalid begin, chunk, status, and read inputs")
    void validatesBeginChunkStatusAndReadBoundaries(Scenario s) {
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        String zeroSha = Sha256.ofBytes(new byte[0]).hex();
        BeginFile validFile = new BeginFile("a.txt", "text/plain", 0L, zeroSha);

        // Begin validation: null, blank/long operationKey, empty/>64 files, unsupported purpose
        Expect.failure(() -> flow.begin(Scenario.AUTHOR, null), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> flow.begin(Scenario.AUTHOR, new Begin("", List.of(validFile), "evidence")),
                ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> flow.begin(Scenario.AUTHOR, new Begin("x".repeat(129), List.of(validFile), "evidence")),
                ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> flow.begin(Scenario.AUTHOR, new Begin(Scenario.uuid(), List.of(), "evidence")),
                ValidationCode.VALIDATION_FAILED);
        List<BeginFile> tooMany = IntStream.range(0, UploadService.MAX_FILES + 1)
                .mapToObj(i -> new BeginFile("f" + i + ".txt", "text/plain", 0L, zeroSha)).toList();
        Expect.failure(() -> flow.begin(Scenario.AUTHOR, new Begin(Scenario.uuid(), tooMany, "evidence")),
                ConflictCode.ARTIFACT_LIMIT);
        Expect.failure(() -> flow.begin(Scenario.AUTHOR, new Begin(Scenario.uuid(), List.of(validFile), "unsupported")),
                ValidationCode.ARTIFACT_UNSUPPORTED_PURPOSE);

        // Status XOR validation
        Expect.failure(() -> flow.status(Scenario.AUTHOR, null, null), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> flow.status(Scenario.AUTHOR, "u1", "a1"), ValidationCode.VALIDATION_FAILED);

        // Read boundary validation
        Expect.failure(() -> flow.read(Scenario.AUTHOR, "att-1", 0, -1L, 10), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> flow.read(Scenario.AUTHOR, "att-1", 0, 0L, 0), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> flow.read(Scenario.AUTHOR, "att-1", 0, 0L, AttachmentProperties.CHUNK_BYTES + 1),
                ValidationCode.VALIDATION_FAILED);

        // Chunk validation: null, null data, invalid base64, empty bytes, bad sha, mismatched sha
        Expect.failure(() -> flow.chunk(Scenario.AUTHOR, null), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> flow.chunk(Scenario.AUTHOR, new Chunk("u1", 0L, null, zeroSha)),
                ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> flow.chunk(Scenario.AUTHOR, new Chunk("u1", 0L, "!!!not-base64!!!", zeroSha)),
                ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> flow.chunk(Scenario.AUTHOR, new Chunk("u1", 0L, "", zeroSha)),
                ValidationCode.VALIDATION_FAILED);
        String helloB64 = Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));
        Expect.failure(() -> flow.chunk(Scenario.AUTHOR, new Chunk("u1", 0L, helloB64, "bad-sha")),
                ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> flow.chunk(Scenario.AUTHOR, new Chunk("u1", 0L, helloB64, zeroSha)),
                ConflictCode.CHUNK_HASH_MISMATCH);

        // Status dispatch & idempotent replay vs operation conflicts
        String opKey = Scenario.uuid();
        Begun begun = flow.begin(Scenario.AUTHOR, new Begin(opKey, List.of(validFile), "evidence"));
        assertThat(flow.status(Scenario.AUTHOR, begun.files().getFirst().uploadId(), null)).isNotNull();
        assertThat(flow.begin(Scenario.AUTHOR, new Begin(opKey, List.of(validFile), "evidence")).attachmentId())
                .isEqualTo(begun.attachmentId());
        Expect.failure(() -> flow.begin(Scenario.AUTHOR, new Begin(opKey, List.of(validFile, validFile), "evidence")),
                ConflictCode.ARTIFACT_OPERATION_CONFLICT);
        Expect.failure(() -> flow.begin(Scenario.AUTHOR, new Begin(opKey,
                List.of(new BeginFile("other.txt", "text/plain", 0L, zeroSha)), "evidence")),
                ConflictCode.ARTIFACT_OPERATION_CONFLICT);
        Expect.failure(() -> flow.begin(Scenario.AUTHOR, new Begin(opKey,
                List.of(new BeginFile("a.txt", "application/json", 0L, zeroSha)), "evidence")),
                ConflictCode.ARTIFACT_OPERATION_CONFLICT);
        Expect.failure(() -> flow.begin(Scenario.AUTHOR, new Begin(opKey,
                List.of(new BeginFile("a.txt", "text/plain", 5L, zeroSha)), "evidence")),
                ConflictCode.ARTIFACT_OPERATION_CONFLICT);

        // validateFile: null item, blank name, backslash/control char, bad mediaType, negative size, bad sha256
        Expect.failure(() -> flow.begin(Scenario.AUTHOR, new Begin(Scenario.uuid(),
                Arrays.asList((BeginFile) null), "evidence")), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> flow.begin(Scenario.AUTHOR, new Begin(Scenario.uuid(),
                List.of(new BeginFile("", "not-a-mime", -1L, "not-sha")), "evidence")),
                ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> flow.begin(Scenario.AUTHOR, new Begin(Scenario.uuid(),
                List.of(new BeginFile("bad\\name", "text/plain", 0L, zeroSha)), "evidence")),
                ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> flow.begin(Scenario.AUTHOR, new Begin(Scenario.uuid(),
                List.of(new BeginFile("bad\u0007name", "text/plain", 0L, zeroSha)), "evidence")),
                ValidationCode.VALIDATION_FAILED);

        // UploadService planChunk, planFinish, resolveDuplicateBegin, readAttachmentStatus, readFileView boundaries
        UploadService svc = s.bean(UploadService.class);
        String uid = begun.files().getFirst().uploadId();
        Sha256 helloSha = Sha256.ofBytes("hello".getBytes(StandardCharsets.UTF_8));
        Expect.failure(() -> svc.planChunk(Scenario.AUTHOR, uid, -1L, 5, helloSha), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> svc.planChunk(Scenario.AUTHOR, uid, 0L, 0, helloSha), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> svc.planChunk(Scenario.AUTHOR, "not-a-uuid", 0L, 5, helloSha),
                LookupCode.ARTIFACT_NOT_FOUND);
        Expect.failure(() -> svc.planChunk(Scenario.AUTHOR, uid, 1L, 5, helloSha), ConflictCode.ARTIFACT_CHUNK_CONFLICT);
        Expect.failure(() -> svc.planChunk(Scenario.AUTHOR, uid, 0L, 5, helloSha), ConflictCode.ARTIFACT_HASH_MISMATCH);
        Expect.failure(() -> svc.planFinish(Scenario.AUTHOR, "not-a-uuid"), LookupCode.ARTIFACT_NOT_FOUND);
        Expect.failure(() -> svc.readAttachmentStatus("not-a-uuid"), LookupCode.ARTIFACT_NOT_FOUND);
        Expect.failure(() -> svc.readFileView("not-a-uuid", 0), LookupCode.ARTIFACT_NOT_FOUND);
        Expect.failure(() -> svc.readFileView(begun.attachmentId(), -1), LookupCode.ARTIFACT_NOT_FOUND);
        Expect.failure(() -> svc.resolveDuplicateBegin(Scenario.AUTHOR, null),
                ConflictCode.ARTIFACT_OPERATION_CONFLICT);
        Expect.failure(() -> svc.resolveDuplicateBegin(Scenario.AUTHOR,
                new Begin(Scenario.uuid(), List.of(validFile), "evidence")),
                ConflictCode.ARTIFACT_OPERATION_CONFLICT);
        assertThat(svc.resolveDuplicateBegin(Scenario.AUTHOR, new Begin(opKey, List.of(validFile), "evidence"))
                .attachmentId()).isEqualTo(begun.attachmentId());
    }
}
