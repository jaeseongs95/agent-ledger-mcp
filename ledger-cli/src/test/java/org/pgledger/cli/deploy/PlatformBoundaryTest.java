package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.deploy.DeployLayout.Platform;

/**
 * DPL-02, DPL-03: what differs between the operating system families is in the platform adapters ({@link ServicePlatform}) and
 * nowhere else. The syntax of a path as a platform writes it into its files (a Linux path, a drive path) is the adapter's,
 * and the code that is common to the platforms holds none.
 */
class PlatformBoundaryTest {

    private static final Path MAIN = Path.of("src", "main", "java", "org", "pgledger", "cli");

    private final ServicePlatforms platforms = DeployTestBeans.platforms();

    @Test
    @DisplayName("DPL-02 a path as a platform writes it into its files is put below a root by that platform: a Linux path drops its leading slash, a Windows path makes its drive letter a directory name")
    void dpl02_aPathBelowARoot() {
        Path root = Path.of("root");

        assertThat(platforms.of(Platform.LINUX).hostPath(root, "/etc/x/mcp.yaml")).isEqualTo(root.resolve("etc/x/mcp.yaml"));
        assertThat(platforms.of(Platform.WINDOWS).hostPath(root, "C:/ProgramData/x/lib")).isEqualTo(root.resolve("C/ProgramData/x/lib"));
    }

    @ParameterizedTest
    @EnumSource(Platform.class)
    @DisplayName("DPL-02 without a root a path that is not an absolute path of this host is refused naming platform, whichever platform wrote it (the file system of the host decides)")
    void dpl02_aPathWithoutARootIsAPathOfTheHost(Platform platform) {
        assertThatThrownBy(() -> platforms.of(platform).hostPath(null, "relative/x"))
                .isInstanceOfSatisfying(UsageException.class, refused -> {
                    assertThat(refused.code()).isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE);
                    assertThat(refused.arguments()).containsExactly(DeployOptions.PLATFORM);
                });
    }

    @Test
    @DisplayName("DPL-03 the code common to the platforms (the writer of files, the forms of the values, the reader of the TLS options) holds no drive letter pattern and no Windows path form: the paths are Path and the file system of the host")
    void dpl03_theCommonCodeHoldsNoWindowsPathForm() throws IOException {
        for (String file : new String[] { "deploy/DeployFiles.java", "tls/TlsRequestReader.java" }) {
            // the class of the letters of a drive, [A-Za-z], closed right after the z
            assertThat(Files.readString(MAIN.resolve(file))).as(file).doesNotContain("WINDOWS_PATH").doesNotContain("A-Za-z]");
        }
        assertThat(Files.readString(MAIN.resolve("deploy/DeployForms.java"))).doesNotContain("WINDOWS_PATH");
    }
}
