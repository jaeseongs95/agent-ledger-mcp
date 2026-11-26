// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.driver;

/**
 * The settings of the application account for the check of {@code setup} ({@link ApplicationAccountConfiguration}): the
 * {@code ledger.jdbc.*} settings without the owner account of the run, or none when no application account is configured. The
 * tool that runs as the owner account binds them from the property sources without the owner account and hands them over as this
 * bean; this module makes the check from it.
 *
 * @param settings the settings of the application account; {@code null} in {@link #NOT_CONFIGURED}
 */
public record ApplicationAccountSettings(LedgerJdbcProperties settings) {

    /** No application account is configured: the check answers no. */
    public static final ApplicationAccountSettings NOT_CONFIGURED = new ApplicationAccountSettings(null);

    /** Whether an application account is configured. */
    public boolean configured() {
        return settings != null;
    }
}
