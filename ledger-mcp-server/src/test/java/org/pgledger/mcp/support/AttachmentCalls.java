// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.mcp.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Base64;
import java.util.List;
import org.pgledger.domain.value.Sha256;

/**
 * Arguments for the five bundle attachment tools sent in server tests. Null fields are omitted from JSON; a non-null
 * {@code hostAttestation} holds the session author id that {@link Attester} signs for the tool call.
 */
public final class AttachmentCalls {

    private AttachmentCalls() {
    }

    public static String sha256(byte[] bytes) {
        return Sha256.ofBytes(bytes).hex();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BeginFile(String fileName, String mediaType, Long size, String sha256) {

        public static BeginFile of(String fileName, String mediaType, byte[] body) {
            return new BeginFile(fileName, mediaType, (long) body.length, AttachmentCalls.sha256(body));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Begin(String operationKey, List<BeginFile> files, String purpose,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static Begin of(String operationKey, String fileName, String mediaType, byte[] body, String author) {
            return new Begin(operationKey, List.of(BeginFile.of(fileName, mediaType, body)), null, author);
        }

        public static Begin ofBundle(String operationKey, List<BeginFile> files, String author) {
            return new Begin(operationKey, files, null, author);
        }

        public Begin withOperationKey(String key) {
            return new Begin(key, files, purpose, hostAttestation);
        }

        public Begin withPurpose(String newPurpose) {
            return new Begin(operationKey, files, newPurpose, hostAttestation);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Chunk(String uploadId, Long sequence, String data, String chunkSha256,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static Chunk of(String uploadId, long sequence, byte[] bytes, String author) {
            return new Chunk(uploadId, sequence, Base64.getEncoder().encodeToString(bytes),
                    AttachmentCalls.sha256(bytes), author);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Finish(String attachmentId, @JsonProperty("_hostAttestation") String hostAttestation) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Status(String uploadId, String attachmentId,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static Status ofUpload(String uploadId, String author) {
            return new Status(uploadId, null, author);
        }

        public static Status ofAttachment(String attachmentId, String author) {
            return new Status(null, attachmentId, author);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Read(String attachmentId, Integer seq, Long offsetBytes, Long maxBytes,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static Read of(String attachmentId, long offsetBytes, long maxBytes, String author) {
            return new Read(attachmentId, null, offsetBytes, maxBytes, author);
        }
    }
}
