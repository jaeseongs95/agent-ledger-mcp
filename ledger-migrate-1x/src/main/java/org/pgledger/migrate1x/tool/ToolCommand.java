// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.migrate1x.tool;

import java.util.Objects;
import java.util.Optional;
import org.pgledger.cli.CommandName;

/** The commands this tool offers; the usage text lists them in this order, which is the order of an upgrade. */
public enum ToolCommand implements CommandName {

    STOP_1X("stop-1x"),
    IMPORT_SECRETS_1X("import-secrets-1x"),
    IMPORT_1X("import-1x"),
    RETIRE_1X("retire-1x");

    private final String cliName;

    ToolCommand(String cliName) {
        this.cliName = cliName;
    }

    public String cliName() {
        return cliName;
    }

    /**
     * Whether the command works on the ledger database. The others never need it: a registered driver and a database that is
     * down must not stop them (the services are stopped and the secrets are moved before the database is touched).
     */
    public boolean usesDatabase() {
        return switch (this) {
            case STOP_1X, IMPORT_SECRETS_1X -> false;
            case IMPORT_1X, RETIRE_1X -> true;
        };
    }

    /** The first line of the usage text names this program, not {@code ledger-cli}. */
    @Override
    public String usageHeaderKey() {
        return "usage.header.migrate-1x";
    }

    /** Message key of this command line in the usage text. */
    public String usageKey() {
        return "usage." + cliName;
    }

    /** The one place where a command line word becomes a command. */
    public static Optional<ToolCommand> parse(String word) {
        for (ToolCommand command : values()) {
            if (Objects.equals(command.cliName, word)) {
                return Optional.of(command);
            }
        }
        return Optional.empty();
    }
}
