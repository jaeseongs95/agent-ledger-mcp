package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SFR-005: ARTIFACT_STORAGE_UNSAFE keeps its code, its name and its answer (not retryable, ATA-040/041) but its meaning is narrowed: a
 * path of the storage is not of the expected kind. The two messages say so, and no longer say that the storage is "not safe" (the
 * personal edition does not judge who may reach a file).
 */
class StorageUnsafeMessageTest {

    @Test
    @DisplayName("SFR-005: the English and the Korean message of ARTIFACT_STORAGE_UNSAFE name the kind of the path (a regular file or a folder) and the detail, and do not speak of safety")
    void sfr005_messageNamesTheKindOfThePath() {
        String en = MessageFormat.format(ResourceBundle.getBundle("messages/ledger-errors", Locale.ROOT)
                .getString("error.ARTIFACT_STORAGE_UNSAFE"), "a regular file is expected, not a directory");
        String ko = MessageFormat.format(ResourceBundle.getBundle("messages/ledger-errors", Locale.KOREAN)
                .getString("error.ARTIFACT_STORAGE_UNSAFE"), "a regular file is expected, not a directory");

        assertThat(en).contains("regular file", "folder", "a regular file is expected, not a directory").doesNotContain("safe");
        assertThat(ko).contains("일반 파일", "폴더", "a regular file is expected, not a directory").doesNotContain("안전");
    }
}
