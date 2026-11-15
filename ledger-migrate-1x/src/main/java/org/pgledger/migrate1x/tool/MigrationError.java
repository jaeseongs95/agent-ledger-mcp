package org.pgledger.migrate1x.tool;

import org.pgledger.cli.ErrorKey;

/**
 * The reasons a command of the migration tool refuses that {@code ledger-cli} has no code for; the others (the command line, the
 * secret store, the ledger database) are the {@code ErrorCode} of {@code ledger-cli}, whose machinery the tool runs on. Each code
 * has a message under {@link #messageKey()} in the message bundles of the tool.
 */
public enum MigrationError implements ErrorKey {

    // the 1.x environment file (import-secrets-1x, stop-1x)
    ENV_FILE_FAILED,
    ENV_KEYS_MISSING,
    INVALID_1X_VALUE,
    SECRETS_PARTIAL,
    HOSTS_DROPPED,

    // stop-1x
    NOT_1X_SERVICE,
    NO_1X_SERVICE,
    NO_1X_SERVICE_WINDOWS,
    BACKUP_DIFFERS,
    SERVICE_COMMAND_FAILED,
    STOP_WRITE_FAILED,

    // import-1x and retire-1x
    IMPORT_SOURCE_NOT_CONFIGURED,
    IMPORT_TARGET_NOT_EMPTY,
    IMPORT_SOURCE_INCOMPLETE,
    IMPORT_DATA_REFUSED,
    IMPORT_VERIFY_FAILED,
    RETIRE_BACKUP_NOT_ACKNOWLEDGED,
    RETIRE_VERIFY_FAILED,
    RETIRE_OWN_SCHEMA,
    RETIRE_SESSIONS_CONNECTED,
    RETIRE_NOT_OWNER,
    RETIRE_SOURCE_CHANGED;

    @Override
    public String messageKey() {
        return "error." + name();
    }
}
