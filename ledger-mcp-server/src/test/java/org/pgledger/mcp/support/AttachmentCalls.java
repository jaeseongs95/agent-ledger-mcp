package org.pgledger.mcp.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Base64;
import org.pgledger.domain.value.Sha256;

/**
 * The arguments of the five attachment tools as the tests send them: one record per tool, its components the camel case wire keys
 * of the common design (section 3), {@code _hostAttestation} as written; a key left null is left out of the call. The attestation of
 * a call is the plain author, which the {@link Attester} signs (the way the hook does, with an empty operation key in the claim), or null for a call that
 * carries none.
 */
public final class AttachmentCalls {

    private AttachmentCalls() {
    }

    /** The SHA-256 of the bytes as the design writes it: 64 lowercase hex characters. */
    public static String sha256(byte[] bytes) {
        return Sha256.ofBytes(bytes).hex();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Begin(String operationKey, String fileName, String mediaType, Long size, String sha256,
            String purpose, @JsonProperty("_hostAttestation") String hostAttestation) {

        /** A begin for exactly these bytes. */
        public static Begin of(String operationKey, String fileName, String mediaType, byte[] body, String author) {
            return new Begin(operationKey, fileName, mediaType, (long) body.length, AttachmentCalls.sha256(body), null,
                    author);
        }

        public Begin withOperationKey(String key) {
            return new Begin(key, fileName, mediaType, size, sha256, purpose, hostAttestation);
        }

        public Begin withPurpose(String newPurpose) {
            return new Begin(operationKey, fileName, mediaType, size, sha256, newPurpose, hostAttestation);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Chunk(String uploadId, Long sequence, String data, String chunkSha256,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        /** The chunk with this number made of exactly these bytes. */
        public static Chunk of(String uploadId, long sequence, byte[] bytes, String author) {
            return new Chunk(uploadId, sequence, Base64.getEncoder().encodeToString(bytes),
                    AttachmentCalls.sha256(bytes), author);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Finish(String uploadId, @JsonProperty("_hostAttestation") String hostAttestation) {
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
    public record Read(String attachmentId, Long offsetBytes, Long maxBytes,
            @JsonProperty("_hostAttestation") String hostAttestation) {
    }
}
