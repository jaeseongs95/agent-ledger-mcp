// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.domain.value.Sha256;

class PreparedFileTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("ATA-044: PreparedFile measures regular and empty files with 64-hex sha256 and supports --name override")
    void measuresRegularAndEmptyFiles() throws Exception {
        byte[] content = "hello bundle attachment".getBytes(StandardCharsets.UTF_8);
        Path doc = tmp.resolve("notes.txt");
        Files.write(doc, content);

        PreparedFile prepared = PreparedFile.of(doc, null);
        assertThat(prepared.fileName()).isEqualTo("notes.txt");
        assertThat(prepared.size()).isEqualTo(content.length);
        assertThat(prepared.sha256()).isEqualTo(Sha256.ofBytes(content).hex()).hasSize(64);
        assertThat(prepared.mediaType()).isNotBlank();

        PreparedFile renamed = PreparedFile.of(doc, "bundle/notes.txt");
        assertThat(renamed.fileName()).isEqualTo("bundle/notes.txt");

        Path empty = tmp.resolve("empty.bin");
        Files.write(empty, new byte[0]);
        PreparedFile emptyPrepared = PreparedFile.of(empty, null);
        assertThat(emptyPrepared.size()).isZero();
        assertThat(emptyPrepared.sha256()).isEqualTo(Sha256.ofBytes(new byte[0]).hex());
    }

    @Test
    @DisplayName("ATA-044: missing files and directories are rejected with ATTACHMENTS_FILE_INVALID")
    void rejectsMissingAndDirectories() throws Exception {
        Path missing = tmp.resolve("missing.txt");
        assertThatThrownBy(() -> PreparedFile.of(missing, null))
                .isInstanceOf(UsageException.class)
                .extracting(ex -> ((UsageException) ex).code())
                .isEqualTo(ErrorCode.ATTACHMENTS_FILE_INVALID);

        assertThatThrownBy(() -> PreparedFile.of(tmp, null))
                .isInstanceOf(UsageException.class)
                .extracting(ex -> ((UsageException) ex).code())
                .isEqualTo(ErrorCode.ATTACHMENTS_FILE_INVALID);
    }
}
