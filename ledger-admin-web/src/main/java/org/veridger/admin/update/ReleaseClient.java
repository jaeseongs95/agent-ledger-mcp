// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.update;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/** Port for querying published releases and downloading release artifacts under SSRF and size limits. */
public interface ReleaseClient {

    /** Fetches the latest stable release metadata from the configured release repository. */
    ReleaseInfo fetchLatest() throws IOException;

    /** Downloads a small text/checksum asset into memory with a strict byte limit. */
    byte[] downloadBytes(URI url, long maxBytes) throws IOException;

    /** Streams a binary asset directly to {@code target} with a strict byte limit. */
    void downloadToFile(URI url, Path target, long maxBytes) throws IOException;
}
