// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.migrate1x.database;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.pgledger.database.driver.RegisteredDriver;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The connection to the legacy ledger that {@code import-legacy} reads ({@code ledger.import.source.*}). Like the settings
 * of the ledger database it is read with the registered JDBC driver, and the password is not part of any configuration
 * file: it arrives through the secret store (the secret {@code ledger.import.source.password}, a configtree property
 * source). The account should be the legacy read-only login of the old installer ({@code ledger_reader}), and the command
 * never writes to it. A value that does not bind or validate stops the startup.
 *
 * @param url      JDBC URL of the database that holds the legacy schema
 * @param username database account (a read-only one)
 * @param password database password
 * @param schema   the legacy schema, {@code ledger} for every installation of the old installer
 * @param roles    the database roles that the old installer made ({@code retire-legacy} removes them with the schema, unless
 *                 one is in use elsewhere); the four roles of the installer of 1.x unless another set is given
 */
@Validated
@ConfigurationProperties(prefix = "ledger.import.source")
public record LegacySourceProperties(@NotBlank String url, String username, String password,
        @DefaultValue("ledger") @Pattern(regexp = "[a-z][a-z0-9_]{0,29}") String schema,
        @DefaultValue({"ledger_owner", "ledger_author", "ledger_reviewer", "ledger_reader"})
        List<@Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{0,62}") String> roles) {

    /** Never prints the password, and not the password of the URL either. */
    @Override
    public String toString() {
        return "LegacySourceProperties[url=" + RegisteredDriver.sanitize(url) + ", username=" + username + ", schema="
                + schema + ", roles=" + roles + "]";
    }
}
