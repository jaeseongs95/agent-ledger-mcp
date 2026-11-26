// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.error;

/** Error tokens of category ADMIN. */
public enum AdminCode implements ErrorCode {
    AGENT_TOKEN_FORBIDDEN,
    UNAUTHORIZED,
    DNS_REBINDING_BLOCKED,
    TOO_MANY_AUTH_FAILURES,
    COMMON_CODE_DELETE_FORBIDDEN,
    PAYLOAD_TOO_LARGE,
    CSRF_REJECTED,
    /** An owner write arrived without the passkey assertion that approves it. */
    PASSKEY_APPROVAL_REQUIRED,
    /** The approval is for another action, another session, was used already, has expired or does not verify. */
    PASSKEY_APPROVAL_INVALID,
    /** No live passkey exists, so nothing can be approved or signed in yet. */
    PASSKEY_NONE_REGISTERED,
    /** The one-time registration code is missing, unknown, expired or used. */
    PASSKEY_CODE_INVALID,
    /** The relying party id or the origin of the assertion is not the one of this admin web. */
    PASSKEY_HOST_MISMATCH,
    /** A passkey needs HTTPS (loopback excepted). */
    PASSKEY_HTTPS_REQUIRED,
    /** The assertion does not verify (signature, challenge, type, user verification, unknown credential). */
    PASSKEY_ASSERTION_INVALID,
    /** The last live passkey cannot be closed in the admin web (reset it from the owner's shell instead). */
    PASSKEY_LAST_KEY;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.ADMIN;
    }
}
