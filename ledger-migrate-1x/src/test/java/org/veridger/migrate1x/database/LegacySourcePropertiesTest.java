// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.migrate1x.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.migrate1x.fixture.LegacyLedger;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * MIG-020 (settings part): the connection of the legacy ledger is read from {@code ledger.import.source.*} like the other
 * settings; its password is a property of its own that the secret store supplies, and nothing the settings print shows it.
 */
class LegacySourcePropertiesTest {

    private static final String PASSWORD = "Legacy-Password-1357-unique";

    @Test
    @DisplayName("MIG-020 the settings bind url, username, password and the schema (ledger unless another is given); the text of the settings shows neither the password nor the password of the URL")
    void theSettingsBindAndHideThePassword() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "ledger.import.source.url", "jdbc:db://old.example/ledger?password=" + PASSWORD,
                "ledger.import.source.username", "ledger_reader",
                "ledger.import.source.password", PASSWORD)));

        LegacySourceProperties properties = binder.bind("ledger.import.source", LegacySourceProperties.class).get();

        assertThat(properties.url()).contains("old.example");
        assertThat(properties.username()).isEqualTo("ledger_reader");
        assertThat(properties.password()).isEqualTo(PASSWORD);
        assertThat(properties.schema()).isEqualTo("ledger");
        assertThat(properties.toString()).doesNotContain(PASSWORD).contains("ledger_reader").contains("old.example");
    }

    @Test
    @DisplayName("MIG-020 the legacy schema is an identifier: another name binds, a name that is not lower case letters, digits and _ is refused when the repository is built")
    void theSchemaIsAnIdentifier() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("ledger.import.source.url", "jdbc:db://old/x",
                "ledger.import.source.schema", "ledger_old")));
        assertThat(binder.bind("ledger.import.source", LegacySourceProperties.class).get().schema()).isEqualTo("ledger_old");

        try (LegacyLedger legacy = LegacyLedger.create()) {
            JdbcClient jdbc = JdbcClient.create(legacy.reader());
            assertThat(new LegacySourceRepository(jdbc, legacy.reader(), "ledger_old").schema()).isEqualTo("ledger_old");
            assertThatThrownBy(() -> new LegacySourceRepository(jdbc, legacy.reader(), "ledger.old")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LegacySourceRepository(jdbc, legacy.reader(), "Ledger")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LegacySourceRepository(jdbc, legacy.reader(), (String) null)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
