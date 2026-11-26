// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.update;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict {@code MAJOR.MINOR.PATCH} semantic version parsed from a release tag ({@code v4.1.0} or {@code 4.1.0}). */
public record SemVer(int major, int minor, int patch) implements Comparable<SemVer> {

    private static final Pattern PATTERN = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)$");

    /** Parses a release version or tag; pre-releases and malformed texts return empty so unstable builds are skipped. */
    public static Optional<SemVer> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(raw.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SemVer(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))));
        } catch (NumberFormatException overflow) {
            return Optional.empty();
        }
    }

    public boolean isNewerThan(SemVer other) {
        return compareTo(other) > 0;
    }

    public boolean isMajorUpgradeFrom(SemVer other) {
        return major > other.major;
    }

    public String text() {
        return major + "." + minor + "." + patch;
    }

    @Override
    public int compareTo(SemVer other) {
        int cmp = Integer.compare(major, other.major);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(minor, other.minor);
        return cmp != 0 ? cmp : Integer.compare(patch, other.patch);
    }
}
