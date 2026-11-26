// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.update;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Port bridging the sandboxed Admin Web JVM and the host's external least-privilege updater (systemd path unit on Linux
 * or scheduled task on Windows).
 */
public interface UpdateQueue {

    /** Reads the current queued or completed updater state without modifying files. */
    UpdateStatus readStatus();

    /** Stages verified release artifacts and atomically writes {@code request.properties} to trigger the host updater. */
    void stageAndRequest(ReleaseInfo release, Map<String, Path> verifiedJars, Map<String, String> sha256ByName,
            String sha256SumsContent, String requestedBy, Instant requestedAt) throws IOException;
}
