// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.cli.ErrorCode;
import org.veridger.cli.UsageException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.PropertyPlaceholderHelper;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * INS-015 .. INS-019, INS-030 and INS-032 (Windows): the files of the deployment are rendered from parameters as text, with
 * no root, no Linux, no Windows and no file access. The three targets (a personal PC, a Windows server, a Linux server) are
 * three sets of parameters.
 */
class DeployRendererTest {

    private static final String MCP_UNIT = "/etc/systemd/system/veridger.service";
    private static final String ADMIN_UNIT = "/etc/systemd/system/veridger-admin.service";
    private static final String HOME = "C:/ProgramData/veridger";

    /** A Linux server. */
    private static Map<String, String> values() {
        Map<String, String> values = new HashMap<>();
        values.put(DeployOptions.JAVA, "/usr/lib/jvm/java-25/bin/java");
        values.put(DeployOptions.JDBC_URL, "jdbc:postgresql://db.example.org:5432/ledger");
        values.put(DeployOptions.APP_ACCOUNT, "ledger_app");
        return values;
    }

    /** A Linux server with the one host name of each service (the names the operator's reverse proxy passes). */
    private static Map<String, String> withHosts() {
        Map<String, String> values = values();
        values.put(DeployOptions.SCHEMA, "ledger");
        values.put(DeployOptions.MCP_HOST, "mcp.example.org");
        values.put(DeployOptions.ADMIN_HOST, "admin.example.org");
        return values;
    }

    /** A personal PC: Windows, no host names. */
    private static Map<String, String> personalPc() {
        Map<String, String> values = new HashMap<>();
        values.put(DeployOptions.PLATFORM, "windows");
        values.put(DeployOptions.HOME, HOME);
        values.put(DeployOptions.JAVA, "C:/Program Files/Java/jdk-25/bin/java.exe");
        values.put(DeployOptions.JDBC_URL, "jdbc:postgresql://localhost:5432/ledger");
        values.put(DeployOptions.APP_ACCOUNT, "ledger_app");
        return values;
    }

    /** A Windows server: the same, with the public host names of the reverse proxy the operator put in front. */
    private static Map<String, String> windowsServer() {
        Map<String, String> values = personalPc();
        values.put(DeployOptions.MCP_HOST, "mcp.example.org");
        values.put(DeployOptions.ADMIN_HOST, "admin.example.org");
        return values;
    }

    private static String file(List<RenderedFile> files, String path) {
        return files.stream().filter(f -> f.path().equals(path)).map(RenderedFile::content).findFirst().orElseThrow();
    }

    private static List<RenderedFile> render(Map<String, String> values) {
        return DeployTestBeans.render(values);
    }

    // ---- INS-015: the Linux units ----

    @ParameterizedTest
    @MethodSource("units")
    @DisplayName("INS-015 each unit runs as the one service user, never root, with the hardening set and its own jar and settings file")
    void ins015_theUnitsAreHardenedAndNotRoot(String path, String jar, String settingsFile, String readWritePaths) {
        String unit = file(render(withHosts()), path);

        assertThat(unit.lines().toList()).contains("User=veridger", "Group=veridger", "NoNewPrivileges=true",
                "ProtectSystem=strict", "ProtectHome=true", "PrivateTmp=true", "PrivateDevices=true",
                "ProtectKernelTunables=true", "ProtectKernelModules=true", "ProtectControlGroups=true",
                "RestrictSUIDSGID=true", "LockPersonality=true", "RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX",
                "CapabilityBoundingSet=", "UMask=0077", "SuccessExitStatus=143", "Restart=on-failure");
        assertThat(unit).doesNotContain("User=root", "Group=root");
        assertThat(unit.lines().filter(line -> line.startsWith("ReadWritePaths=")).toList())
                .isEqualTo(readWritePaths.isEmpty() ? List.of() : List.of("ReadWritePaths=" + readWritePaths));
        String start = unit.lines().filter(line -> line.startsWith("ExecStart=")).findFirst().orElseThrow();
        assertThat(start).isEqualTo("ExecStart=/usr/lib/jvm/java-25/bin/java -Duser.timezone=UTC -jar /opt/veridger/" + jar
                + " --spring.config.import=file:/etc/veridger/application.yaml,file:/etc/veridger/" + settingsFile
                + ",optional:file:/etc/veridger/local.yaml,optional:file:/etc/veridger/jdbc-driver.yaml,"
                + "optional:configtree:/etc/veridger/secrets/common/,optional:configtree:/etc/veridger/secrets/"
                + ("mcp.yaml".equals(settingsFile) ? "mcp" : "admin") + "/");
    }

    static Stream<Arguments> units() {
        // ATA-036: the MCP server writes the attachment files, so its read-only file system has that one folder open
        return Stream.of(Arguments.of(MCP_UNIT, "ledger-mcp-server.jar", "mcp.yaml", "/var/lib/veridger/attachments"),
                Arguments.of(ADMIN_UNIT, "ledger-admin-web.jar", "admin.yaml", "/etc/veridger"));
    }

    // ---- INS-016: the settings ----

    /** The settings file as Spring Boot reads it: the same loader, so the file parses as YAML and its keys are the flat keys of the properties. */
    private static EnumerablePropertySource<?> yaml(String text) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("rendered",
                new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)));
        return (EnumerablePropertySource<?>) sources.getFirst();
    }

    @Test
    @DisplayName("INS-016 the shared settings hold the JDBC URL and the one application account; each service file holds its port and hosts; no secret")
    void ins016_theSettings() throws IOException {
        List<RenderedFile> files = render(withHosts());

        EnumerablePropertySource<?> shared = yaml(file(files, "/etc/veridger/application.yaml"));
        assertThat(shared.getProperty("ledger.jdbc.url")).isEqualTo("jdbc:postgresql://db.example.org:5432/ledger");
        assertThat(shared.getProperty("ledger.jdbc.username")).isEqualTo("ledger_app");
        assertThat(shared.getProperty("ledger.schema.name")).isEqualTo("ledger");
        // the long text type of the schema files is declared by the dialect of SqlBuilder (SqlBuilder.longTextType): there is no setting for it
        assertThat(Arrays.asList(shared.getPropertyNames())).filteredOn(name -> name.startsWith("ledger.schema."))
                .containsExactly("ledger.schema.name");

        EnumerablePropertySource<?> mcp = yaml(file(files, "/etc/veridger/mcp.yaml"));
        assertThat(mcp.getProperty("server.port")).isEqualTo(8765);
        assertThat(mcp.getProperty("ledger.mcp.allowed-hosts[0]")).isEqualTo("127.0.0.1");
        assertThat(mcp.getProperty("ledger.mcp.allowed-hosts[1]")).isEqualTo("localhost");
        assertThat(mcp.getProperty("ledger.mcp.allowed-hosts[2]")).isEqualTo("mcp.example.org");

        EnumerablePropertySource<?> admin = yaml(file(files, "/etc/veridger/admin.yaml"));
        assertThat(admin.getProperty("server.port")).isEqualTo(8865);
        assertThat(admin.getProperty("ledger.admin.allowed-hosts[2]")).isEqualTo("admin.example.org");
        assertThat(admin.getProperty("ledger.admin.passkey.rp-id")).isEqualTo("admin.example.org");
        assertThat(admin.getProperty("ledger.admin.passkey.origins[0]")).isEqualTo("https://admin.example.org");
        assertThat(admin.getProperty("ledger.admin.passkey.origins[1]")).isNull();
        assertThat(admin.getProperty("ledger.admin.driver-file")).isEqualTo("/etc/veridger/jdbc-driver.yaml");
        assertThat(admin.getProperty("ledger.admin.driver-directory")).isEqualTo("/etc/veridger/drivers");
        assertThat(admin.getProperty("ledger.admin.mcp.health-url")).isEqualTo("http://127.0.0.1:8765/healthz");
        for (EnumerablePropertySource<?> settings : List.of(shared, mcp, admin)) {
            assertThat(List.of(settings.getPropertyNames())).noneMatch(name -> name.matches("(?i).*(password|token|secret).*"));
        }
    }

    @Test
    @DisplayName("INS-016 the schema keys are written only when they are given; the loopback hosts are the only ones without host names")
    void ins016_optionalKeysAreAbsentWhenNotGiven() throws IOException {
        List<RenderedFile> files = render(values());

        assertThat(List.of(yaml(file(files, "/etc/veridger/application.yaml")).getPropertyNames()))
                .containsExactlyInAnyOrder("ledger.jdbc.url", "ledger.jdbc.username", "ledger.attachments.directory",
                        "ledger.attachments.key-directory");
        assertThat(List.of(yaml(file(files, "/etc/veridger/mcp.yaml")).getPropertyNames())).containsExactlyInAnyOrder(
                "server.port", "ledger.mcp.allowed-hosts[0]", "ledger.mcp.allowed-hosts[1]");
        assertThat(List.of(yaml(file(files, "/etc/veridger/admin.yaml")).getPropertyNames()))
                .as("no public admin host: the admin web derives localhost for its passkeys").noneMatch(name -> name.contains("passkey"));
    }

    @Test
    @DisplayName("INS-016 (PSK-INS-01, changed by PCS-022) the admin host name gives the passkey relying party: the rp-id is the name and the one origin is https:// that name")
    void ins016_thePasskeyRelyingParty() throws IOException {
        Map<String, String> values = values();
        values.put(DeployOptions.MCP_HOST, "mcp.example.org");
        values.put(DeployOptions.ADMIN_HOST, "ledger.example.org");

        EnumerablePropertySource<?> admin = yaml(file(render(values), "/etc/veridger/admin.yaml"));

        assertThat(admin.getProperty("ledger.admin.passkey.rp-id")).isEqualTo("ledger.example.org");
        assertThat(admin.getProperty("ledger.admin.passkey.origins[0]")).isEqualTo("https://ledger.example.org");
        assertThat(admin.getProperty("ledger.admin.passkey.origins[1]")).isNull();
        assertThat(admin.getProperty("ledger.admin.allowed-hosts[2]")).isEqualTo("ledger.example.org");
        assertThat(admin.getProperty("ledger.admin.allowed-hosts[3]")).isNull();
    }

    // ---- INS-017 (removed by PCS: no nginx site) ----

    @Test
    @DisplayName("PCS-002 a Linux server renders the two units and the three settings files for every option: no proxy site is written, whatever the host names")
    void pcs002_noSiteIsRendered() {
        List<String> expected = List.of(MCP_UNIT, ADMIN_UNIT, "/etc/veridger/application.yaml",
                "/etc/veridger/mcp.yaml", "/etc/veridger/admin.yaml");

        assertThat(render(values()).stream().map(RenderedFile::path)).containsExactlyElementsOf(expected);
        assertThat(render(withHosts()).stream().map(RenderedFile::path)).containsExactlyElementsOf(expected);
    }

    // ---- INS-018 ----

    @ParameterizedTest
    @MethodSource("targets")
    @DisplayName("INS-018 the rendering is deterministic: the same text every time, line feeds only, one trailing line feed, absolute paths")
    void ins018_isDeterministic(String target, Map<String, String> values) {
        List<RenderedFile> first = render(values);
        List<RenderedFile> second = render(values);

        assertThat(second).as(target).isEqualTo(first);
        assertThat(first).allSatisfy(file -> {
            assertThat(file.content()).doesNotContain("\r").endsWith("\n").doesNotEndWith("\n\n");
            assertThat(file.path()).matches("(/|[A-Za-z]:/).*");
        });
    }

    static Stream<Arguments> targets() {
        return Stream.of(Arguments.of("Linux server", withHosts()), Arguments.of("personal PC", personalPc()),
                Arguments.of("Windows server", windowsServer()));
    }

    // ---- INS-019 ----

    static Stream<Arguments> unsafeValues() {
        String twoLines = "/usr/bin/java\nUser=root";
        return Stream.of(
                Arguments.of(DeployOptions.JAVA, "usr/bin/java"), Arguments.of(DeployOptions.JAVA, "/usr/bin/../bin/java"),
                Arguments.of(DeployOptions.JAVA, "/usr/bin/j ava"), Arguments.of(DeployOptions.JAVA, twoLines),
                Arguments.of(DeployOptions.JAVA, "/usr/bin/java$HOME"), Arguments.of(DeployOptions.JAVA, "/usr/%h/java"),
                Arguments.of(DeployOptions.JAVA, "/usr/bin/'java'"), Arguments.of(DeployOptions.JAVA, "/usr/bin/./java"),
                Arguments.of(DeployOptions.JAVA, "/usr/bin\\java"), Arguments.of(DeployOptions.JAVA, "/"),
                Arguments.of(DeployOptions.JDBC_URL, "postgresql://h/db"), Arguments.of(DeployOptions.JDBC_URL, "jdbc:postgresql://h/db?password=x"),
                Arguments.of(DeployOptions.JDBC_URL, "jdbc:postgresql://h/db?PassWord=x"),
                Arguments.of(DeployOptions.JDBC_URL, "jdbc:postgresql://h/db'\nx: y"), Arguments.of(DeployOptions.JDBC_URL, "jdbc:p://h/${x}"),
                Arguments.of(DeployOptions.JDBC_URL, "jdbc:p://h/ db"),
                Arguments.of(DeployOptions.APP_ACCOUNT, "a b"), Arguments.of(DeployOptions.APP_ACCOUNT, "a;drop"),
                Arguments.of(DeployOptions.APP_ACCOUNT, "1abc"),
                Arguments.of(DeployOptions.SCHEMA, "Ledger"), Arguments.of(DeployOptions.SCHEMA, "led-ger"),
                Arguments.of(DeployOptions.MCP_PORT, "80"), Arguments.of(DeployOptions.MCP_PORT, "70000"),
                Arguments.of(DeployOptions.MCP_PORT, "abc"), Arguments.of(DeployOptions.ADMIN_PORT, "8765"),
                Arguments.of(DeployOptions.MCP_HOST, "Mcp.Example.org"), Arguments.of(DeployOptions.MCP_HOST, "mcp_host.example.org"),
                Arguments.of(DeployOptions.MCP_HOST, "mcp.example.org/x"), Arguments.of(DeployOptions.MCP_HOST, "mcp.example.org:443"),
                Arguments.of(DeployOptions.MCP_HOST, "-mcp.example.org"), Arguments.of(DeployOptions.MCP_HOST, "mcp.example.org\nx"),
                Arguments.of(DeployOptions.ADMIN_HOST, "mcp.example.org"),
                Arguments.of(DeployOptions.PLATFORM, "macos"), Arguments.of(DeployOptions.PLATFORM, "Linux"),
                Arguments.of(DeployOptions.HOME, "/opt/x"));
    }

    @ParameterizedTest
    @MethodSource("unsafeValues")
    @DisplayName("INS-019 an unsafe value is refused with the name of the option and never the value")
    void ins019_unsafeValuesAreRefused(String option, String value) {
        Map<String, String> values = withHosts();
        values.put(option, value);

        assertThatThrownBy(() -> DeployTestBeans.parameters(values)).isInstanceOfSatisfying(UsageException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE);
            assertThat(e.arguments()).containsExactly(option);
        });
    }

    static Stream<Arguments> unsafeWindowsValues() {
        return Stream.of(
                Arguments.of(DeployOptions.HOME, "/opt/x"), Arguments.of(DeployOptions.HOME, "C:/Program Files/x"),
                Arguments.of(DeployOptions.HOME, "C:\\ProgramData\\x"), Arguments.of(DeployOptions.HOME, "C:/x/../y"),
                Arguments.of(DeployOptions.HOME, "C:/x$y"), Arguments.of(DeployOptions.HOME, "C:"),
                Arguments.of(DeployOptions.JAVA, "/usr/bin/java"), Arguments.of(DeployOptions.JAVA, "C:/Program Files/<x>/java.exe"),
                Arguments.of(DeployOptions.JAVA, "C:/Program Files/a&b/java.exe"), Arguments.of(DeployOptions.JAVA, "C:/a\"b/java.exe"),
                Arguments.of(DeployOptions.JAVA, "C:/a\nb/java.exe"));
    }

    @ParameterizedTest
    @MethodSource("unsafeWindowsValues")
    @DisplayName("INS-032 on Windows the home and the Java path are refused when they are not plain drive paths (a space is allowed only in the Java path)")
    void ins032_unsafeWindowsValuesAreRefused(String option, String value) {
        Map<String, String> values = personalPc();
        values.put(option, value);

        assertThatThrownBy(() -> DeployTestBeans.parameters(values)).isInstanceOfSatisfying(UsageException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE);
            assertThat(e.arguments()).containsExactly(option);
        });
    }

    @Test
    @DisplayName("INS-019 (changed by PCS) neither host name is required; a missing required option is MISSING_ARGUMENT; Windows needs its home")
    void ins019_incompleteParametersAreRefused() {
        assertThat(DeployTestBeans.parameters(values()).mcpHost()).isNull();
        assertThat(DeployTestBeans.parameters(values()).adminHost()).isNull();

        for (String required : List.of(DeployOptions.JAVA, DeployOptions.JDBC_URL, DeployOptions.APP_ACCOUNT)) {
            Map<String, String> missing = values();
            missing.remove(required);
            assertThatThrownBy(() -> DeployTestBeans.parameters(missing)).isInstanceOfSatisfying(UsageException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.MISSING_ARGUMENT));
        }

        Map<String, String> noHome = personalPc();
        noHome.remove(DeployOptions.HOME);
        assertThatThrownBy(() -> DeployTestBeans.parameters(noHome)).isInstanceOfSatisfying(UsageException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.MISSING_ARGUMENT));
    }

    // ---- INS-030 ----

    /** The two properties of a Linux host that the post-processor supplies (R9-D0089): the tool then reads /etc/<name>. */
    private static String linuxHost(String placeholder) {
        Object value = HostDefaults.settingsPlaceholders(org.veridger.files.FileKind.POSIX).get(placeholder);
        return value == null ? null : value.toString();
    }

    @Test
    @DisplayName("INS-030 the command line tool imports for the installation the four sources the units import (without the file of a service), and nothing else")
    void ins030_theToolAndTheServicesReadTheSameSources() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(stream);
        }
        PropertyPlaceholderHelper helper = new PropertyPlaceholderHelper("${", "}", ":", null, true);
        String imported = helper.replacePlaceholders(properties.getProperty("spring.config.import"),
                DeployRendererTest::linuxHost);
        String unitImport = file(render(values()), MCP_UNIT).lines().filter(line -> line.startsWith("ExecStart="))
                .findFirst().orElseThrow().replaceFirst(".*--spring\\.config\\.import=", "");

        List<String> expected = List.of(unitImport.split(",")).stream().filter(source -> !source.endsWith("/mcp.yaml"))
                .map(source -> source.startsWith("optional:") ? source : "optional:" + source).toList();
        assertThat(List.of(imported.split(","))).containsExactlyElementsOf(expected);
        // the placeholder carries the home: the Windows installation gives its own on the command line
        String windows = helper.replacePlaceholders(properties.getProperty("spring.config.import"),
                placeholder -> "ledger.secrets.home".equals(placeholder) ? HOME + "/etc" : linuxHost(placeholder));
        assertThat(windows).contains("optional:file:" + HOME + "/etc/application.yaml",
                "optional:configtree:" + HOME + "/etc/secrets/common/", "optional:configtree:" + HOME + "/etc/secrets/mcp/")
                .doesNotContain("secrets/admin", "secrets/tool");
    }

    // ---- INS-032: Windows ----

    private static Document xml(String text) throws ParserConfigurationException, IOException, SAXException {
        return DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String element(Document document, String name) {
        return document.getElementsByTagName(name).item(0).getTextContent();
    }

    @ParameterizedTest
    @MethodSource("windowsServices")
    @DisplayName("INS-032 each WinSW configuration is well-formed XML with the id of its service, the Java of the parameters, the same arguments as the Linux unit, a restart after a failure and a log folder; it names no account")
    void ins032_theWinswConfiguration(String id, String jar, String settingsFile) throws Exception {
        List<RenderedFile> files = render(personalPc());
        Document configuration = xml(file(files, HOME + "/lib/" + id + ".xml"));

        assertThat(configuration.getDocumentElement().getTagName()).isEqualTo("service");
        assertThat(element(configuration, "id")).isEqualTo(id);
        assertThat(element(configuration, "name")).isEqualTo(id);
        assertThat(element(configuration, "executable")).isEqualTo("C:\\Program Files\\Java\\jdk-25\\bin\\java.exe");
        assertThat(element(configuration, "arguments")).isEqualTo("-Duser.timezone=UTC -jar " + HOME + "/lib/" + jar
                + " --spring.config.import=file:" + HOME + "/etc/application.yaml,file:" + HOME + "/etc/" + settingsFile
                + ",optional:file:" + HOME + "/etc/local.yaml,optional:file:" + HOME + "/etc/jdbc-driver.yaml,"
                + "optional:configtree:" + HOME + "/etc/secrets/common/,optional:configtree:" + HOME + "/etc/secrets/"
                + ("mcp.yaml".equals(settingsFile) ? "mcp" : "admin") + "/");
        assertThat(element(configuration, "logpath")).isEqualTo("C:\\ProgramData\\veridger\\logs");
        assertThat(element(configuration, "startmode")).isEqualTo("Automatic");
        assertThat(configuration.getElementsByTagName("onfailure").getLength()).isPositive();
        assertThat(configuration.getElementsByTagName("onfailure").item(0).getAttributes().getNamedItem("action")
                .getNodeValue()).isEqualTo("restart");
        assertThat(configuration.getElementsByTagName("serviceaccount").getLength())
                .as("the one account is set by deploy/install.cmd with sc.exe config").isZero();
    }

    static Stream<Arguments> windowsServices() {
        return Stream.of(Arguments.of("veridger", "ledger-mcp-server.jar", "mcp.yaml"),
                Arguments.of("veridger-admin", "ledger-admin-web.jar", "admin.yaml"));
    }

    @Test
    @DisplayName("INS-032 a personal PC renders the two WinSW configurations and the settings below its home, no unit, no proxy; its hosts are loopback only")
    void ins032_thePersonalPc() throws IOException {
        List<RenderedFile> files = render(personalPc());

        assertThat(files.stream().map(RenderedFile::path)).containsExactly(HOME + "/lib/veridger.xml",
                HOME + "/lib/veridger-admin.xml", HOME + "/etc/application.yaml", HOME + "/etc/mcp.yaml",
                HOME + "/etc/admin.yaml");
        EnumerablePropertySource<?> admin = yaml(file(files, HOME + "/etc/admin.yaml"));
        assertThat(admin.getProperty("ledger.admin.driver-file")).isEqualTo(HOME + "/etc/jdbc-driver.yaml");
        assertThat(admin.getProperty("ledger.admin.driver-directory")).isEqualTo(HOME + "/etc/drivers");
        assertThat(List.of(yaml(file(files, HOME + "/etc/mcp.yaml")).getPropertyNames())).containsExactlyInAnyOrder(
                "server.port", "ledger.mcp.allowed-hosts[0]", "ledger.mcp.allowed-hosts[1]");
        assertThat(files).noneMatch(f -> f.content().contains("User=") || f.content().contains("nginx"));
    }

    @Test
    @DisplayName("INS-032 a Windows server adds its one host name per service to the allowed hosts; the reverse proxy in front of it is the operator's own")
    void ins032_theWindowsServer() throws IOException {
        List<RenderedFile> files = render(windowsServer());

        assertThat(yaml(file(files, HOME + "/etc/mcp.yaml")).getProperty("ledger.mcp.allowed-hosts[2]"))
                .isEqualTo("mcp.example.org");
        assertThat(yaml(file(files, HOME + "/etc/admin.yaml")).getProperty("ledger.admin.allowed-hosts[2]"))
                .isEqualTo("admin.example.org");
        assertThat(files).hasSize(5);
    }

    // ---- INS-040: the name of the instance ----

    private static Map<String, String> named(Map<String, String> values, String name) {
        values.put(DeployOptions.NAME, name);
        return values;
    }

    @Test
    @DisplayName("INS-040 a Linux instance with a name of its own: the units, the user, the jars directory, the settings directory and the secret directory follow the name, and no file names the default instance")
    void ins040_aNamedLinuxInstance() throws IOException {
        List<RenderedFile> files = render(named(withHosts(), "acme-test"));

        assertThat(files.stream().map(RenderedFile::path)).containsExactly("/etc/systemd/system/acme-test.service",
                "/etc/systemd/system/acme-test-admin.service", "/etc/acme-test/application.yaml", "/etc/acme-test/mcp.yaml",
                "/etc/acme-test/admin.yaml");
        String mcp = file(files, "/etc/systemd/system/acme-test.service");
        String admin = file(files, "/etc/systemd/system/acme-test-admin.service");
        assertThat(mcp.lines().toList()).contains("User=acme-test", "Group=acme-test", "Description=acme-test: the ledger MCP server",
                "ExecStart=/usr/lib/jvm/java-25/bin/java -Duser.timezone=UTC -jar /opt/acme-test/ledger-mcp-server.jar "
                        + "--spring.config.import=file:/etc/acme-test/application.yaml,file:/etc/acme-test/mcp.yaml,"
                        + "optional:file:/etc/acme-test/local.yaml,optional:file:/etc/acme-test/jdbc-driver.yaml,"
                        + "optional:configtree:/etc/acme-test/secrets/common/,optional:configtree:/etc/acme-test/secrets/mcp/");
        assertThat(admin.lines().toList()).contains("User=acme-test", "ReadWritePaths=/etc/acme-test",
                "After=network-online.target acme-test.service");
        assertThat(yaml(file(files, "/etc/acme-test/admin.yaml")).getProperty("ledger.admin.driver-file"))
                .isEqualTo("/etc/acme-test/jdbc-driver.yaml");
        for (RenderedFile rendered : files) {
            assertThat(rendered.path() + rendered.content()).as(rendered.path()).doesNotContain("pg-ledger")
                    .doesNotContain("agent-ledger").doesNotContain("veridger");
        }
    }

    @Test
    @DisplayName("INS-040 a Windows instance with a name of its own: the WinSW ids, the file names and the paths follow the name")
    void ins040_aNamedWindowsInstance() throws Exception {
        Map<String, String> values = named(personalPc(), "acme-test");
        values.put(DeployOptions.HOME, "C:/ProgramData/acme-test");
        List<RenderedFile> files = render(values);

        assertThat(files.stream().map(RenderedFile::path)).containsExactly("C:/ProgramData/acme-test/lib/acme-test.xml",
                "C:/ProgramData/acme-test/lib/acme-test-admin.xml", "C:/ProgramData/acme-test/etc/application.yaml",
                "C:/ProgramData/acme-test/etc/mcp.yaml", "C:/ProgramData/acme-test/etc/admin.yaml");
        Document admin = xml(file(files, "C:/ProgramData/acme-test/lib/acme-test-admin.xml"));
        assertThat(element(admin, "id")).isEqualTo("acme-test-admin");
        assertThat(element(admin, "name")).isEqualTo("acme-test-admin");
        assertThat(element(admin, "logpath")).isEqualTo("C:\\ProgramData\\acme-test\\logs");
        assertThat(element(admin, "arguments")).contains("-jar C:/ProgramData/acme-test/lib/ledger-admin-web.jar")
                .contains("optional:configtree:C:/ProgramData/acme-test/etc/secrets/common/,"
                        + "optional:configtree:C:/ProgramData/acme-test/etc/secrets/admin/");
        for (RenderedFile rendered : files) {
            assertThat(rendered.path() + rendered.content()).as(rendered.path()).doesNotContain("pg-ledger")
                    .doesNotContain("agent-ledger").doesNotContain("veridger");
        }
    }

    @Test
    @DisplayName("INS-040 the default name is veridger: giving it explicitly changes nothing, and the second service is <name>-admin")
    void ins040_theDefaultName() {
        assertThat(render(named(withHosts(), "veridger"))).isEqualTo(render(withHosts()));
        assertThat(render(named(personalPc(), "veridger"))).isEqualTo(render(personalPc()));
        assertThat(DeployTestBeans.defaults().instanceName()).isEqualTo("veridger");
        assertThat(DeployLayout.linux(DeployTestBeans.defaults().instanceName()).adminName()).isEqualTo("veridger-admin");
    }

    static Stream<String> badNames() {
        return Stream.of("", "A", "Acme", "acme_test", "acme test", "acme\ntest", "acme/test", "../acme", "acme..test",
                "acme.test", "-acme", "acme-", "1acme", "acme-admin", "x".repeat(24), "ünï", "acme$", "acme%h", "acme'",
                "acme;rm", "a b");
    }

    @ParameterizedTest
    @MethodSource("badNames")
    @DisplayName("INS-040 a name that is not lower-case letters, digits and single hyphens, starts with a letter, ends with a letter or digit, is at most 23 characters, ends with -admin, or holds a path, a space or a line end is refused with the name of the option")
    void ins040_aBadNameIsRefused(String name) {
        Map<String, String> values = named(withHosts(), name);

        assertThatThrownBy(() -> DeployTestBeans.parameters(values)).isInstanceOfSatisfying(UsageException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE);
            assertThat(e.arguments()).containsExactly(DeployOptions.NAME);
        });
    }

    static Stream<String> goodNames() {
        return Stream.of("a", "ab", "acme-test", "acme-1", "a1", "pg-ledger-test", "x".repeat(23));
    }

    @ParameterizedTest
    @MethodSource("goodNames")
    @DisplayName("INS-040 a plain instance name is accepted (up to 23 characters)")
    void ins040_aPlainNameIsAccepted(String name) {
        assertThat(render(named(values(), name))).hasSize(5);
    }

    @Test
    @DisplayName("INS-040 the CLI imports the sources of the instance it is told about: ledger.secrets.instance=<name> gives the settings directory of that instance and nothing of the default one")
    void ins040_theToolFollowsTheInstance() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(stream);
        }
        PropertyPlaceholderHelper helper = new PropertyPlaceholderHelper("${", "}", ":", null, true);
        String imported = helper.replacePlaceholders(properties.getProperty("spring.config.import"),
                placeholder -> "ledger.secrets.instance".equals(placeholder) ? "acme-test" : linuxHost(placeholder));

        assertThat(imported).contains("optional:file:/etc/acme-test/application.yaml",
                "optional:configtree:/etc/acme-test/secrets/common/", "optional:configtree:/etc/acme-test/secrets/mcp/")
                .doesNotContain("pg-ledger").doesNotContain("agent-ledger").doesNotContain("veridger");
    }
}
