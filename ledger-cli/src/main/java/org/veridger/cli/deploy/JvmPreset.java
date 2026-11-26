// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

/**
 * The options of the Java virtual machine of everything this product starts, written in this one place (a preset): the
 * services (the WinSW configuration, the systemd unit) get them from here, and no other class writes a JVM option. The one option is the default time zone: the database layer refuses to run in another one
 * ({@code UtcSessionCheck}, JVM_ZONE_NOT_UTC), and every time the product keeps is UTC. The entry point of each program also pins
 * the zone to UTC before it reads it ({@code JvmZone}), so a command given without the option (as the README gives it) runs on a
 * host whose zone is not UTC; the option stays in the definitions of the services and in the installers, so a JVM that is
 * started through them has the zone from its first moment.
 *
 * <p>The two installers ({@code deploy/install.sh}, {@code deploy/install.cmd}) start Java before this class can tell them
 * anything, so each defines the same option once, in one variable at its top, and uses the variable for every command; a test
 * compares both with {@link #OPTIONS}. Heap and the like are not set: the JVM sizes itself from the host (the container-aware
 * defaults of the JDK) until a measurement says otherwise.
 */
final class JvmPreset {

    /** The options for short-lived CLI commands and installers, in the order written before {@code -jar}. */
    static final String OPTIONS = "-Duser.timezone=UTC";

    /**
     * The options for long-running server services (systemd and WinSW): pins UTC and forces immediate JVM exit on
     * {@code OutOfMemoryError} so the service supervisor restarts the server cleanly instead of leaving a dead
     * Tomcat poller thread accepting connections without responding.
     */
    static final String SERVICE_OPTIONS = OPTIONS + " -XX:+ExitOnOutOfMemoryError";

    private JvmPreset() {
    }

    /** The arguments of a Java command line that runs a CLI jar: the preset and {@code -jar <jar>}. */
    static String jar(String jar) {
        return OPTIONS + " -jar " + jar;
    }

    /** The arguments of a Java command line that runs a managed server service jar. */
    static String serviceJar(String jar) {
        return SERVICE_OPTIONS + " -jar " + jar;
    }
}
