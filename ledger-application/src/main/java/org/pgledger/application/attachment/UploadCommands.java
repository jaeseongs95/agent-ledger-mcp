package org.pgledger.application.attachment;

import java.time.Instant;

/** The inputs and outputs of the five upload operations (the common design, section 3), in plain types. */
public final class UploadCommands {

    private UploadCommands() {
    }

    /** {@code artifact-begin}; {@code purpose} is optional and the ledger supports only {@code evidence}. */
    public record Begin(String operationKey, String fileName, String mediaType, long size, String sha256, String purpose) {
    }

    /** What begin answers: the upload and what the client needs to send it. */
    public record Begun(String uploadId, long maxChunkBytes, Instant expiresAt) {
    }

    /** {@code artifact-chunk}: the chunk as the tool received it (base64 is decoded by the entry point). */
    public record Chunk(String uploadId, long sequence, String data, String chunkSha256) {
    }

    /** What a chunk answers: its number and the plain bytes the upload holds now. */
    public record Received(long sequence, long received) {
    }

    /** What finish answers. */
    public record Finished(String attachmentId, long size, String sha256) {
    }

    /**
     * What status answers for an upload or an attachment; no path and no storage key.
     *
     * @param state     STAGING or COMPLETED
     * @param expiresAt null for an attachment
     * @param sequence  the number of the next chunk; null for an attachment
     */
    public record Status(String id, String state, String fileName, String mediaType, long size, String sha256,
            Instant expiresAt, long received, Long sequence) {
    }

    /** One page of a body. */
    public record ReadPage(String attachmentId, long offsetBytes, long totalBytes, byte[] data, String mediaType,
            String fileName) {
    }
}
