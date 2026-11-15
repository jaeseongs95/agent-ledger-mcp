package org.pgledger.migrate1x.tool;

/** Every reason a command can be refused; each code has a message under {@link #messageKey()} in the message bundles. */
public enum ErrorCode {

    // the command line
    MISSING_COMMAND,
    UNKNOWN_COMMAND,
    MISSING_ARGUMENT,
    TOO_MANY_ARGUMENTS,
    MISSING_OPTION_VALUE,
    UNKNOWN_OPTION,
    INVALID_OPTION_VALUE,

    // the secret store and the secret input
    INVALID_SECRET_NAME,
    NO_CONSOLE,
    EMPTY_SECRET,
    EMPTY_SECRET_FILE,
    INVALID_ENCODING,
    NON_POSIX_FILESYSTEM,
    LOOSE_PERMISSIONS,
    UNEXPECTED_FILE_TYPE,
    UNKNOWN_PROCESS_USER,
    PARENT_MISSING,
    IO_FAILED,
    SECRET_TOO_LONG,
    STDIN_FAILED,

    // the 1.x environment file (import-secrets-1x, stop-1x)
    ENV_FILE_FAILED,
    ENV_KEYS_MISSING,
    INVALID_1X_VALUE,
    HOSTS_DROPPED,

    // stop-1x
    NOT_1X_SERVICE,
    NO_1X_SERVICE,
    BACKUP_DIFFERS,
    SERVICE_COMMAND_FAILED,
    STOP_WRITE_FAILED,

    // the ledger database (import-1x, retire-1x)
    DRIVER_NOT_CONFIGURED,
    LEDGER_REFUSED,
    DATABASE_FAILED,
    SCHEMA_NOT_READY,
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

    public String messageKey() {
        return "error." + name();
    }
}
