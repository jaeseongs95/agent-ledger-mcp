// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.veridger.application.attachment.UploadCommands;

/** Wire output records returned by the five {@link AttachmentTools}. */
public final class AttachmentOutputs {

    private AttachmentOutputs() {
    }

    /** One staged file upload created by {@code artifact_begin}. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BegunFile(@JsonProperty("seq") int seq, @JsonProperty("uploadId") String uploadId) {
    }

    /** What {@code artifact_begin} answers for a bundle upload. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Begun(
            @JsonProperty("attachmentId") String attachmentId,
            @JsonProperty("files") List<BegunFile> files,
            @JsonProperty("maxChunkBytes") long maxChunkBytes,
            @JsonProperty("expiresAt") Instant expiresAt) {
    }

    /** What {@code artifact_chunk} answers: the number of the chunk and the plain bytes the upload holds now. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Received(@JsonProperty("sequence") long sequence, @JsonProperty("received") long received) {
    }

    /** One verified file entry inside a kept attachment bundle. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record KeptFile(
            @JsonProperty("seq") int seq,
            @JsonProperty("fileName") String fileName,
            @JsonProperty("mediaType") String mediaType,
            @JsonProperty("size") long size,
            @JsonProperty("sha256") String sha256) {
    }

    /** What {@code artifact_finish} answers. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Finished(
            @JsonProperty("attachmentId") String attachmentId,
            @JsonProperty("files") List<KeptFile> files) {
    }

    /**
     * What {@code artifact_status} answers for an upload in progress ({@code kind = "upload"}) or a kept attachment
     * bundle ({@code kind = "attachment"}). Never contains a path or storage key.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Status(
            @JsonProperty("kind") String kind,
            @JsonProperty("uploadId") String uploadId,
            @JsonProperty("attachmentId") String attachmentId,
            @JsonProperty("seq") Integer seq,
            @JsonProperty("state") String state,
            @JsonProperty("fileName") String fileName,
            @JsonProperty("mediaType") String mediaType,
            @JsonProperty("size") Long size,
            @JsonProperty("sha256") String sha256,
            @JsonProperty("expiresAt") Instant expiresAt,
            @JsonProperty("received") Long received,
            @JsonProperty("sequence") Long sequence,
            @JsonProperty("files") List<KeptFile> files) {
    }

    /**
     * One page of a bundle file body: {@code size} is the number of bytes of the page, {@code data} is the page in
     * base64, and {@code text} is the page as text when the media type is a text or JSON type and the bytes are valid
     * UTF-8 (otherwise null).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(
            @JsonProperty("attachmentId") String attachmentId,
            @JsonProperty("seq") int seq,
            @JsonProperty("offsetBytes") long offsetBytes,
            @JsonProperty("totalBytes") long totalBytes,
            @JsonProperty("size") int size,
            @JsonProperty("data") String data,
            @JsonProperty("mediaType") String mediaType,
            @JsonProperty("fileName") String fileName,
            @JsonProperty("text") String text) {
    }

    public static Begun of(UploadCommands.Begun begun) {
        return new Begun(
                begun.attachmentId(),
                begun.files().stream().map(f -> new BegunFile(f.seq(), f.uploadId())).toList(),
                begun.maxChunkBytes(),
                begun.expiresAt());
    }

    public static Received of(UploadCommands.Received received) {
        return new Received(received.sequence(), received.received());
    }

    public static Finished of(UploadCommands.Finished finished) {
        return new Finished(finished.attachmentId(), keptFiles(finished.files()));
    }

    public static Status of(UploadCommands.Status status) {
        return new Status(
                status.kind(),
                status.uploadId(),
                status.attachmentId(),
                status.seq(),
                status.state(),
                status.fileName(),
                status.mediaType(),
                status.size(),
                status.sha256(),
                status.expiresAt(),
                status.received(),
                status.sequence(),
                status.files() == null ? null : keptFiles(status.files()));
    }

    public static Page of(UploadCommands.Page page) {
        return new Page(
                page.attachmentId(),
                page.seq(),
                page.offsetBytes(),
                page.totalBytes(),
                page.data().length,
                Base64.getEncoder().encodeToString(page.data()),
                page.mediaType(),
                page.fileName(),
                isTextual(page.mediaType()) ? utf8(page.data()) : null);
    }

    private static List<KeptFile> keptFiles(List<UploadCommands.KeptFile> files) {
        return files.stream()
                .map(f -> new KeptFile(f.seq(), f.fileName(), f.mediaType(), f.size(), f.sha256()))
                .toList();
    }

    private static boolean isTextual(String mediaType) {
        String type = mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return type.startsWith("text/") || "application/json".equals(type) || type.endsWith("+json");
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException notText) {
            return null;
        }
    }
}
