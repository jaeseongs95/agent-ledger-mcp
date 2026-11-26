// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.database.driver.DriverRegistration;
import org.veridger.database.driver.LedgerJdbcProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * DRV-046 (F6): the settings of the ledger database never print a password, neither the one of the settings nor the one
 * inside the JDBC URL. A record prints every component by default, and a settings object ends up in logs and failures.
 */
class LedgerJdbcPropertiesTest {

    private static final String SECRET = "s3cr3t-password-value";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LedgerJdbcProperties.class)
    static class Settings {
    }

    @Test
    @DisplayName("DRV-046 toString shows the URL without its password, the account and the pool size, and never the password")
    void theTextOfTheSettingsHasNoPassword() {
        LedgerJdbcProperties settings = new LedgerJdbcProperties("jdbc:db://host/ledger?user=app&password=" + SECRET,
                "app", SECRET, 5, new DriverRegistration(null, "org.example.Driver", null, null));

        String text = settings.toString();

        assertThat(text).doesNotContain(SECRET).contains("username=app").contains("poolSize=5")
                .contains("org.example.Driver").contains("password=***");
    }

    @Test
    @DisplayName("DRV-046 settings bound from the environment print no password either, with the password in the settings and in the URL")
    void boundSettingsPrintNoPassword() {
        new ApplicationContextRunner().withUserConfiguration(Settings.class)
                .withPropertyValues("ledger.jdbc.url=jdbc:db://app:" + SECRET + "@host/ledger?password=" + SECRET,
                        "ledger.jdbc.username=app", "ledger.jdbc.password=" + SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LedgerJdbcProperties bound = context.getBean(LedgerJdbcProperties.class);
                    assertThat(bound.password()).as("the value is bound").isEqualTo(SECRET);
                    assertThat(bound.toString()).doesNotContain(SECRET).contains("poolSize=10");
                    assertThat(bound.connectionTimeoutMs()).isEqualTo(30_000L);
                    assertThat(bound.idleTimeoutMs()).isEqualTo(600_000L);
                    assertThat(bound.keepaliveTimeMs()).isEqualTo(0L);
                    assertThat(bound.isKeepaliveTimeMsUsable()).isTrue();
                });
    }

    @Test
    @DisplayName("PERF-DB-12: keepaliveTimeMs accepts 0 or >= 30000ms and rejects sub-30000ms non-zero values")
    void keepaliveTimeValidationRejectsSubThirtySecondNonZeroValues() {
        new ApplicationContextRunner().withUserConfiguration(Settings.class)
                .withPropertyValues("ledger.jdbc.url=jdbc:postgresql://host/ledger",
                        "ledger.jdbc.username=app", "ledger.jdbc.password=" + SECRET,
                        "ledger.jdbc.connection-timeout-ms=5000",
                        "ledger.jdbc.idle-timeout-ms=120000",
                        "ledger.jdbc.keepalive-time-ms=30000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LedgerJdbcProperties bound = context.getBean(LedgerJdbcProperties.class);
                    assertThat(bound.connectionTimeoutMs()).isEqualTo(5_000L);
                    assertThat(bound.idleTimeoutMs()).isEqualTo(120_000L);
                    assertThat(bound.keepaliveTimeMs()).isEqualTo(30_000L);
                });

        new ApplicationContextRunner().withUserConfiguration(Settings.class)
                .withPropertyValues("ledger.jdbc.url=jdbc:postgresql://host/ledger",
                        "ledger.jdbc.username=app", "ledger.jdbc.password=" + SECRET,
                        "ledger.jdbc.keepalive-time-ms=15000")
                .run(context -> assertThat(context).hasFailed());
    }
}
