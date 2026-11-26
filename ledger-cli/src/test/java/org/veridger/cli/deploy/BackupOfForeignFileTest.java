// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REH2-INS-01 at the level of the writer, on any file system (the command test of {@code prepare-host} for the Linux layout
 * needs POSIX permissions): a file that this command did not render and whose backup {@code <file>.backup} is already
 * there with the same bytes.
 */
class BackupOfForeignFileTest {

    private static final String FOREIGN = "[Unit]\nDescription=hand-written\n";
    private static final String UNIT = "/etc/systemd/system/veridger.service";
    private static final String RENDERED = Text.GENERATED + "\n[Unit]\nDescription=rendered\n";

    @TempDir
    Path tmp;

    private final DeployFiles files = new DeployFiles();
    /** The paths of the unit are Linux paths: the platform that wrote them puts them below the root. */
    private final ServicePlatform linux = DeployTestBeans.platforms().of(DeployLayout.Platform.LINUX);

    @Test
    @DisplayName("REH2-INS-01 a file that this command did not render and a backup with the same bytes (what a rollback leaves): the writer goes on, the backup is kept as it is and told as kept, the file is the rendered one; the next run keeps the backup too")
    void sameBytesAreKept() throws IOException {
        Path unit = tmp.resolve("etc/systemd/system/veridger.service");
        Path backup = unit.resolveSibling("veridger.service" + DeployFiles.BACKUP_SUFFIX);
        Files.createDirectories(unit.getParent());
        Files.writeString(unit, FOREIGN, StandardCharsets.UTF_8);
        Files.writeString(backup, FOREIGN, StandardCharsets.UTF_8);
        List<Path> kept = new ArrayList<>();
        List<DeployFiles.Backup> how = new ArrayList<>();

        List<Path> written = files.write(linux, tmp, List.of(new RenderedFile(UNIT, RENDERED)), (path, made) -> {
            kept.add(path);
            how.add(made);
        });

        assertThat(written).containsExactly(unit);
        assertThat(kept).containsExactly(backup);
        // REH3-INS-02: a backup that was there is told as left alone, not as made now
        assertThat(how).containsExactly(DeployFiles.Backup.KEPT);
        assertThat(Files.readString(backup, StandardCharsets.UTF_8)).isEqualTo(FOREIGN);
        assertThat(Files.readString(unit, StandardCharsets.UTF_8)).isEqualTo(RENDERED);
        files.write(linux, tmp, List.of(new RenderedFile(UNIT, RENDERED)), (path, made) -> {
            kept.add(path);
            how.add(made);
        });
        assertThat(kept).hasSize(1);
        assertThat(Files.readString(backup, StandardCharsets.UTF_8)).isEqualTo(FOREIGN);
    }

    @Test
    @DisplayName("REH3-INS-01 a file that this command did not render, without a backup, is moved to <file>.backup and told as moved (the sentence of the first run)")
    void firstRunMovesIt() throws IOException {
        Path unit = tmp.resolve("etc/systemd/system/veridger.service");
        Path backup = unit.resolveSibling("veridger.service" + DeployFiles.BACKUP_SUFFIX);
        Files.createDirectories(unit.getParent());
        Files.writeString(unit, FOREIGN, StandardCharsets.UTF_8);
        List<DeployFiles.Backup> how = new ArrayList<>();

        files.write(linux, tmp, List.of(new RenderedFile(UNIT, RENDERED)), (path, made) -> how.add(made));

        assertThat(how).containsExactly(DeployFiles.Backup.MOVED);
        assertThat(Files.readString(backup, StandardCharsets.UTF_8)).isEqualTo(FOREIGN);
    }
}
