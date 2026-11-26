// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.security;

import java.util.Locale;
import java.util.Set;
import org.veridger.admin.app.LedgerAdminProperties;
import org.springframework.stereotype.Component;

/** The host names of the Host header that the admin answers to (the guard against DNS rebinding); case is ignored. */
@Component
public final class AllowedHosts {

    private final Set<String> hosts;

    public AllowedHosts(LedgerAdminProperties properties) {
        this.hosts = Set.copyOf(properties.allowedHosts().stream().map(host -> host.toLowerCase(Locale.ROOT)).toList());
    }

    public boolean allows(String host) {
        return host != null && hosts.contains(host.toLowerCase(Locale.ROOT));
    }
}
