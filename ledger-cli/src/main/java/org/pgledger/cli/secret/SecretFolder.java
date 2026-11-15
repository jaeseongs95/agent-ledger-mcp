package org.pgledger.cli.secret;

import java.util.List;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;

/**
 * The folders of the secret directory, one per use, and the one place that says which secret belongs in which (least
 * privilege: a process puts only the folders it needs into its Spring {@code Environment}, so the MCP server never holds the
 * secrets of the admin web and the other way round). Spring Boot makes the names of the folders and files under a
 * configuration tree the name of the property (https://docs.spring.io/spring-boot/reference/features/external-config.html,
 * "Configuration Trees"), so each folder is imported as a tree of its own ({@code optional:configtree:<secrets>/<folder>/}) and
 * the secret directory itself is never imported.
 *
 * <p>The secrets are named by their property:
 *
 * <ul>
 * <li>{@link #COMMON}: {@code ledger.jdbc.password}, read by the MCP server, the admin web, {@code ledger-cli} and
 *     {@code ledger-migrate-1x}.
 * <li>{@link #ADMIN}: {@code ledger.admin.*} (the admin token and the token of the agents that the admin web refuses) and the
 *     client secret of an OpenID Connect registration ({@code spring.security.oauth2.client.registration.<id>.client-secret}),
 *     read by the admin web only.
 * <li>{@link #MCP}: {@code ledger.mcp.token}, read by the MCP server and by {@code ledger-cli mcp-check}.
 * <li>{@link #TOOL}: {@code ledger.import.source.password} (the 1.x database), read by {@code ledger-migrate-1x} only; no
 *     service reads it.
 * </ul>
 *
 * <p>The installer and the command line tool order the folders the same way ({@link #all()}); the key files of the attachments
 * are no secret of this directory and are kept outside every tree ({@code DeployLayout#attachmentKeyDirectory}).
 */
public enum SecretFolder {

    /** Read by both services and by the tools. */
    COMMON("common"),
    /** Read by the admin web. */
    ADMIN("admin"),
    /** Read by the MCP server and {@code mcp-check}. */
    MCP("mcp"),
    /** Read by the migration tool only. */
    TOOL("tool");

    private static final String OIDC_PREFIX = "spring.security.oauth2.client.registration.";
    private static final String OIDC_SUFFIX = ".client-secret";

    private final String directory;

    SecretFolder(String directory) {
        this.directory = directory;
    }

    /** The name of the folder below the secret directory. */
    public String directory() {
        return directory;
    }

    /** Every folder, in the order the installer makes them. */
    public static List<SecretFolder> all() {
        return List.of(values());
    }

    /**
     * The folder of a secret.
     *
     * @throws FailureException UNKNOWN_SECRET_NAME when the name is none of the secrets of the product (a secret that no
     *         process reads is a mistake, so it is refused before anything is asked or written; the name is not echoed)
     */
    public static SecretFolder of(SecretName name) {
        String value = name.value();
        if (value.equals("ledger.jdbc.password")) {
            return COMMON;
        }
        if (value.equals("ledger.mcp.token")) {
            return MCP;
        }
        if (value.equals("ledger.import.source.password")) {
            return TOOL;
        }
        if (value.startsWith("ledger.admin.") && value.length() > "ledger.admin.".length()) {
            return ADMIN;
        }
        if (value.startsWith(OIDC_PREFIX) && value.endsWith(OIDC_SUFFIX)
                && value.length() > OIDC_PREFIX.length() + OIDC_SUFFIX.length()) {
            return ADMIN;
        }
        throw new FailureException(ErrorCode.UNKNOWN_SECRET_NAME);
    }
}
