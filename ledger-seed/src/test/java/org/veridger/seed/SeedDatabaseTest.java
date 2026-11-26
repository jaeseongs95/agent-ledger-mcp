// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TXD-009: the H2 file of the bundle is opened from a Path without string edits of the path text. */
class SeedDatabaseTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("TXD-009 a bundle file in a directory with a space and a non-ASCII letter is created at that path; a name without .mv.db is refused")
    void opensByPath() throws IOException, SQLException {
        Path folder = Files.createDirectories(directory.resolve("seed dir 한글"));
        Path file = folder.resolve("bundle.mv.db");

        try (Connection connection = SeedDatabase.open(file, "")) {
            assertThat(connection.isValid(1)).isTrue();
        }

        assertThat(file).isRegularFile();
        assertThatThrownBy(() -> SeedDatabase.open(folder.resolve("bundle.txt"), "")).isInstanceOf(SQLException.class)
                .hasMessageContaining(".mv.db");
    }
}
