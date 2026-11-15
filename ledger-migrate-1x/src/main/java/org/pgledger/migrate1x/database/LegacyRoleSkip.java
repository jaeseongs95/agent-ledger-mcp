package org.pgledger.migrate1x.database;

/**
 * A database role of the 1.x installer that {@code retire-legacy} leaves in place, and why. The role is listed with the
 * reason, so the owner sees what stays and can remove it by hand when the reason no longer holds.
 */
public record LegacyRoleSkip(String name, Reason reason) {

    /** Why a role is kept. */
    public enum Reason {
        /** The role is the account that runs the command. */
        RUNNING_ACCOUNT,
        /** The role is the owner account or the application account of the 2.0 settings. */
        LEDGER_2_ACCOUNT,
        /** A role outside the 1.x set is a member of it. */
        OTHER_MEMBERS,
        /** It owns an object outside the legacy schema in this database. */
        OWNS_OTHER_OBJECTS,
        /** The running account may not drop roles. */
        NO_PRIVILEGE
    }

    /** The line that the list shows and the digest is made of. */
    public String line() {
        return "SKIPPED ROLE " + name + " " + reason;
    }
}
