// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * UI configuration for the {@code /grants} host selector ({@code ledger.admin.grants.*}). Preset host names live in
 * configuration ({@code application.yml} or {@code local.yaml}) so Java code remains host-agnostic.
 */
@ConfigurationProperties(prefix = "ledger.admin.grants")
public record GrantsUiProperties(@DefaultValue List<String> knownHosts) {

    public GrantsUiProperties {
        knownHosts = knownHosts == null ? List.of() : List.copyOf(knownHosts);
    }
}
