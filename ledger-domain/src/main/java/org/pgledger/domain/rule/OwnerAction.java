package org.pgledger.domain.rule;

/**
 * What an owner operation did; the {@code operation} column of {@code owner_audit} holds the constant name (at most 20
 * characters, and the constants are the values of the check constraint of that column, see V7).
 */
public enum OwnerAction {
    GRANT_ISSUED, GRANT_REVOKED, KEY_REGISTERED, KEY_REPLACED, KEY_REVOKED, CODE_SET, PROJECT_ADDED, DRIVER_REGISTERED, IMPORT,
    /** The owner asked for a one-time passkey registration code (ledger-cli passkey-code). */
    PASSKEY_CODE_ISSUED,
    /** A passkey was registered with such a code. */
    PASSKEY_REGISTERED,
    /** One passkey was closed in the admin web. */
    PASSKEY_REVOKED,
    /** Every live passkey was closed from the owner's shell (ledger-cli passkey-code --remove-existing). */
    PASSKEY_RESET
}
