// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;

/**
 * Exhaustive boundary and failure-path tests for {@link StorageKey} and {@link PageCursor}.
 */
class PageCursorAndStorageKeyTest {

    private static final String VALID_HEX = "0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("DOM-SK-01 StorageKey validates 32 lower-case hex, extracts 2-char folder, and hides key in toString")
    void storageKeyContractsAndBoundaries() {
        StorageKey key = StorageKey.of(VALID_HEX);
        assertThat(key.hex()).isEqualTo(VALID_HEX);
        assertThat(key.folder()).isEqualTo("01");
        assertThat(key.toString()).isEqualTo("StorageKey[hidden]");
        assertThat(StorageKey.isFormat(VALID_HEX)).isTrue();
        assertThat(StorageKey.isFormat(null)).isFalse();
        assertThat(StorageKey.isFormat("0123456789abcdef0123456789abcde")).isFalse();
        assertThat(StorageKey.isFormat("0123456789abcdef0123456789abcdef0")).isFalse();
        assertThat(StorageKey.isFormat("0123456789ABCDEF0123456789abcdef")).isFalse();
        assertThat(StorageKey.isFormat("0123456789abcdef0123456789abcdeg")).isFalse();

        assertThatThrownBy(() -> StorageKey.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKey.of("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKey.of("0123456789ABCDEF0123456789ABCDEF")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DOM-PC-01 PageCursor validates construction, round-trips null and non-null sort keys, and rejects damaged/wrong-kind tokens")
    void pageCursorBoundariesAndFailurePaths() {
        PageCursor withSort = new PageCursor(PageCursor.Kind.ISSUES, "2026-11-27T00:00:00Z", "ISS-001");
        String encoded = withSort.encode();
        assertThat(PageCursor.decode(encoded, PageCursor.Kind.ISSUES)).isEqualTo(withSort);

        PageCursor withoutSort = new PageCursor(PageCursor.Kind.TASKS, null, "TSK-001");
        assertThat(PageCursor.decode(withoutSort.encode(), PageCursor.Kind.TASKS)).isEqualTo(withoutSort);

        // Construction failures
        assertThatThrownBy(() -> new PageCursor(null, "k", "id"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new PageCursor(PageCursor.Kind.ISSUES, "k", null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new PageCursor(PageCursor.Kind.ISSUES, "k", ""))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new PageCursor(PageCursor.Kind.ISSUES, "k", "a\nb"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new PageCursor(PageCursor.Kind.ISSUES, "k\n2", "id"))
                .isInstanceOf(ValidationException.class);

        // Decode failures
        assertThatThrownBy(() -> PageCursor.decode(null, PageCursor.Kind.ISSUES))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).issues().getFirst().reason()).isEqualTo(ValidationReason.REQUIRED);
        assertThatThrownBy(() -> PageCursor.decode("nodot", PageCursor.Kind.ISSUES))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).issues().getFirst().reason()).isEqualTo(ValidationReason.CURSOR_DAMAGED);
        assertThatThrownBy(() -> PageCursor.decode(".12345678", PageCursor.Kind.ISSUES))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).issues().getFirst().reason()).isEqualTo(ValidationReason.CURSOR_DAMAGED);
        assertThatThrownBy(() -> PageCursor.decode("!!!invalid-base64!!!.12345678", PageCursor.Kind.ISSUES))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).issues().getFirst().reason()).isEqualTo(ValidationReason.CURSOR_DAMAGED);
        assertThatThrownBy(() -> PageCursor.decode(encoded.substring(0, encoded.length() - 1) + "0", PageCursor.Kind.ISSUES))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).issues().getFirst().reason()).isEqualTo(ValidationReason.CURSOR_DAMAGED);
        assertThatThrownBy(() -> PageCursor.decode(encoded, PageCursor.Kind.SPECS))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).issues().getFirst().reason()).isEqualTo(ValidationReason.CURSOR_OTHER_LIST);

        // Valid checksum but malformed internal newline counts / unknown kind
        for (String badPayload : new String[] { "ISSUES", "ISSUES\nonlyone", "ISSUES\na\nb\nc", "UNKNOWN\ns\nid" }) {
            String token = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(badPayload.getBytes(StandardCharsets.UTF_8))
                    + "." + Sha256.ofText(badPayload).hex().substring(0, 8);
            assertThatThrownBy(() -> PageCursor.decode(token, PageCursor.Kind.ISSUES))
                    .isInstanceOf(ValidationException.class)
                    .extracting(e -> ((ValidationException) e).issues().getFirst().reason()).isEqualTo(ValidationReason.CURSOR_DAMAGED);
        }
    }
}
