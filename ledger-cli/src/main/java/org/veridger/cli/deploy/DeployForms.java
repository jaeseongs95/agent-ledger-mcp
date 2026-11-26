// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import java.nio.file.Path;
import java.util.regex.Pattern;
import org.veridger.cli.ErrorCode;
import org.veridger.cli.UsageException;

/**
 * The plain forms a deployment value may have, as the regular expressions of the Jakarta constraints of
 * {@link DeployParameters}. The values are written into unit files, XML and YAML settings, where a space, a quote, a newline,
 * {@code $} (a Spring placeholder), {@code %} (a systemd specifier) or {@code <} would change what the file means, so each value
 * is restricted to the plain characters it needs. Nothing is quoted or escaped to make a value pass.
 */
public final class DeployForms {

    static final int FIRST_UNPRIVILEGED_PORT = 1024;
    static final int LAST_PORT = 65535;
    static final int MAX_PATH = 200;
    static final int MAX_HOST = 253;
    static final int MAX_URL = 600;

    /**
     * A path segment of plain characters (letters, digits and {@code . _ + -}); {@code .} and {@code ..} are no segment (they would
     * leave the directory). The form of a whole path, with its slashes and its drive, is the platform's that writes it
     * ({@link ServicePlatform}); this is what the platforms share.
     */
    static final String SEGMENT = "(?!\\.{1,2}(?:/|$))[A-Za-z0-9._+-]+";
    static final String HOST = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*";
    /**
     * The characters of a URI without quotes, backslash, {@code $}, braces and white space, and no password word anywhere:
     * a JDBC URL never holds a password (the passwords are secrets of the secret store).
     */
    static final String URL = "(?!.*(?i:pass(?:word|wd)|pwd))jdbc:[A-Za-z0-9.]+:[A-Za-z0-9._~:/?#\\[\\]@!&*+,;=%()-]+";
    /**
     * The name of an instance becomes a system user, unit and service names, directory names and a file name, so it is a short
     * plain word: lower-case letters, digits and hyphens, a letter first, a letter or digit last, at most 23 characters (the
     * user name of a Linux system is at most 32 and the admin service adds {@code -admin}).
     */
    static final String NAME = "[a-z](?:[a-z0-9-]{0,21}[a-z0-9])?";
    static final String SCHEMA ="[a-z][a-z0-9_]{0,29}";

    private DeployForms() {
    }

    /**
     * Whether the value is a plain host name, the form of {@code --mcp-host} and {@code --admin-host}: lower case, no port, no list.
     * The one place of the rule; the migration tool (which depends on the classes of this module) asks it for its own options of the
     * same name.
     */
    public static boolean isHostName(String value) {
        return value != null && value.length() <= MAX_HOST && Pattern.matches(HOST, value);
    }

    /**
     * The value when it is the name of an instance.
     *
     * @throws UsageException INVALID_DEPLOY_VALUE naming the option, never the value
     */
    public static String requireName(String value, String option) {
        if (value == null || !Pattern.matches(NAME, value) || value.endsWith("-admin")) {
            throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, option);
        }
        return value;
    }

    /** Whether the value has the form and is not longer than a path may be. */
    public static boolean isPath(String value, String form) {
        return value != null && value.length() <= MAX_PATH && Pattern.matches(form, value);
    }

    /**
     * Whether the path is an absolute path of this host (the file system says so, {@link Path#isAbsolute}: no form of any
     * operating system is written here), is not longer than a path may be, and holds at least one name, every name of it a plain
     * segment (no space, no {@code ..}).
     */
    public static boolean isPlainAbsolute(Path path) {
        if (!path.isAbsolute() || path.getNameCount() == 0 || path.toString().length() > MAX_PATH) {
            return false;
        }
        for (Path name : path) {
            if (!Pattern.matches(SEGMENT, name.toString())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The value when it is a path of that form.
     *
     * @throws UsageException INVALID_DEPLOY_VALUE naming the option, never the value
     */
    public static String requirePath(String value, String form, String option) {
        if (!isPath(value, form)) {
            throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, option);
        }
        return value;
    }
}
