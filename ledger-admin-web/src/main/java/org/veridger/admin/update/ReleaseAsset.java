// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.update;

import java.net.URI;

/** One downloadable asset attached to a GitHub release. */
public record ReleaseAsset(String name, URI downloadUrl, long size) {
}
