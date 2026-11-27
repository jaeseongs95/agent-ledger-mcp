// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.update;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.veridger.admin.app.LedgerAdminProperties;
import org.veridger.domain.value.OwnerIdentity;

/**
 * Orchestrates Veridger release checks, schema-compatibility gating, SHA-256 artifact verification, and staging of
 * least-privilege OS update requests.
 */
@Service
public final class UpdateService {

    /** Server JARs downloaded, verified against {@code SHA256SUMS}, and staged for host update. */
    public static final List<String> SERVER_JARS = List.of(
            "ledger-cli.jar", "ledger-mcp-server.jar", "ledger-admin-web.jar");
    private static final String SHA256SUMS = "SHA256SUMS";
    private static final long MAX_SUMS_BYTES = 1_048_576L;
    private static final long MAX_JAR_BYTES = 134_217_728L;
    private static final Duration INSPECT_CACHE_TTL = Duration.ofMinutes(5);

    private record CachedRelease(ReleaseInfo release, Instant expiresAt) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(UpdateService.class);

    /** High-level availability classification shown on {@code /updates}. */
    public enum Availability {
        DISABLED,
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        SCHEMA_MIGRATION_REQUIRED,
        CHECK_FAILED
    }

    /** Complete view model rendered on {@code /updates}. */
    public record UpdateView(String currentVersion, int currentSchemaVersion, String repository,
            Availability availability, String latestVersion, String releaseUrl, int requiredSchemaVersion,
            boolean canApply, UpdateStatus queueStatus) {
    }

    /** Result of one owner-initiated {@code POST /updates/apply} attempt. */
    public record ApplyResult(boolean accepted, String flashKey, String targetVersion) {
    }

    private final LedgerAdminProperties.Update config;
    private final ReleaseClient releases;
    private final ChecksumVerifier verifier;
    private final UpdateQueue queue;
    private final Clock clock;
    private final ReentrantLock applyLock = new ReentrantLock();
    private final AtomicReference<CachedRelease> latestCache = new AtomicReference<>();

    public UpdateService(LedgerAdminProperties properties, ReleaseClient releases, ChecksumVerifier verifier,
            UpdateQueue queue, Clock clock) {
        this.config = properties.update();
        this.releases = releases;
        this.verifier = verifier;
        this.queue = queue;
        this.clock = clock;
    }

    /**
     * Evaluates current version, host updater status, and latest release availability without mutating anything.
     * Caches successful GitHub release metadata per service instance for {@link #INSPECT_CACHE_TTL} (using the
     * injected {@link Clock}, no {@code ThreadLocal} or mutable static) to avoid rate-limiting and repeated HTTPS
     * round-trips on page reloads; failed checks are never cached.
     */
    public UpdateView inspect() {
        UpdateStatus status = queue.readStatus();
        if (!config.enabled()) {
            return new UpdateView(config.currentVersion(), config.currentSchemaVersion(), config.repository(),
                    Availability.DISABLED, "", "", config.currentSchemaVersion(), false, status);
        }
        SemVer current = SemVer.parse(config.currentVersion()).orElse(new SemVer(0, 0, 0));
        ReleaseInfo latest;
        try {
            latest = latestCached();
        } catch (IOException e) {
            LOG.warn("could not check latest Veridger release for {}", config.repository(), e);
            return new UpdateView(config.currentVersion(), config.currentSchemaVersion(), config.repository(),
                    Availability.CHECK_FAILED, "", "", config.currentSchemaVersion(), false, status);
        }
        Availability availability = classify(current, latest);
        boolean canApply = availability == Availability.UPDATE_AVAILABLE
                && status.state() != UpdateStatus.State.PENDING;
        return new UpdateView(config.currentVersion(), config.currentSchemaVersion(), config.repository(),
                availability, latest.version().text(), latest.htmlUrl(), latest.requiredSchemaVersion(),
                canApply, status);
    }

    private ReleaseInfo latestCached() throws IOException {
        Instant now = clock.instant();
        CachedRelease cached = latestCache.get();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.release();
        }
        ReleaseInfo fresh = releases.fetchLatest();
        latestCache.set(new CachedRelease(fresh, now.plus(INSPECT_CACHE_TTL)));
        return fresh;
    }

    /**
     * Re-fetches the latest release, verifies schema compatibility and every SHA-256 digest against {@code SHA256SUMS},
     * and atomically stages the update request for the host updater.
     */
    public ApplyResult applyLatest(OwnerIdentity actor) {
        if (!config.enabled()) {
            return new ApplyResult(false, "updates.error.disabled", "");
        }
        applyLock.lock();
        try {
            return applyLatestLocked(actor);
        } finally {
            applyLock.unlock();
        }
    }

    private ApplyResult applyLatestLocked(OwnerIdentity actor) {
        UpdateStatus status = queue.readStatus();
        if (status.state() == UpdateStatus.State.PENDING) {
            return new ApplyResult(false, "updates.error.alreadyPending", status.targetVersion());
        }
        SemVer current = SemVer.parse(config.currentVersion()).orElse(new SemVer(0, 0, 0));
        ReleaseInfo latest;
        try {
            latest = releases.fetchLatest();
            latestCache.set(new CachedRelease(latest, clock.instant().plus(INSPECT_CACHE_TTL)));
        } catch (IOException e) {
            LOG.warn("update aborted: could not fetch latest release", e);
            return new ApplyResult(false, "updates.error.checkFailed", "");
        }
        Availability availability = classify(current, latest);
        if (availability == Availability.UP_TO_DATE) {
            return new ApplyResult(false, "updates.error.upToDate", latest.version().text());
        }
        if (availability == Availability.SCHEMA_MIGRATION_REQUIRED) {
            return new ApplyResult(false, "updates.error.schemaRequired", latest.version().text());
        }

        Map<String, ReleaseAsset> assetByName = new LinkedHashMap<>();
        for (ReleaseAsset asset : latest.assets()) {
            assetByName.put(asset.name(), asset);
        }
        ReleaseAsset sumsAsset = assetByName.get(SHA256SUMS);
        if (sumsAsset == null) {
            LOG.warn("update aborted: release {} is missing {}", latest.tagName(), SHA256SUMS);
            return new ApplyResult(false, "updates.error.missingAssets", latest.version().text());
        }
        Map<String, ReleaseAsset> jarAssets = new LinkedHashMap<>();
        for (String jarName : SERVER_JARS) {
            ReleaseAsset asset = assetByName.get(jarName);
            if (asset == null) {
                LOG.warn("update aborted: release {} is missing {}", latest.tagName(), jarName);
                return new ApplyResult(false, "updates.error.missingAssets", latest.version().text());
            }
            jarAssets.put(jarName, asset);
        }

        Path tempDir = null;
        try {
            byte[] sumsBytes = releases.downloadBytes(sumsAsset.downloadUrl(), MAX_SUMS_BYTES);
            String sumsText = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(sumsBytes)).toString();
            Map<String, String> expectedHashes = verifier.parseManifest(sumsText);

            tempDir = queue.createVerificationDirectory();
            Map<String, Path> downloaded = new LinkedHashMap<>();
            Map<String, String> verifiedHashes = new LinkedHashMap<>();
            for (Map.Entry<String, ReleaseAsset> entry : jarAssets.entrySet()) {
                String jarName = entry.getKey();
                String expected = expectedHashes.get(jarName);
                if (expected == null) {
                    LOG.warn("update aborted: SHA256SUMS does not list {}", jarName);
                    return new ApplyResult(false, "updates.error.checksumFailed", latest.version().text());
                }
                Path file = tempDir.resolve(jarName);
                releases.downloadToFile(entry.getValue().downloadUrl(), file, MAX_JAR_BYTES);
                String verified = verifier.verify(file, expected);
                downloaded.put(jarName, file);
                verifiedHashes.put(jarName, verified);
            }
            queue.stageAndRequest(latest, downloaded, verifiedHashes, sumsText, actor.value(), clock.instant());
            LOG.info("staged Veridger update {} requested by {}", latest.version().text(), actor.value());
            return new ApplyResult(true, "updates.flash.queued", latest.version().text());
        } catch (IllegalArgumentException | IOException e) {
            LOG.warn("update verification or staging failed for {}", latest.tagName(), e);
            return new ApplyResult(false, "updates.error.checksumFailed", latest.version().text());
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    private Availability classify(SemVer current, ReleaseInfo latest) {
        if (!latest.version().isNewerThan(current)) {
            return Availability.UP_TO_DATE;
        }
        if (latest.version().isMajorUpgradeFrom(current)
                || latest.requiredSchemaVersion() > config.currentSchemaVersion()) {
            return Availability.SCHEMA_MIGRATION_REQUIRED;
        }
        return Availability.UPDATE_AVAILABLE;
    }

    private static void cleanupTempDir(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        for (String name : SERVER_JARS) {
            try {
                Files.deleteIfExists(dir.resolve(name));
            } catch (IOException ignored) {
                LOG.debug("could not delete temp jar {}", name, ignored);
            }
        }
        try {
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            LOG.debug("could not delete temp update directory", ignored);
        }
    }
}
