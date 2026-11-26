// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.application.attachment.UploadCommands.Begin;
import org.agentledger.application.attachment.UploadCommands.BeginFile;
import org.agentledger.application.attachment.UploadCommands.Begun;
import org.agentledger.application.attachment.UploadCommands.Chunk;
import org.agentledger.application.support.AttachmentContext;
import org.agentledger.application.support.Expect;
import org.agentledger.application.support.Scenario;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.ValidationCode;
import org.agentledger.domain.value.Sha256;

/**
 * R3-F4: zero-byte chunks and excessive tiny-chunk segment amplification are refused before disk writes.
 */
@AttachmentContext
class UploadChunkLimitTest {

    @Test
    @DisplayName("R3-F4: zero-byte chunk is refused and 0-byte declared file finishes without any chunk")
    void r3F4_zeroByteChunkRefusedAndEmptyFileFinishes(Scenario s) {
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        Sha256 emptySha = Sha256.ofBytes(new byte[0]);
        Begun begun = flow.begin(Scenario.AUTHOR, new Begin(Scenario.uuid(),
                List.of(new BeginFile("empty.txt", "text/plain", 0L, emptySha.hex())), "evidence"));
        String uploadId = begun.files().getFirst().uploadId();

        Expect.failure(() -> flow.chunk(Scenario.AUTHOR, new Chunk(uploadId, 0L, "", emptySha.hex())),
                ValidationCode.VALIDATION_FAILED);

        var finished = flow.finish(Scenario.AUTHOR, begun.attachmentId());
        assertThat(finished.files()).hasSize(1);
        assertThat(finished.files().getFirst().size()).isZero();
    }

    @Test
    @DisplayName("R3-F4: excessive 1-byte chunks are capped by maxSegmentsFor to prevent segment-overhead amplification")
    void r3F4_tinyChunkAmplificationRefused(Scenario s) {
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] full = new byte[20];
        for (int i = 0; i < full.length; i++) {
            full[i] = (byte) ('a' + i);
        }
        Sha256 fullSha = Sha256.ofBytes(full);
        Begun begun = flow.begin(Scenario.AUTHOR, new Begin(Scenario.uuid(),
                List.of(new BeginFile("tiny.bin", "application/octet-stream", full.length, fullSha.hex())), "evidence"));
        String uploadId = begun.files().getFirst().uploadId();

        long maxSegments = UploadService.maxSegmentsFor(full.length);
        assertThat(maxSegments).isEqualTo(16L);
        for (int seq = 0; seq < maxSegments; seq++) {
            byte[] oneByte = new byte[] { full[seq] };
            flow.chunk(Scenario.AUTHOR, new Chunk(uploadId, seq, Base64.getEncoder().encodeToString(oneByte),
                    Sha256.ofBytes(oneByte).hex()));
        }
        byte[] nextByte = new byte[] { full[(int) maxSegments] };
        Expect.failure(() -> flow.chunk(Scenario.AUTHOR, new Chunk(uploadId, maxSegments,
                Base64.getEncoder().encodeToString(nextByte), Sha256.ofBytes(nextByte).hex())),
                ConflictCode.ARTIFACT_LIMIT);
    }

    @Test
    @DisplayName("R3-F4: maxSegmentsFor bounds overhead below 1% for large files")
    void r3F4_maxSegmentsBoundsLargeFileOverhead() {
        assertThat(UploadService.maxSegmentsFor(0L)).isZero();
        assertThat(UploadService.maxSegmentsFor(-1L)).isZero();
        assertThat(UploadService.maxSegmentsFor(64L * 1024L * 1024L)).isEqualTo(16_384L);
    }
}
