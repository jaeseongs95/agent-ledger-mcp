package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HK-018 (contents) and the static half of HK-021 (manifest, class file version). The jar is the executable jar of the
 * Spring Boot plugin: the classes of the module are in {@code BOOT-INF/classes}, every dependency is a nested jar in
 * {@code BOOT-INF/lib}, and the launcher of Spring Boot is at the top.
 */
class JarContentTest {

    private static final Path JAR = Path.of(System.getProperty("ledger.hook.jar"));

    private static final String CLASSES = "BOOT-INF/classes/";
    private static final String LIB = "BOOT-INF/lib/";

    /** Everything a hook jar may hold at its top level; anything else fails the test. A change of this list needs review. */
    private static final Pattern SIGNATURE_FILE = Pattern.compile("META-INF/.*\\.(SF|DSA|RSA)");
    private static final Pattern SECRET_NAME = Pattern.compile("secret|token|credential|password",
            Pattern.CASE_INSENSITIVE);
    /** The nested jars that the hook must never carry: a JDBC driver or pool, data access, a web server or a web framework. */
    private static final Pattern FORBIDDEN_LIBRARY = Pattern.compile("(postgresql|h2|mysql-connector-j|mariadb-java-client|ojdbc[0-9]*"
            + "|mssql-jdbc|HikariCP|spring-jdbc|spring-data-[a-z-]+|spring-tx|spring-web[a-z-]*|tomcat-[a-z-]+|jetty-[a-z-]+"
            + "|undertow-[a-z-]+|netty-[a-z-]+)-[0-9][A-Za-z0-9._-]*\\.jar");
    private static final List<Pattern> ALLOWED = List.of(
            // the module's own classes, and the two settings files of the application (no secret: checked below)
            Pattern.compile("BOOT-INF/classes/org/pgledger/hook/.*"),
            Pattern.compile("BOOT-INF/classes/(application\\.properties|logback-spring\\.xml)"),
            Pattern.compile("BOOT-INF/(classpath|layers)\\.idx"),
            // the launcher of Spring Boot that starts the application from the nested jars
            Pattern.compile("org/springframework/boot/loader/.*"),
            Pattern.compile("META-INF/MANIFEST\\.MF"),
            Pattern.compile("META-INF/(LICENSE|NOTICE)[A-Za-z0-9._-]*"),
            Pattern.compile("META-INF/services/[A-Za-z0-9._$]+"),
            // the nested jars: the attestation token and key text forms (JDK only), Jackson, and Spring Boot with its logging
            Pattern.compile("BOOT-INF/lib/(ledger-attestation|jackson-(core|databind|annotations)|spring-[a-z-]+|logback-(classic|core)"
                    + "|log4j-(api|to-slf4j)|jul-to-slf4j|slf4j-api|commons-logging|jspecify|jakarta\\.annotation-api|snakeyaml"
                    + "|micrometer-(commons|observation))"
                    + "-[0-9][A-Za-z0-9._-]*\\.jar"),
            // the licence and notice texts of each dependency, copied out of the nested jars by the build
            Pattern.compile("META-INF/third-party/[A-Za-z0-9._-]+/[A-Za-z0-9._-]*(LICENSE|NOTICE)[A-Za-z0-9._-]*"));

    private static List<String> entries() throws IOException {
        List<String> names = new ArrayList<>();
        try (JarFile jar = new JarFile(JAR.toFile())) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (!entry.isDirectory()) {
                    names.add(entry.getName());
                }
            }
        }
        return names;
    }

    /** The names of the files inside one nested jar of {@code BOOT-INF/lib} whose file name starts with {@code prefix}. */
    private static List<String> nestedEntries(String prefix) throws IOException {
        List<String> names = new ArrayList<>();
        try (JarFile jar = new JarFile(JAR.toFile())) {
            JarEntry nested = Collections.list(jar.entries()).stream()
                    .filter(entry -> entry.getName().startsWith(LIB + prefix)).findFirst()
                    .orElseThrow(() -> new AssertionError("no nested jar " + prefix + "* in " + LIB));
            try (JarInputStream in = new JarInputStream(jar.getInputStream(nested))) {
                for (JarEntry entry = in.getNextJarEntry(); entry != null; entry = in.getNextJarEntry()) {
                    if (!entry.isDirectory()) {
                        names.add(entry.getName());
                    }
                }
            }
        }
        return names;
    }

    @Test
    @DisplayName("HK-018 the jar holds only the module's classes, the launcher of Spring Boot, nested jars of the attestation classes, Jackson and Spring Boot with its logging, and the licence texts")
    void onlyModuleAndItsLibraries() throws IOException {
        List<String> names = entries();
        assertThat(names).isNotEmpty();
        assertThat(names).allSatisfy(name -> assertThat(ALLOWED.stream().anyMatch(p -> p.matcher(name).matches())).as(name).isTrue());
        assertThat(names).anyMatch(n -> n.startsWith(CLASSES + "org/pgledger/hook/Main"));
        assertThat(names).anyMatch(n -> n.startsWith("org/springframework/boot/loader/"));
        assertThat(names).anyMatch(n -> n.startsWith(LIB + "ledger-attestation-"));
        assertThat(names).anyMatch(n -> n.startsWith(LIB + "jackson-core-"));
        assertThat(names).anyMatch(n -> n.startsWith(LIB + "jackson-databind-"));
        assertThat(names).anyMatch(n -> n.startsWith(LIB + "jackson-annotations-"));
        assertThat(names).anyMatch(n -> n.startsWith(LIB + "spring-boot-"));
        assertThat(names).anyMatch(n -> n.startsWith(LIB + "logback-classic-"));
        assertThat(nestedEntries("ledger-attestation-")).contains("org/pgledger/attestation/AttestationToken.class",
                "org/pgledger/attestation/PublicKeys.class");
        assertThat(nestedEntries("jackson-core-")).anyMatch(n -> n.startsWith("tools/jackson/core/"));
        assertThat(nestedEntries("jackson-databind-")).anyMatch(n -> n.startsWith("tools/jackson/databind/"));
    }

    @Test
    @DisplayName("HK-018 no JDBC driver, no web server, no secret or configuration file with secrets, no signature or module descriptor")
    void nothingElse() throws IOException {
        List<String> names = entries();
        assertThat(names).noneMatch(n -> n.startsWith(LIB) && FORBIDDEN_LIBRARY.matcher(n.substring(LIB.length())).matches())
                .noneMatch(n -> n.startsWith("org/postgresql/") || n.startsWith("org/h2/") || n.startsWith("com/mysql/")
                        || n.startsWith("org/mariadb/") || n.startsWith("oracle/") || n.startsWith("com/microsoft/"))
                .noneMatch(n -> n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".toml") || n.endsWith(".env")
                        || n.endsWith(".pem") || n.endsWith(".key") || n.endsWith(".json"))
                .noneMatch(n -> n.endsWith(".properties") && !"BOOT-INF/classes/application.properties".equals(n))
                .noneMatch(n -> n.endsWith(".xml") && !"BOOT-INF/classes/logback-spring.xml".equals(n))
                .noneMatch(n -> SIGNATURE_FILE.matcher(n).matches())
                .noneMatch(n -> n.endsWith("module-info.class"))
                // class names such as JsonToken are fine; a non-class resource with such a name is not
                .noneMatch(n -> !n.endsWith(".class") && !n.startsWith("META-INF/services/") && SECRET_NAME.matcher(n).find());
    }

    @Test
    @DisplayName("HK-021 the manifest names the launcher of Spring Boot as Main-Class and org.pgledger.hook.Main as Start-Class, and the jar is the only thing needed to run it (no Class-Path)")
    void manifest() throws IOException {
        try (JarFile jar = new JarFile(JAR.toFile())) {
            Attributes main = jar.getManifest().getMainAttributes();
            assertThat(main.getValue("Main-Class")).isEqualTo("org.springframework.boot.loader.launch.JarLauncher");
            assertThat(main.getValue("Start-Class")).isEqualTo("org.pgledger.hook.Main");
            assertThat(main.getValue("Class-Path")).isNull();
        }
    }

    @Test
    @DisplayName("HK-021 the module's classes are Java 25 class files (major 69): older JDKs refuse them with UnsupportedClassVersionError")
    void classFilesNeedJava25() throws IOException {
        try (JarFile jar = new JarFile(JAR.toFile())) {
            List<JarEntry> own = Collections.list(jar.entries()).stream()
                    .filter(e -> e.getName().startsWith(CLASSES + "org/pgledger/hook/") && e.getName().endsWith(".class")).toList();
            assertThat(own).isNotEmpty();
            for (JarEntry entry : own) {
                try (InputStream in = jar.getInputStream(entry)) {
                    byte[] header = in.readNBytes(8);
                    int major = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
                    assertThat(major).as(entry.getName()).isEqualTo(69);
                }
            }
        }
    }

    @Test
    @DisplayName("HK-018 the licence and notice texts of the dependencies are kept in the jar under META-INF/third-party")
    void licenceTextsAreKept() throws IOException {
        List<String> names = entries();
        assertThat(names).anyMatch(n -> n.matches("META-INF/third-party/jackson-databind-[^/]+/[^/]*(LICENSE|NOTICE)[^/]*"));
        assertThat(names).anyMatch(n -> n.matches("META-INF/third-party/jackson-core-[^/]+/[^/]*(LICENSE|NOTICE)[^/]*"));
    }
}
