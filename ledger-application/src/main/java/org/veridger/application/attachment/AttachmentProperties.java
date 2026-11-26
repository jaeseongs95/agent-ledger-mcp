// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.attachment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the attachments ({@code ledger.attachments.*}, the keys of the common design, section 3.1). A value that does
 * not bind, or a key directory inside the attachment directory ({@link AttachmentSettingsException}, a failure of the settings and no
 * error code of a tool), stops the start. The chunk size is no setting: it is the contract
 * constant {@link #CHUNK_BYTES}, the same for every client and every installation.
 *
 * @param directory             where the files of the bodies are kept (below the installation's data directory)
 * @param keyDirectory          where the key files are kept (the installation's {@code <settings folder>/attachment-keys}, beside the secret directory and in no folder that a process imports as a configuration tree, which would make the key files properties of its {@code Environment}); outside {@code directory}
 * @param activeKeyId           the key that new uploads are written with; older keys stay while rows use them. It is null on a new
 *                              installation until {@code setup} has made the first key and written its id (the installer cannot know it
 *                              when it renders the settings, and {@code setup} itself has to start with it missing); a server that has
 *                              no active key does not start (the start check names the missing key)
 * @param maxFileBytes          the largest file
 * @param stagingBudgetBytes    what all uploads that are open may declare together
 * @param attachmentBudgetBytes what all kept attachments (and the open uploads) may hold together; a begin over it is refused and nothing is deleted
 * @param maxOpenPerOwner       how many uploads of one author may be open
 * @param diskMarginBytes       the space that has to stay free on the disk of {@code directory} after a file is written (default 64MB, the
 *                              same as {@code maxFileBytes}: one largest file is always left for the database and the logs); a begin or a
 *                              chunk with {@code usable - size < margin} is refused with ARTIFACT_STORAGE_FULL
 * @param uploadTtl             how long an upload lives after its last chunk
 * @param orphanGrace           how long a file without a row waits before the check moves it away
 * @param reaperInterval        how often the reaper runs
 */
@Validated
@ConfigurationProperties(prefix = "ledger.attachments")
public record AttachmentProperties(@NotNull Path directory, @NotNull Path keyDirectory,
        @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String activeKeyId,
        @DefaultValue("64MB") DataSize maxFileBytes, @DefaultValue("512MB") DataSize stagingBudgetBytes,
        @DefaultValue("10GB") DataSize attachmentBudgetBytes, @DefaultValue("4") int maxOpenPerOwner,
        @DefaultValue("64MB") DataSize diskMarginBytes, @DefaultValue("10m") Duration uploadTtl, @DefaultValue("24h") Duration orphanGrace,
        @DefaultValue("1m") Duration reaperInterval) {

    /** The largest chunk of {@code artifact-chunk} and the largest page of {@code artifact-read}: 256 KiB, a contract constant. */
    public static final int CHUNK_BYTES = 262_144;

    public AttachmentProperties {
        if (directory != null && keyDirectory != null) {
            directory = directory.toAbsolutePath().normalize();
            keyDirectory = keyDirectory.toAbsolutePath().normalize();
            if (keyDirectory.startsWith(directory)) {
                throw new AttachmentSettingsException(AttachmentSettingsException.KEY_DIRECTORY_INSIDE_DIRECTORY);
            }
        }
        require(maxFileBytes.toBytes() >= 1, "max-file-bytes");
        require(stagingBudgetBytes.toBytes() >= maxFileBytes.toBytes(), "staging-budget-bytes (at least max-file-bytes)");
        require(attachmentBudgetBytes.toBytes() >= maxFileBytes.toBytes(), "attachment-budget-bytes (at least max-file-bytes)");
        require(maxOpenPerOwner >= 1, "max-open-per-owner");
        require(diskMarginBytes.toBytes() >= 0, "disk-margin-bytes");
        require(isPositive(uploadTtl), "upload-ttl");
        require(isPositive(orphanGrace), "orphan-grace");
        require(isPositive(reaperInterval), "reaper-interval");
    }

    private static boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    private static void require(boolean condition, String key) {
        if (!condition) {
            throw new IllegalArgumentException("ledger.attachments." + key + " is out of range");
        }
    }
}
