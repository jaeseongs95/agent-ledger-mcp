package org.pgledger.cli.deploy;

/**
 * The options of the Java virtual machine of everything this product starts, written in this one place (a preset): the
 * services (the WinSW configuration, the systemd unit) get them from here, and no other class writes a JVM option. The one option is the default time zone: the database layer refuses to run in another one
 * ({@code UtcSessionCheck}, JVM_ZONE_NOT_UTC), and every time the product keeps is UTC.
 *
 * <p>The two installers ({@code deploy/install.sh}, {@code deploy/install.cmd}) start Java before this class can tell them
 * anything, so each defines the same option once, in one variable at its top, and uses the variable for every command; a test
 * compares both with {@link #OPTIONS}. Heap and the like are not set: the JVM sizes itself from the host (the container-aware
 * defaults of the JDK) until a measurement says otherwise.
 */
final class JvmPreset {

    /** The options, in the order they are written before {@code -jar}. */
    static final String OPTIONS = "-Duser.timezone=UTC";

    private JvmPreset() {
    }

    /** The arguments of a Java command line that runs an executable jar: the preset and {@code -jar <jar>}. */
    static String jar(String jar) {
        return OPTIONS + " -jar " + jar;
    }
}
