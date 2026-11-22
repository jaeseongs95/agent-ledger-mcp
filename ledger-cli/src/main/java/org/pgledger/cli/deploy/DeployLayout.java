// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli.deploy;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import java.util.Locale;
import org.pgledger.cli.secret.SecretFolder;
import org.pgledger.files.ServicePrincipalAcl;

/**
 * Where an installation puts its files, and the names of it. An installation is an <em>instance</em> with a name (the default
 * is an install default, see {@code InstallDefaults}): the product runs as two services, the MCP server (named {@code <name>}) and the admin web (named
 * {@code <name>-admin}), under <b>one</b> operating system service account that belongs to no person (on Linux the system user
 * {@code <name>}). Its purpose is to keep the secrets of the ledger (the admin token, the database password) away from the
 * accounts the agents run as, so the secret directory is readable by the service account only (on Linux: it is the service user's; on Windows the
 * installing user owns it and the service account LocalService reads it, and nobody else has access). Everything an instance owns
 * (the service names, the user, the jar directory, the settings and secret directory, the WinSW ids) follows the name, so two
 * instances can live on one host. The jar names are constants, and so is the rule that makes the name; a name is only accepted
 * through {@link DeployParameters}. {@code deploy/install.sh} and {@code deploy/install.cmd}, which create the directories,
 * compose the same paths from the name, and a test compares them with these.
 *
 * <p>Paths are written with {@code /} on both platforms (Java and Spring Boot accept it on Windows, and the text of a file is
 * the same wherever it is rendered).
 *
 * @param platform the operating system family the files are for
 * @param name     the name of the instance (already validated)
 * @param lib      the jars (on Windows also the WinSW executable and configuration of each service)
 * @param etc      the settings, the driver registration, the driver jars and the secret directory
 * @param data     what the installation keeps as data of its own, private to the service account: below it the attachment
 *                 directory ({@link #attachmentDirectory()}); Linux {@code /var/lib/<name>}, Windows {@code <home>/data}
 * @param logs     where the service wrapper writes its logs (Windows; null on Linux, where systemd keeps the journal)
 */
public record DeployLayout(Platform platform,
        @Pattern(regexp = DeployForms.NAME, message = DeployOptions.NAME) String name, String lib, String etc, String data,
        String logs) {

    /**
     * The operating system families an installation is rendered for; the one thing a render option decides, and only its name.
     * What each family does and what it accepts (its service definitions, the form of its paths, the host preparation) is its
     * {@code ServicePlatform}, found by this constant in one place.
     */
    public enum Platform {
        /** A Linux server: systemd units. */
        LINUX,
        /** A personal PC or a Windows server: WinSW services. */
        WINDOWS;

        /** The value of {@code --platform} that names it. */
        public String option() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The one service account on Windows: the predefined local account LocalService, which "has minimum privileges on the
     * local computer and presents anonymous credentials on the network"
     * (https://learn.microsoft.com/en-us/windows/win32/services/localservice-account). It is no person's account, it needs no
     * password, and the installer can create nothing else without a PowerShell script or {@code net user} (see
     * {@code deploy/install.cmd}). It is shared by every instance of the host and by every other service that runs as it.
     */
    public static final String WINDOWS_SERVICE_ACCOUNT = ServicePrincipalAcl.WINDOWS_SERVICE_ACCOUNT;
    public static final String MCP_JAR = "ledger-mcp-server.jar";
    public static final String ADMIN_JAR = "ledger-admin-web.jar";
    public static final String CLI_JAR = "ledger-cli.jar";
    /** Where systemd reads the units of the administrator (Spring Boot, "Installing Spring Boot Applications"). */
    public static final String UNIT_DIRECTORY = "/etc/systemd/system";

    /**
     * The layout of a Linux instance: jars below {@code /opt/<name>}, the settings and the secrets below {@code /etc/<name>}, the
     * data below {@code /var/lib/<name>} (the place the Filesystem Hierarchy Standard gives to the variable data of a program).
     */
    public static DeployLayout linux(String name) {
        return new DeployLayout(Platform.LINUX, name, "/opt/" + name, "/etc/" + name, "/var/lib/" + name, null);
    }

    /**
     * The directory below which the installer of Windows ({@code deploy/install.cmd}) puts the home of an instance,
     * {@code C:/ProgramData/<name>}; the tool that works for an installation finds it there when it is not told another home
     * ({@link HostDefaults}).
     */
    public static final String WINDOWS_HOME_ROOT = "C:/ProgramData";

    /** The layout of a Windows instance in the home that the installer gives it, {@code C:/ProgramData/<name>}. */
    public static DeployLayout windows(String name) {
        return windows(name, WINDOWS_HOME_ROOT + "/" + name);
    }

    /** The layout of a Windows instance below one directory (for example {@code C:/ProgramData/<name>}). */
    public static DeployLayout windows(String name, String home) {
        return new DeployLayout(Platform.WINDOWS, name, home + "/lib", home + "/etc", home + "/data", home + "/logs");
    }

    /** Where the files of the attachments are kept: {@code <data>/attachments}, the setting {@code ledger.attachments.directory}. */
    public String attachmentDirectory() {
        return data + "/attachments";
    }

    /**
     * Where the key files of the attachments are kept: {@code <etc>/attachment-keys}, private to the service account like the
     * secret directory (on Windows: the installing user with everything and LocalService with read; the setting
     * {@code ledger.attachments.key-directory}); outside the attachment directory, so a backup of the files never carries the
     * keys by itself, and beside the secret directory, not inside it: Spring Boot makes every file below a configuration tree
     * a property, so a key file below any imported tree would be in the {@code Environment} of the process (see
     * {@link #secretDirectory()}).
     */
    public String attachmentKeyDirectory() {
        return etc + "/attachment-keys";
    }

    /** The secret directory, {@code <etc>/secrets}: it holds one folder per use ({@link #secretFolder}) and is itself no configuration tree. */
    public String secretDirectory() {
        return etc + "/secrets";
    }

    /** The folder of one use below the secret directory: the configuration tree that the processes which need it import. */
    public String secretFolder(SecretFolder folder) {
        return secretDirectory() + "/" + folder.directory();
    }

    /** The name of the MCP server service (systemd unit, WinSW service id). */
    public String mcpName() {
        return name;
    }

    /** The name of the admin web service. */
    public String adminName() {
        return name + "-admin";
    }

    /** The one service account on Linux: the system user of the name of the instance (no login, not root). */
    public String serviceUser() {
        return name;
    }

    /** A name that ends with {@code -admin} would collide with the admin service of the instance without that ending. */
    @AssertTrue(message = DeployOptions.NAME)
    public boolean isNotAnAdminName() {
        return name == null || !name.endsWith("-admin");
    }
}
