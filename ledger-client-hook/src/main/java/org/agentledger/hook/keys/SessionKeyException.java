// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook.keys;

/**
 * The session key of an author cannot be used. The reason says which check failed; the message is the reason name and
 * never carries key material or a file name.
 */
public final class SessionKeyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Which check failed. */
    public enum Reason {
        /** The server or author text is empty or holds a line break (the key file keeps each on its own line). */
        AUTHOR_INVALID,
        /** The key folder is not configured, is not a usable absolute path or is not a directory. */
        FOLDER_UNAVAILABLE,
        /** A folder or key file that the hook has just created could not be made owner-only (POSIX mode, ACL). An existing one is never judged. */
        TOO_OPEN,
        /** The path of the key file is a directory or another special file (a link is followed). */
        NOT_A_REGULAR_FILE,
        /** The key file is not in the stored format (wrong lines, text that is not UTF-8 or not a key). */
        CORRUPT,
        /** The key file belongs to another author. */
        AUTHOR_MISMATCH,
        /** The key file belongs to another MCP server (it was copied from the key of another server). */
        SERVER_MISMATCH,
        /** The current user is not known (no account name was given) where the file system has ACLs (the entry of the key folder names it). */
        OWNER_UNKNOWN,
        /** The private and the public key of the file are not a pair. */
        KEY_MISMATCH,
        /** Reading or creating the folder or the file failed. */
        IO_FAILED
    }

    private final Reason reason;

    public SessionKeyException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public SessionKeyException(Reason reason) {
        this(reason, null);
    }

    public Reason reason() {
        return reason;
    }
}
