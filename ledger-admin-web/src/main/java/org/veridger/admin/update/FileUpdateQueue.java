// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.update;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.veridger.admin.app.LedgerAdminProperties;

/**
 * File-backed {@link UpdateQueue} inside the admin web's writable settings directory ({@code ledger.admin.update.directory}).
 * Refuses symbolic links at every path segment and writes {@code request.properties} atomically via temporary rename so
 * the external host updater never observes a half-written request.
 */
@Component
public final class FileUpdateQueue implements UpdateQueue {

    private static final Logger LOG = LoggerFactory.getLogger(FileUpdateQueue.class);
    private static final String REQUEST_FILE = "request.properties";
    private static final String STATUS_FILE = "status.properties";
    private static final String STAGED_DIR = "staged";
    private static final String SHA256SUMS = "SHA256SUMS";
    private static final long MAX_STATE_FILE_BYTES = 64L * 1024L;

    private final Path directory;

    public FileUpdateQueue(LedgerAdminProperties properties) {
        this.directory = properties.update().directory().toAbsolutePath().normalize();
    }

    @Override
    public UpdateStatus readStatus() {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return UpdateStatus.idle();
        }
        Path request = directory.resolve(REQUEST_FILE);
        if (Files.isRegularFile(request, LinkOption.NOFOLLOW_LINKS)) {
            Properties props = loadProperties(request);
            return new UpdateStatus(UpdateStatus.State.PENDING, props.getProperty("targetVersion", ""),
                    props.getProperty("requestedAt", ""), "");
        }
        Path status = directory.resolve(STATUS_FILE);
        if (Files.isRegularFile(status, LinkOption.NOFOLLOW_LINKS)) {
            Properties props = loadProperties(status);
            UpdateStatus.State state = parseState(props.getProperty("state", "IDLE"));
            return new UpdateStatus(state, props.getProperty("targetVersion", ""),
                    props.getProperty("updatedAt", ""), props.getProperty("detail", ""));
        }
        return UpdateStatus.idle();
    }

    @Override
    public Path createVerificationDirectory() throws IOException {
        rejectSymlink(directory);
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("update directory is not a real directory");
        }
        Path verifyDir = directory.resolve(".verify-" + UUID.randomUUID());
        rejectSymlink(verifyDir);
        return Files.createDirectory(verifyDir);
    }

    @Override
    public void stageAndRequest(ReleaseInfo release, Map<String, Path> verifiedJars, Map<String, String> sha256ByName,
            String sha256SumsContent, String requestedBy, Instant requestedAt) throws IOException {
        rejectSymlink(directory);
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("update directory is not a real directory");
        }
        Path requestTarget = directory.resolve(REQUEST_FILE);
        rejectSymlink(requestTarget);
        if (Files.exists(requestTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("an update request is already pending");
        }
        Path staged = directory.resolve(STAGED_DIR);
        rejectSymlink(staged);
        Files.createDirectories(staged);
        if (!Files.isDirectory(staged, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("staged directory is not a real directory");
        }

        for (Map.Entry<String, Path> entry : verifiedJars.entrySet()) {
            Path destination = staged.resolve(entry.getKey()).normalize();
            if (!destination.startsWith(staged)) {
                throw new IOException("staged artifact path escaped staging directory");
            }
            rejectSymlink(destination);
            Path jarTmp = staged.resolve(entry.getKey() + ".tmp");
            rejectSymlink(jarTmp);
            Files.copy(entry.getValue(), jarTmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(jarTmp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        Path sumsTarget = staged.resolve(SHA256SUMS);
        rejectSymlink(sumsTarget);
        Path sumsTmp = staged.resolve(SHA256SUMS + ".tmp");
        rejectSymlink(sumsTmp);
        Files.writeString(sumsTmp, sha256SumsContent, StandardCharsets.UTF_8);
        Files.move(sumsTmp, sumsTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        Properties request = new Properties();
        request.setProperty("targetVersion", release.version().text());
        request.setProperty("tagName", release.tagName());
        request.setProperty("requestedBy", requestedBy);
        request.setProperty("requestedAt", requestedAt.toString());
        for (Map.Entry<String, String> entry : sha256ByName.entrySet()) {
            request.setProperty("sha256." + entry.getKey(), entry.getValue());
        }

        Path requestTmp = directory.resolve(REQUEST_FILE + ".tmp");
        rejectSymlink(requestTmp);
        try (Writer writer = Files.newBufferedWriter(requestTmp, StandardCharsets.UTF_8)) {
            request.store(writer, "Veridger staged update request");
        }
        Files.move(requestTmp, requestTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("refusing symbolic link in update directory: " + path.getFileName());
        }
    }

    private static Properties loadProperties(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_STATE_FILE_BYTES) {
                LOG.warn("refusing oversized or non-regular update state file {}", file.getFileName());
                return new Properties();
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (!raw.isEmpty() && raw.charAt(0) == '\uFEFF') {
                raw = raw.substring(1);
            }
            Properties parsed = new Properties();
            try (Reader reader = new java.io.StringReader(raw)) {
                parsed.load(reader);
            }
            return parsed;
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("could not read update state file {}", file.getFileName(), e);
            return new Properties();
        }
    }

    private static UpdateStatus.State parseState(String raw) {
        if ("PENDING".equals(raw)) {
            return UpdateStatus.State.PENDING;
        }
        if ("SUCCEEDED".equals(raw)) {
            return UpdateStatus.State.SUCCEEDED;
        }
        if ("ROLLED_BACK".equals(raw)) {
            return UpdateStatus.State.ROLLED_BACK;
        }
        if ("RECOVERY_REQUIRED".equals(raw)) {
            return UpdateStatus.State.RECOVERY_REQUIRED;
        }
        if ("FAILED".equals(raw)) {
            return UpdateStatus.State.FAILED;
        }
        return UpdateStatus.State.IDLE;
    }
}
