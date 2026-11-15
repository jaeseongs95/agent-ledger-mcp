package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.deploy.DeployLayout.Platform;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.stereotype.Component;

/**
 * DPL-02 .. DPL-08: what differs between the operating system families is in the platform adapters ({@link ServicePlatform})
 * and nowhere else. The syntax of a path as a platform writes it into its files (a Linux path, a drive path), the form of the
 * path of the Java runtime, whether there is an nginx site, and the steps that only some platforms have are the adapter's; the
 * code that is common to the platforms holds none of it.
 */
class PlatformBoundaryTest {

    private static final Path MAIN = Path.of("src", "main", "java", "org", "pgledger", "cli");
    private static final String LINUX_JAVA = "/usr/lib/jvm/java-25/bin/java";
    private static final String WINDOWS_JAVA = "C:/Program Files/Java/jdk-25/bin/java.exe";
    private static final List<String> MCP_HOSTS = List.of("mcp.example.org");

    private final ServicePlatforms platforms = DeployTestBeans.platforms();
    private final ServicePlatform onLinux = platforms.of(Platform.LINUX);
    private final ServicePlatform onWindows = platforms.of(Platform.WINDOWS);
    private final DeployLayout linux = DeployLayout.linux("x");
    private final DeployLayout windows = DeployLayout.windows("x", "C:/ProgramData/x");

    private static DeployParameters parameters(DeployLayout layout, String java, DeployParameters.Nginx site, List<String> mcpHosts) {
        return new DeployParameters(layout, java, "jdbc:postgresql://db.example.org:5432/ledger", "ledger_app", null, null, 8765,
                8865, mcpHosts, List.of(), site);
    }

    @Test
    @DisplayName("DPL-02 a path as a platform writes it into its files is put below a root by that platform: a Linux path drops its leading slash, a Windows path makes its drive letter a directory name")
    void dpl02_aPathBelowARoot() {
        Path root = Path.of("root");

        assertThat(onLinux.hostPath(root, "/etc/x/mcp.yaml")).isEqualTo(root.resolve("etc/x/mcp.yaml"));
        assertThat(onWindows.hostPath(root, "C:/ProgramData/x/lib")).isEqualTo(root.resolve("C/ProgramData/x/lib"));
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
    @DisplayName("DPL-03 the code common to the platforms (the writer of files, the forms of the values, the reader of the TLS options) holds no drive letter pattern and no path form of an operating system: the paths are Path and the file system of the host")
    void dpl03_theCommonCodeHoldsNoPathForm() throws IOException {
        for (String file : new String[] { "deploy/DeployFiles.java", "tls/TlsRequestReader.java" }) {
            // the class of the letters of a drive, [A-Za-z], closed right after the z
            assertThat(Files.readString(MAIN.resolve(file))).as(file).doesNotContain("WINDOWS_PATH").doesNotContain("A-Za-z]");
        }
        assertThat(Files.readString(MAIN.resolve("deploy/DeployForms.java"))).doesNotContain("WINDOWS_PATH")
                .doesNotContain("WINDOWS_JAVA").doesNotContain("POSIX_PATH").doesNotContain("A-Za-z]:");
    }

    @Test
    @DisplayName("DPL-04 each platform judges the form of the path of the Java runtime: Linux refuses a drive path and a space, Windows accepts a drive path with a space (Program Files) and refuses a Linux path")
    void dpl04_theFormOfTheJavaPath() {
        assertThat(onLinux.refusedOptions(parameters(linux, LINUX_JAVA, null, List.of()))).isEmpty();
        assertThat(onLinux.refusedOptions(parameters(linux, WINDOWS_JAVA, null, List.of()))).containsExactly(DeployOptions.JAVA);
        assertThat(onLinux.refusedOptions(parameters(linux, "/usr/lib/jvm/java 25/bin/java", null, List.of())))
                .containsExactly(DeployOptions.JAVA);
        assertThat(onWindows.refusedOptions(parameters(windows, WINDOWS_JAVA, null, List.of()))).isEmpty();
        assertThat(onWindows.refusedOptions(parameters(windows, LINUX_JAVA, null, List.of()))).containsExactly(DeployOptions.JAVA);
    }

    @Test
    @DisplayName("DPL-04 the nginx site is the Linux platform's: Linux needs plain paths and a public host name of the MCP server for it, Windows refuses the site")
    void dpl04_theNginxSite() {
        DeployParameters.Nginx site = new DeployParameters.Nginx("/etc/nginx/conf.d/x.conf", "/etc/ssl/x.pem", "/etc/ssl/x.key");
        DeployParameters.Nginx plain = new DeployParameters.Nginx("rel", "rel", "rel");

        assertThat(onLinux.refusedOptions(parameters(linux, LINUX_JAVA, site, MCP_HOSTS))).isEmpty();
        assertThat(onLinux.refusedOptions(parameters(linux, LINUX_JAVA, site, List.of()))).containsExactly(DeployOptions.MCP_HOST);
        assertThat(onLinux.refusedOptions(parameters(linux, LINUX_JAVA, plain, MCP_HOSTS))).containsExactlyInAnyOrder(
                DeployOptions.NGINX_CONF, DeployOptions.TLS_CERTIFICATE, DeployOptions.TLS_KEY);
        assertThat(onWindows.refusedOptions(parameters(windows, WINDOWS_JAVA, site, MCP_HOSTS)))
                .containsExactly(DeployOptions.NGINX_CONF);
    }

    @Test
    @DisplayName("DPL-05 what only some platforms do is a capability that only they implement: the service wrapper and the seal are Windows's, the directories of the automatic certificate are Linux's; every platform is a ServicePlatform")
    void dpl05_theCapabilities() {
        assertThat(platforms.capability(Platform.WINDOWS, ServiceWrappers.class)).isPresent();
        assertThat(platforms.capability(Platform.WINDOWS, HostSealing.class)).isPresent();
        assertThat(platforms.capability(Platform.WINDOWS, TlsHosting.class)).isEmpty();
        assertThat(platforms.capability(Platform.LINUX, TlsHosting.class)).isPresent();
        assertThat(platforms.capability(Platform.LINUX, ServiceWrappers.class)).isEmpty();
        assertThat(platforms.capability(Platform.LINUX, HostSealing.class)).isEmpty();
        for (Platform platform : Platform.values()) {
            assertThat(platforms.capability(platform, ServicePlatform.class)).isPresent();
        }
    }

    @Test
    @DisplayName("DPL-06 the value record, the layout and the platform enum hold no flag of a platform: the record does not ask which platform it is for and the enum is a name")
    void dpl06_noFlagOfAPlatformInTheCommonTypes() throws IOException {
        assertThat(Files.readString(MAIN.resolve("deploy/DeployParameters.java"))).doesNotContain("platform()")
                .doesNotContain("servesNginx");
        assertThat(Files.readString(MAIN.resolve("deploy/DeployLayout.java"))).doesNotContain("servesNginx")
                .doesNotContain("javaPathForm");
        assertThat(Platform.values()).extracting(Platform::option).containsExactly("linux", "windows");
    }

    @Test
    @DisplayName("DPL-07 the privileges of the Windows token are an adapter bean behind an interface that WindowsAccess is given: no static call, and a context with another implementation starts WindowsAccess with that one")
    void dpl07_theWindowsPrivilegesAreAnAdapterBean() throws IOException, NoSuchMethodException {
        assertThat(WindowsPrivileges.class).isAnnotatedWith(Component.class).isAssignableTo(TokenPrivileges.class);
        assertThat(WindowsPrivileges.class.getDeclaredMethods()).filteredOn(method -> !method.isSynthetic())
                .filteredOn(method -> Modifier.isStatic(method.getModifiers())).isEmpty();
        assertThat(WindowsAccess.class.getDeclaredConstructor(TokenPrivileges.class)).isNotNull();
        assertThat(Files.readString(MAIN.resolve("deploy/WindowsAccess.java"))).doesNotContain("WindowsPrivileges");

        TokenPrivileges recorded = names -> {
            throw new IOException("the privileges of this test");
        };
        new ApplicationContextRunner().withBean(TokenPrivileges.class, () -> recorded).withUserConfiguration(WindowsAccess.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(WindowsAccess.class));
        new ApplicationContextRunner().withUserConfiguration(WindowsAccess.class, WindowsPrivileges.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TokenPrivileges.class));
    }

    @Test
    @DisplayName("DPL-08 the executable jar allows native access with its manifest attribute Enable-Native-Access: ALL-UNNAMED, and no installer gives --enable-native-access on a command line")
    void dpl08_theJarAllowsNativeAccessItself() throws IOException {
        Path root = Path.of(Objects.requireNonNull(System.getProperty("ledger.docs.root"),
                "ledger.docs.root is set by the test task of ledger-cli"));

        assertThat(Files.readString(root.resolve("ledger-cli/build.gradle.kts"), StandardCharsets.UTF_8))
                .contains("\"Enable-Native-Access\" to \"ALL-UNNAMED\"");
        for (String script : new String[] { "deploy/install.cmd", "deploy/install.sh" }) {
            assertThat(Files.readString(root.resolve(script), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT)).as(script)
                    .doesNotContain("--enable-native-access").doesNotContain("%native%");
        }
    }
}
