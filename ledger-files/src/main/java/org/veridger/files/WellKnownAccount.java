// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.files;

/**
 * The accounts that every Windows has and that the product means by a word and not by a name: the service account, the system and
 * the group of the administrators. The name of such an account depends on the display language of Windows (the group of the
 * administrators is {@code BUILTIN\Administrators} in English and has another name in German), its security identifier (SID) does
 * not (https://learn.microsoft.com/en-us/windows/win32/secauthz/well-known-sids). So the product holds the SID, and
 * {@link WellKnownAccounts} says what the host calls the account.
 */
public enum WellKnownAccount {

    /** The predefined local account that the services run as (LocalService): no person's account, no password. */
    SERVICE("S-1-5-19"),
    /** The local system (LocalSystem). */
    SYSTEM("S-1-5-18"),
    /** The built-in group of the administrators of the computer. */
    ADMINISTRATORS("S-1-5-32-544");

    private final String sid;

    WellKnownAccount(String sid) {
        this.sid = sid;
    }

    /** The security identifier in its string form, the same on every Windows whatever its language. */
    public String sid() {
        return sid;
    }
}
