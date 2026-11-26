// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.update;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Metadata of one published Veridger release fetched from the release feed. */
public record ReleaseInfo(String tagName, SemVer version, String htmlUrl, List<ReleaseAsset> assets,
        int requiredSchemaVersion) {

    public ReleaseInfo {
        assets = List.copyOf(assets);
    }

    /** Finds a release asset by exact filename. */
    public Optional<ReleaseAsset> asset(String name) {
        return assets.stream().filter(a -> Objects.equals(a.name(), name)).findFirst();
    }
}
