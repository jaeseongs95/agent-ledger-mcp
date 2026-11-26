// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.veridger.application.attachment.AttachmentProperties;
import org.veridger.cli.ErrorCode;
import org.veridger.cli.UsageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures one local regular file in a single streaming pass before upload ({@code attachments-prepare}), producing the
 * four declaration fields accepted by {@code artifact_begin} ({@code fileName}, {@code size}, {@code sha256} as 64
 * lowercase hex characters, {@code mediaType}). Rejects non-regular files, unreadable files, and files modified while
 * being read. Connects to nothing.
 */
public record PreparedFile(String fileName, long size, String sha256, String mediaType) {

    private static final Logger LOG = LoggerFactory.getLogger(PreparedFile.class);
    private static final String UNKNOWN_MEDIA_TYPE = "application/octet-stream";
    private static final int BUFFER_BYTES = AttachmentProperties.CHUNK_BYTES;

    /** Size in bytes and 64-hex SHA-256 captured from a stable regular file snapshot. */
    public record Measure(long size, String sha256) {
    }

    /** Measures {@code file} and builds the declaration record with {@code name} or the file's own filename. */
    public static PreparedFile of(Path file, String name) {
        Measure measure = measure(file);
        Path own = file.getFileName();
        String fileName = name != null ? name : own == null ? file.toString() : own.toString();
        return new PreparedFile(fileName, measure.size(), measure.sha256(), mediaType(file));
    }

    /** Streams {@code file} once to compute its byte length and SHA-256, failing if it changes during the read. */
    public static Measure measure(Path file) {
        BasicFileAttributes before = attributes(file);
        MessageDigest digest = newSha256();
        long count = 0L;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            for (int read = in.readNBytes(buffer, 0, buffer.length); read > 0; read = in.readNBytes(buffer, 0, buffer.length)) {
                digest.update(buffer, 0, read);
                count += read;
            }
        } catch (IOException cause) {
            throw unreadable(file, cause);
        }
        BasicFileAttributes after = attributes(file);
        if (count != before.size() || before.size() != after.size()
                || !Objects.equals(before.lastModifiedTime(), after.lastModifiedTime())) {
            throw new UsageException(ErrorCode.ATTACHMENTS_FILE_INVALID, String.valueOf(file),
                    "changed while being read");
        }
        return new Measure(count, HexFormat.of().formatHex(digest.digest()));
    }

    private static BasicFileAttributes attributes(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw new UsageException(ErrorCode.ATTACHMENTS_FILE_INVALID, String.valueOf(file),
                        "not a regular file");
            }
            return attributes;
        } catch (IOException cause) {
            throw unreadable(file, cause);
        }
    }

    private static UsageException unreadable(Path file, IOException cause) {
        LOG.debug("The file {} could not be read.", file, cause);
        return new UsageException(ErrorCode.ATTACHMENTS_FILE_INVALID, String.valueOf(file),
                cause.getClass().getSimpleName());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static String mediaType(Path file) {
        try {
            String probed = Files.probeContentType(file);
            return probed == null ? UNKNOWN_MEDIA_TYPE : probed;
        } catch (IOException unknown) {
            LOG.debug("The media type of {} could not be detected.", file.getFileName(), unknown);
            return UNKNOWN_MEDIA_TYPE;
        }
    }
}
