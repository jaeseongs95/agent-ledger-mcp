// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.attachment;

import java.time.Instant;
import java.util.List;

/** Value records exchanged by {@link AttachmentFlow}, {@link UploadService}, MCP tools, and admin panels. */
public final class UploadCommands {

    private UploadCommands() {
    }

    /** One file declared in an {@code artifact-begin} request. */
    public record BeginFile(String fileName, String mediaType, long size, String sha256) {
    }

    /** Start a multi-file bundle upload. */
    public record Begin(String operationKey, List<BeginFile> files, String purpose) {
    }

    /** One staged file upload created by {@code artifact-begin}. */
    public record BegunFile(int seq, String uploadId) {
    }

    /** Result of {@code artifact-begin}: shared {@code attachmentId} plus ordered per-file {@code uploadId}s. */
    public record Begun(String attachmentId, List<BegunFile> files, int maxChunkBytes, Instant expiresAt) {
    }

    /** Upload one chunk of a staged file. */
    public record Chunk(String uploadId, long sequence, String data, String chunkSha256) {
    }

    /** Acknowledgement of one chunk. */
    public record Received(long sequence, long received) {
    }

    /** Immutable facts of one file inside a finished bundle. */
    public record KeptFile(int seq, String fileName, String mediaType, long size, String sha256) {
    }

    /** Result of {@code artifact-finish}: kept bundle id and ordered file list. */
    public record Finished(String attachmentId, List<KeptFile> files) {
    }

    /**
     * Status answer for either an open upload ({@code kind = "upload"}) or a kept attachment bundle ({@code kind = "attachment"}).
     * Never contains a path or storage key.
     */
    public record Status(
            String kind,
            String uploadId,
            String attachmentId,
            Integer seq,
            String state,
            String fileName,
            String mediaType,
            Long size,
            String sha256,
            Instant expiresAt,
            Long received,
            Long sequence,
            List<KeptFile> files) {
    }

    /** One verified plain page of file {@code seq} inside a kept attachment bundle. */
    public record Page(
            String attachmentId,
            int seq,
            long offsetBytes,
            long totalBytes,
            int size,
            byte[] data,
            String mediaType,
            String fileName) {
    }
}
