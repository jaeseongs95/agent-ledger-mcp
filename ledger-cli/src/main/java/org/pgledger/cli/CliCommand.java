// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli;

import java.util.Objects;
import java.util.Optional;

/** The commands this tool offers; the usage text lists them in this order. */
public enum CliCommand implements CommandName {

    SECRET_SET("secret-set"),
    TOKEN_GENERATE("token-generate"),
    DRIVER_REGISTER("driver-register"),
    MIGRATE("migrate"),
    SETUP("setup"),
    KEY_REGISTER("key-register"),
    KEY_REVOKE("key-revoke"),
    TOKEN_ISSUE("token-issue"),
    TOKEN_REVOKE("token-revoke"),
    TOKEN_LIST("token-list"),
    PASSKEY_CODE("passkey-code"),
    PROJECT_ADD("project-add"),
    CODE_SET("code-set"),
    CODES("codes"),
    GRANT("grant"),
    REVOKE("revoke"),
    GRANTS("grants"),
    ATTACHMENTS_CHECK("attachments-check"),
    ATTACHMENTS_REKEY("attachments-rekey"),
    ATTACHMENTS_KEY_CREATE("attachments-key-create"),
    ATTACHMENTS_PREPARE("attachments-prepare"),
    ATTACHMENTS_SEND("attachments-send"),
    MCP_CHECK("mcp-check"),
    RENDER_DEPLOY("render-deploy"),
    PREPARE_HOST("prepare-host"),
    INSTALL_DEFAULT("install-default");

    private final String cliName;

    CliCommand(String cliName) {
        this.cliName = cliName;
    }

    public String cliName() {
        return cliName;
    }

    /**
     * Whether the command works on the ledger database. The others never need it: a registered driver and a database that is
     * down or whose password is not stored yet must not stop them, so an installation that stopped half way can be run again
     * ({@code secret-set} has to be able to store the password that is missing).
     */
    public boolean usesDatabase() {
        return switch (this) {
            case SECRET_SET, TOKEN_GENERATE, ATTACHMENTS_PREPARE, ATTACHMENTS_SEND, MCP_CHECK, RENDER_DEPLOY,
                    PREPARE_HOST, INSTALL_DEFAULT -> false;
            default -> true;
        };
    }

    /** Message key of this command line in the usage text. */
    public String usageKey() {
        return "usage." + cliName;
    }

    /** The one place where a command line word becomes a command. */
    public static Optional<CliCommand> parse(String word) {
        for (CliCommand command : values()) {
            if (Objects.equals(command.cliName, word)) {
                return Optional.of(command);
            }
        }
        return Optional.empty();
    }
}
