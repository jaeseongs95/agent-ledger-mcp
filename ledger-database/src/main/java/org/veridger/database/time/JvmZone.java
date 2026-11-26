// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.time;

/**
 * The default time zone of the JVM of a program of the product: UTC, whatever zone the host has. The database layer refuses to run
 * in another zone ({@code UtcSessionCheck}, JVM_ZONE_NOT_UTC) and every time the product keeps is UTC; the default zone of a JVM
 * comes from the operating system, so a program started as the README gives it ({@code java -jar <jar> <command>}) on a host whose
 * zone is not UTC would be refused without this. The services that the installers define also get {@code -Duser.timezone=UTC}
 * ({@code JvmPreset}); this makes the zone the same for every program, however it is started.
 *
 * <p>The JDK reads the property {@code user.timezone} when the default zone is first asked and never again, so {@link #pinUtc}
 * is the first statement of the {@code main} method of each program (a rule of the architecture tests checks that it is). A zone
 * that was read before it is not changed; the check of the database layer then still refuses it, so a program never runs in a
 * zone that is not UTC.
 */
public final class JvmZone {

    private static final String PROPERTY = "user.timezone";
    private static final String UTC = "UTC";

    private JvmZone() {
    }

    /** Sets the default time zone of this JVM to UTC (the property that the JDK reads once, when the zone is first needed). */
    public static void pinUtc() {
        System.setProperty(PROPERTY, UTC);
    }
}
