// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook.resident;

import org.agentledger.files.ProcessAccount;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Account setting for the resident hook state folder ({@code ledger.hook.user-name}): on file systems with ACLs, names
 * the owner-only principal for newly created resident state files. Defaults to {@link ProcessAccount#name()}.
 */
@ConfigurationProperties("ledger.hook")
public record ResidentAccountProperties(String userName) {

    @Override
    public String userName() {
        return userName == null || userName.isBlank() ? ProcessAccount.name().orElse("") : userName;
    }
}
