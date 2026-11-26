// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.driver;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Connection settings ({@code ledger.jdbc.*}). The password is not part of any configuration file: it arrives
 * through the secret store (a configtree property source). A value that does not bind or validate stops the startup.
 *
 * @param url                 JDBC URL
 * @param username            database account
 * @param password            database password
 * @param poolSize            maximum pool size
 * @param driver              the registered driver
 * @param connectionTimeoutMs maximum wait for a connection from the pool in milliseconds (default 30000)
 * @param idleTimeoutMs       how long an idle connection stays in the pool in milliseconds (default 600000)
 * @param keepaliveTimeMs     how often idle pooled connections are validated in milliseconds (0 disables, default 0)
 */
@Validated
@ConfigurationProperties(prefix = "ledger.jdbc")
public record LedgerJdbcProperties(String url, String username, String password,
        @DefaultValue("10") @Min(1) int poolSize, @DefaultValue @Valid DriverRegistration driver,
        @DefaultValue("30000") @Min(1000) long connectionTimeoutMs,
        @DefaultValue("600000") @Min(10000) long idleTimeoutMs,
        @DefaultValue("0") @Min(0) long keepaliveTimeMs) {

    @ConstructorBinding
    public LedgerJdbcProperties {
    }

    /** Convenience constructor preserving 5-argument callers with default pool timeouts. */
    public LedgerJdbcProperties(String url, String username, String password, int poolSize, DriverRegistration driver) {
        this(url, username, password, poolSize, driver, 30_000L, 600_000L, 0L);
    }

    /** HikariCP disables non-zero keepalive values below 30,000 ms; reject them up front instead of silently clamping. */
    @AssertTrue(message = "ledger.jdbc.keepalive-time-ms must be 0 (disabled) or at least 30000")
    public boolean isKeepaliveTimeMsUsable() {
        return keepaliveTimeMs == 0L || keepaliveTimeMs >= 30_000L;
    }

    /**
     * Never prints the password, and not the password inside the URL either. A record prints every component by
     * default, and a settings object ends up in logs and failure messages.
     */
    @Override
    public String toString() {
        return "LedgerJdbcProperties[url=" + RegisteredDriver.sanitize(url) + ", username=" + username + ", poolSize="
                + poolSize + ", driver=" + driver + ", connectionTimeoutMs=" + connectionTimeoutMs
                + ", idleTimeoutMs=" + idleTimeoutMs + ", keepaliveTimeMs=" + keepaliveTimeMs + "]";
    }
}
