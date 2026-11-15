package org.pgledger.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import org.pgledger.application.attachment.UploadCommands;

/**
 * The results of the five attachment tools as they go on the wire (the common design of the three products, section 3): the
 * camel case keys of that design, written as the component names; no path and no storage key is among them.
 */
public final class AttachmentOutputs {

    private AttachmentOutputs() {
    }

    /** What {@code artifact-begin} answers. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Begun(@JsonProperty("uploadId") String uploadId, @JsonProperty("maxChunkBytes") long maxChunkBytes,
            @JsonProperty("expiresAt") Instant expiresAt) {
    }

    /** What {@code artifact-chunk} answers: the number of the chunk and the plain bytes the upload holds now. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Received(@JsonProperty("sequence") long sequence, @JsonProperty("received") long received) {
    }

    /** What {@code artifact-finish} answers. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Finished(@JsonProperty("attachmentId") String attachmentId, @JsonProperty("size") long size,
            @JsonProperty("sha256") String sha256) {
    }

    /**
     * What {@code artifact-status} answers for an upload or an attachment; {@code expiresAt} and {@code sequence} are null for an
     * attachment.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Status(@JsonProperty("id") String id, @JsonProperty("state") String state,
            @JsonProperty("fileName") String fileName, @JsonProperty("mediaType") String mediaType,
            @JsonProperty("size") long size, @JsonProperty("sha256") String sha256,
            @JsonProperty("expiresAt") Instant expiresAt, @JsonProperty("received") long received,
            @JsonProperty("sequence") Long sequence) {
    }

    /**
     * One page of a body: {@code size} is the number of bytes of the page, {@code data} is the page in base64, and {@code text} is the
     * page as text when the media type is a text or a JSON type and the bytes are valid UTF-8 (otherwise null).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(@JsonProperty("attachmentId") String attachmentId, @JsonProperty("offsetBytes") long offsetBytes,
            @JsonProperty("totalBytes") long totalBytes, @JsonProperty("size") int size,
            @JsonProperty("data") String data, @JsonProperty("mediaType") String mediaType,
            @JsonProperty("fileName") String fileName, @JsonProperty("text") String text) {
    }

    public static Begun of(UploadCommands.Begun begun) {
        return new Begun(begun.uploadId(), begun.maxChunkBytes(), begun.expiresAt());
    }

    public static Received of(UploadCommands.Received received) {
        return new Received(received.sequence(), received.received());
    }

    public static Finished of(UploadCommands.Finished finished) {
        return new Finished(finished.attachmentId(), finished.size(), finished.sha256());
    }

    public static Status of(UploadCommands.Status status) {
        return new Status(status.id(), status.state(), status.fileName(), status.mediaType(), status.size(),
                status.sha256(), status.expiresAt(), status.received(), status.sequence());
    }

    public static Page of(UploadCommands.ReadPage page) {
        return new Page(page.attachmentId(), page.offsetBytes(), page.totalBytes(), page.data().length,
                Base64.getEncoder().encodeToString(page.data()), page.mediaType(), page.fileName(),
                isTextual(page.mediaType()) ? utf8(page.data()) : null);
    }

    /** A {@code text/*} type or a JSON type ({@code application/json}, {@code application/...+json}); parameters are ignored. */
    private static boolean isTextual(String mediaType) {
        String type = mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return type.startsWith("text/") || "application/json".equals(type) || type.endsWith("+json");
    }

    /** The bytes as text, or null when they are not valid UTF-8 (a page may end inside a multi-byte character). */
    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notText) {
            // not a failure: a page of a binary body, or one that was cut inside a character, has no text form
            return null;
        }
    }
}
