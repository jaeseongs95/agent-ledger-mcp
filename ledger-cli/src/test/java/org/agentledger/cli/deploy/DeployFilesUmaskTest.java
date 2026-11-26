// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.agentledger.cli.support.RequiresPosixPermissions;
import org.agentledger.files.FileKind;
import org.agentledger.files.FileModes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests ensuring {@link DeployFiles} always produces {@code rw-r--r--} (0644) files on POSIX file systems even
 * when the invoking process runs under a restrictive {@code umask} such as {@code 077}.
 */
class DeployFilesUmaskTest {

    private static final HostPaths RELATIVE_POSIX = (root, path) -> root.resolve(path.replaceFirst("^/", ""));

    @TempDir
    Path tmp;

    @Test
    @RequiresPosixPermissions
    @DisplayName("3.0.1 POSIX umask 077: raw createTempFile is masked to 0600, while DeployFiles copy/write/writeIntoHost still produce 0644")
    void copyAndWritePreserveWorldReadableUnderRestrictiveUmask() throws Exception {
        Path work = Files.createDirectory(tmp.resolve("work"));
        Path source = work.resolve("source.jar");
        Files.writeString(source, "jar-bytes", StandardCharsets.UTF_8);

        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path probeLog = tmp.resolve("probe.log");
        ProcessBuilder builder = new ProcessBuilder(
                "/bin/sh", "-c", "umask 077 && exec \"$@\"", "--",
                java.toString(), "-Duser.timezone=UTC", UmaskProbe.class.getName(), work.toString())
                .redirectErrorStream(true)
                .redirectOutput(probeLog.toFile());
        builder.environment().put("CLASSPATH", System.getProperty("java.class.path"));

        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = Files.exists(probeLog) ? Files.readString(probeLog, StandardCharsets.UTF_8) : "";
        assertThat(finished).as("probe process finished within timeout; output=%s", output).isTrue();
        assertThat(process.exitValue()).as("probe exit code; output=%s", output).isZero();

        // Control check: confirms umask 077 actually masked raw Files.createTempFile(..., WORLD_READABLE) to rw-------.
        assertThat(mode(work.resolve("control.txt"))).isEqualTo(FileModes.PRIVATE);

        Path copied = work.resolve("copied.jar");
        Path written = work.resolve("root/etc/agent-ledger-mcp/mcp.yaml");
        Path hostWritten = work.resolve("host-root/etc/agent-ledger-mcp/admin.yaml");

        assertThat(Files.readString(copied, StandardCharsets.UTF_8)).isEqualTo("jar-bytes");
        assertThat(mode(copied)).isEqualTo(FileModes.WORLD_READABLE);

        assertThat(Files.readString(written, StandardCharsets.UTF_8)).isEqualTo("server:\n  port: 8765\n");
        assertThat(mode(written)).isEqualTo(FileModes.WORLD_READABLE);

        assertThat(Files.readString(hostWritten, StandardCharsets.UTF_8)).isEqualTo("server:\n  port: 8865\n");
        assertThat(mode(hostWritten)).isEqualTo(FileModes.WORLD_READABLE);

        assertNoTemporaryFiles(work);
    }

    @Test
    @DisplayName("3.0.1 cross-platform: DeployFiles copy, write, and writeIntoHost write expected bytes and leave no temporary files")
    void copyAndWriteLeaveNoTemporaryFiles() throws IOException {
        DeployFiles files = new DeployFiles();
        Path source = tmp.resolve("source.jar");
        Files.writeString(source, "jar-bytes", StandardCharsets.UTF_8);

        Path copied = tmp.resolve("out/copied.jar");
        files.copy(source, copied);

        Path root = tmp.resolve("root");
        files.write(RELATIVE_POSIX, root,
                List.of(new RenderedFile("/etc/agent-ledger-mcp/mcp.yaml", "server:\n  port: 8765\n")),
                (backup, how) -> {});

        Path hostRoot = tmp.resolve("host-root");
        files.writeIntoHost(RELATIVE_POSIX, hostRoot,
                List.of(new RenderedFile("/etc/agent-ledger-mcp/admin.yaml", "server:\n  port: 8865\n")),
                (backup, how) -> {});

        assertThat(Files.readString(copied, StandardCharsets.UTF_8)).isEqualTo("jar-bytes");
        assertThat(Files.readString(root.resolve("etc/agent-ledger-mcp/mcp.yaml"), StandardCharsets.UTF_8))
                .isEqualTo("server:\n  port: 8765\n");
        assertThat(Files.readString(hostRoot.resolve("etc/agent-ledger-mcp/admin.yaml"), StandardCharsets.UTF_8))
                .isEqualTo("server:\n  port: 8865\n");
        if (FileKind.of(copied.getFileSystem()) == FileKind.POSIX) {
            assertThat(mode(copied)).isEqualTo(FileModes.WORLD_READABLE);
            assertThat(mode(root.resolve("etc/agent-ledger-mcp/mcp.yaml"))).isEqualTo(FileModes.WORLD_READABLE);
            assertThat(mode(hostRoot.resolve("etc/agent-ledger-mcp/admin.yaml"))).isEqualTo(FileModes.WORLD_READABLE);
        }
        assertNoTemporaryFiles(tmp);
    }

    private static String mode(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    private static void assertNoTemporaryFiles(Path directory) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            assertThat(walk.filter(Files::isRegularFile).map(p -> p.getFileName().toString()))
                    .noneMatch(name -> name.startsWith("..render-"));
        }
    }

    /** Child-process entry point executed under {@code umask 077}. */
    public static final class UmaskProbe {

        private UmaskProbe() {
        }

        public static void main(String[] args) throws IOException {
            Path work = Path.of(args[0]);
            Path control = Files.createTempFile(work, "control-", ".tmp",
                    FileModes.creation(work.getFileSystem(), FileModes.WORLD_READABLE));
            Files.move(control, work.resolve("control.txt"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            DeployFiles files = new DeployFiles();
            files.copy(work.resolve("source.jar"), work.resolve("copied.jar"));
            files.write(RELATIVE_POSIX, work.resolve("root"),
                    List.of(new RenderedFile("/etc/agent-ledger-mcp/mcp.yaml", "server:\n  port: 8765\n")),
                    (backup, how) -> {});
            files.writeIntoHost(RELATIVE_POSIX, work.resolve("host-root"),
                    List.of(new RenderedFile("/etc/agent-ledger-mcp/admin.yaml", "server:\n  port: 8865\n")),
                    (backup, how) -> {});
        }
    }
}
