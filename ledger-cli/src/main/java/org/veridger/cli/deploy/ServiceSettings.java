// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

/**
 * The settings files, Spring Boot properties in YAML, the same on every platform: {@code application.yaml} (what the two
 * services and the owner commands share: the JDBC URL, the account of the application, the schema), and one file per
 * service ({@code mcp.yaml}, {@code admin.yaml}: its port, its allowed hosts, for the admin web where the driver
 * registration is). The paths of the admin web's driver registration follow {@link DeployLayout}. No file holds a secret:
 * the passwords and tokens arrive from the secret store ({@code optional:configtree:} in {@code spring.config.import}).
 */
final class ServiceSettings {

    private ServiceSettings() {
    }

    /** What the services and the owner commands share. */
    static String shared(DeployParameters p) {
        StringBuilder yaml = new StringBuilder();
        Text.line(yaml, Text.GENERATED);
        Text.line(yaml, "# Your own settings (for example ledger.admin.oidc.owner-email) belong in local.yaml next to this file: it is read after this one and never replaced.");
        Text.line(yaml, "ledger:");
        Text.line(yaml, "  jdbc:");
        Text.line(yaml, "    url: " + Text.quoted(p.jdbcUrl()));
        Text.line(yaml, "    username: " + Text.quoted(p.appAccount()));
        if (p.schema() != null) {
            Text.line(yaml, "  schema:");
            Text.line(yaml, "    name: " + Text.quoted(p.schema()));
        }
        // The attachments: where the files are and where the keys are (every key of the ten keeps its default). The key that new
        // uploads are written with, ledger.attachments.active-key-id, is not rendered: the installer cannot know it (setup makes
        // the first key and writes the id into local.yaml, which no run of the installer replaces; a rotation changes it there).
        Text.line(yaml, "  attachments:");
        Text.line(yaml, "    directory: " + Text.quoted(p.layout().attachmentDirectory()));
        Text.line(yaml, "    key-directory: " + Text.quoted(p.layout().attachmentKeyDirectory()));
        return yaml.toString();
    }

    static String mcp(DeployParameters p) {
        StringBuilder yaml = head(p.mcpPort());
        Text.line(yaml, "  mcp:");
        hosts(yaml, p.mcpHost());
        return yaml.toString();
    }

    static String admin(DeployParameters p) {
        StringBuilder yaml = head(p.adminPort());
        Text.line(yaml, "  admin:");
        hosts(yaml, p.adminHost());
        passkey(yaml, p.adminHost());
        Text.line(yaml, "    driver-file: " + Text.quoted(p.layout().driverStateDirectory() + "/jdbc-driver.yaml"));
        Text.line(yaml, "    driver-directory: " + Text.quoted(p.layout().etc() + "/drivers"));
        Text.line(yaml, "    mcp:");
        Text.line(yaml, "      health-url: " + Text.quoted("http://127.0.0.1:" + p.mcpPort() + "/healthz"));
        Text.line(yaml, "    update:");
        Text.line(yaml, "      directory: " + Text.quoted(p.layout().updateDirectory()));
        Text.line(yaml, "      schema-migration-marker: " + Text.quoted(p.layout().lib() + "/.schema-updates"));
        return yaml.toString();
    }

    /**
     * The relying party of the owner's passkeys for the host name of the admin web, so that a remote installation needs no hand
     * edit: the {@code rp-id} is the name (a passkey belongs to one host name) and the one origin is {@code https://<name>}, the
     * address of the operator's reverse proxy. Nothing is written without a name: the admin web then derives {@code localhost}
     * and {@code http://localhost:<port>}.
     */
    private static void passkey(StringBuilder yaml, String adminHost) {
        if (adminHost == null) {
            return;
        }
        Text.line(yaml, "    passkey:");
        Text.line(yaml, "      rp-id: " + Text.quoted(adminHost));
        Text.line(yaml, "      origins:");
        Text.line(yaml, "        - " + Text.quoted("https://" + adminHost));
    }

    /** The port, up to the key {@code ledger:} (left open for the service). Both servers listen on loopback only (fixed in code, not a setting). */
    private static StringBuilder head(int port) {
        StringBuilder yaml = new StringBuilder();
        Text.line(yaml, Text.GENERATED);
        Text.line(yaml, "server:");
        Text.line(yaml, "  port: " + port);
        Text.line(yaml, "ledger:");
        return yaml;
    }

    /** The loopback names (the health check and a local call), always, and the one host name of the operator's proxy when there is one. */
    private static void hosts(StringBuilder yaml, String publicHost) {
        Text.line(yaml, "    allowed-hosts:");
        Text.line(yaml, "      - '127.0.0.1'");
        Text.line(yaml, "      - 'localhost'");
        if (publicHost != null) {
            Text.line(yaml, "      - " + Text.quoted(publicHost));
        }
    }
}
