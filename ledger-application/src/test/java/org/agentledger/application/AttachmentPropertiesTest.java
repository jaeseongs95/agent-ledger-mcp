// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.application.attachment.AttachmentProperties;
import org.agentledger.application.attachment.AttachmentSettingsException;
import org.agentledger.application.support.AttachmentSettings;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

/**
 * ATA-019: the settings of the attachments ({@code ledger.attachments.*}) bound from a map the way Spring Boot binds them from the
 * environment, without a context: the ten keys with their defaults, a key directory inside the attachment directory refused at start,
 * and the chunk size that is a contract constant and no setting.
 */
class AttachmentPropertiesTest {

    private static final Path BASE = Path.of("build", "attachment-properties-test").toAbsolutePath().normalize();
    private static final Path DIRECTORY = BASE.resolve("attachments");
    private static final Path KEYS = BASE.resolve("keys");

    private static Map<String, Object> required() {
        Map<String, Object> values = new HashMap<>();
        values.put("ledger.attachments.directory", DIRECTORY.toString());
        values.put("ledger.attachments.key-directory", KEYS.toString());
        values.put("ledger.attachments.active-key-id", "key-0123456789abcdef");
        return values;
    }

    private static AttachmentProperties bind(Map<String, Object> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bind("ledger.attachments",
                Bindable.of(AttachmentProperties.class)).get();
    }

    @Test
    @DisplayName("ATA-019: the keys have the defaults of the design: 64 MiB, 512 MiB, 10 GiB, 4, a disk margin of 64 MiB, 10 minutes, 24 hours, 1 minute")
    void ata019_theDefaults() {
        AttachmentProperties properties = bind(required());

        assertThat(properties.directory()).isEqualTo(DIRECTORY);
        assertThat(properties.keyDirectory()).isEqualTo(KEYS);
        assertThat(properties.activeKeyId()).isEqualTo("key-0123456789abcdef");
        assertThat(properties.maxFileBytes()).isEqualTo(DataSize.ofMegabytes(64));
        assertThat(properties.stagingBudgetBytes()).isEqualTo(DataSize.ofMegabytes(512));
        assertThat(properties.attachmentBudgetBytes()).isEqualTo(DataSize.ofGigabytes(10));
        assertThat(properties.maxOpenPerOwner()).isEqualTo(4);
        assertThat(properties.diskMarginBytes()).as("the margin is the size of the largest file").isEqualTo(DataSize.ofMegabytes(64));
        assertThat(properties.uploadTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.orphanGrace()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.reaperInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties).as("the same as the settings the tests build by hand")
                .isEqualTo(AttachmentSettings.properties(DIRECTORY, KEYS, "key-0123456789abcdef"));
    }

    @Test
    @DisplayName("ATA-019: every key can be set, in the units of Spring Boot")
    void ata019_everyKeyCanBeSet() {
        Map<String, Object> values = required();
        values.put("ledger.attachments.max-file-bytes", "1MB");
        values.put("ledger.attachments.staging-budget-bytes", "2MB");
        values.put("ledger.attachments.attachment-budget-bytes", "3MB");
        values.put("ledger.attachments.max-open-per-owner", "7");
        values.put("ledger.attachments.upload-ttl", "30s");
        values.put("ledger.attachments.orphan-grace", "2h");
        values.put("ledger.attachments.reaper-interval", "5m");

        AttachmentProperties properties = bind(values);

        assertThat(properties.maxFileBytes()).isEqualTo(DataSize.ofMegabytes(1));
        assertThat(properties.stagingBudgetBytes()).isEqualTo(DataSize.ofMegabytes(2));
        assertThat(properties.attachmentBudgetBytes()).isEqualTo(DataSize.ofMegabytes(3));
        assertThat(properties.maxOpenPerOwner()).isEqualTo(7);
        assertThat(properties.uploadTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.orphanGrace()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.reaperInterval()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("ATA-019: a key directory inside the attachment directory (or the same one) is a failure of the settings (no tool error code) whose message names both keys in English and Korean; a sibling whose name only starts the same is allowed")
    void ata019_theKeyDirectoryInsideTheStorageIsRefused() {
        for (Path inside : new Path[] {DIRECTORY.resolve("keys"), DIRECTORY, DIRECTORY.resolve("a").resolve("b")}) {
            assertThatThrownBy(() -> AttachmentSettings.properties(DIRECTORY, inside, "key-a"))
                    .isInstanceOfSatisfying(AttachmentSettingsException.class, failure -> assertThat(failure.getMessage())
                            .contains("ledger.attachments.key-directory", "ledger.attachments.directory", "must name a folder outside",
                                    "폴더 밖의 폴더여야 합니다"));
        }
        assertThat(AttachmentSettings.properties(DIRECTORY, BASE.resolve("attachments-keys"), "key-a").keyDirectory())
                .isEqualTo(BASE.resolve("attachments-keys"));

        Map<String, Object> values = required();
        values.put("ledger.attachments.key-directory", DIRECTORY.resolve("keys").toString());
        assertThatThrownBy(() -> bind(values)).as("the start fails when the setting is bound")
                .hasRootCauseInstanceOf(AttachmentSettingsException.class);
    }

    @Test
    @DisplayName("ATA-019: a value out of range stops the start")
    void ata019_aValueOutOfRangeIsRefused() {
        for (String[] bad : new String[][] {{"max-open-per-owner", "0"}, {"upload-ttl", "0s"}, {"orphan-grace", "0s"},
                {"reaper-interval", "0s"}, {"max-file-bytes", "0B"}, {"staging-budget-bytes", "1KB"},
                {"attachment-budget-bytes", "1KB"}}) {
            Map<String, Object> values = required();
            values.put("ledger.attachments.max-file-bytes", "1MB");
            values.put("ledger.attachments." + bad[0], bad[1]);
            assertThatThrownBy(() -> bind(values)).as("%s = %s", bad[0], bad[1]).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    @DisplayName("ATA-019: the chunk size is the constant 262144, there is no setting for it and a key chunk-bytes changes nothing")
    void ata019_theChunkSizeIsAConstant() {
        assertThat(AttachmentProperties.CHUNK_BYTES).isEqualTo(262_144);
        Set<String> keys = Arrays.stream(AttachmentProperties.class.getRecordComponents()).map(RecordComponent::getName)
                .collect(Collectors.toSet());
        assertThat(keys).containsExactlyInAnyOrder("directory", "keyDirectory", "activeKeyId", "maxFileBytes",
                "stagingBudgetBytes", "attachmentBudgetBytes", "maxOpenPerOwner", "diskMarginBytes", "uploadTtl",
                "orphanGrace", "reaperInterval");

        Map<String, Object> values = required();
        values.put("ledger.attachments.chunk-bytes", "1024");
        assertThat(bind(values)).isEqualTo(bind(required()));
    }
}
