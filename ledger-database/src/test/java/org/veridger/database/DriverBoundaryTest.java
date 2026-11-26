// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentest4j.TestAbortedException;
import org.veridger.database.driver.DriverDataSources;
import org.veridger.database.driver.DriverLoader;
import org.veridger.database.driver.DriverRegistration;
import org.veridger.database.driver.LedgerDataSourceConfiguration;
import org.veridger.database.driver.LedgerJdbcProperties;
import org.veridger.database.driver.RegisteredDriver;
import org.veridger.database.fake.BaseFakeDriver;
import org.veridger.database.fake.FakeDriverA;
import org.veridger.database.fake.FakeDriverB;
import org.veridger.database.fake.NoDefaultConstructorDriver;
import org.veridger.database.schema.SchemaMigrator;
import org.veridger.database.schema.SchemaProperties;
import org.veridger.database.schema.SchemaVerifier;
import org.veridger.database.support.Databases;
import org.veridger.testing.database.PostgresDriver;
import org.veridger.testing.database.TestDatabaseSettings;
import org.veridger.testing.database.TestSchemaServices;
import org.veridger.database.support.LedgerDatabaseExtension;
import org.veridger.domain.error.ErrorCode;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;
import org.veridger.domain.value.Sha256;
import org.veridger.testing.BroadAccess;
import org.veridger.testing.OwnerOnlyFiles;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** DRV-002 .. DRV-009, DRV-011, DRV-012, DRV-017 .. DRV-019, DRV-021, DRV-025 .. DRV-027, DRV-029: the driver boundary and its
 * registration (DRV-028, the owner refusal, is NOT_RUN: it needs a file of another owner). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(LedgerDatabaseExtension.class)
class DriverBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    /** The loader is a stateless component with no dependency, so the tests make it the way the Spring context does. */
    private static final DriverLoader LOADER = new DriverLoader();

    private Path fakeJar;

    @TempDir
    Path temp;

    @BeforeAll
    void buildTheFakeJar(@TempDir Path shared) throws IOException {
        OwnerOnlyFiles.restrict(shared);
        fakeJar = shared.resolve("fake-drivers.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(fakeJar))) {
            for (Class<?> type : List.of(BaseFakeDriver.class, FakeDriverA.class, FakeDriverB.class,
                    NoDefaultConstructorDriver.class)) {
                String entry = type.getName().replace('.', '/') + ".class";
                jar.putNextEntry(new JarEntry(entry));
                try (var in = DriverBoundaryTest.class.getClassLoader().getResourceAsStream(entry)) {
                    jar.write(in.readAllBytes());
                }
                jar.closeEntry();
            }
        }
        OwnerOnlyFiles.restrict(fakeJar);
    }

    @BeforeEach
    void trustTheTempDirectory() {
        OwnerOnlyFiles.restrict(temp);
    }

    private static Path locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The sample driver jar of the tests: a copy of the PostgreSQL driver jar of the build (its path is the system property
     * {@code ledger.test.postgres-jar}) in a directory of its own that only its owner can use (the loader refuses a jar, or
     * a directory, that other accounts can write, and the Gradle cache is not that on every file system).
     */
    private static final Path DRIVER_JAR = OwnerOnlyFiles.copyToNewDirectory(PostgresDriver.jar());

    private static Path driverJar() {
        return DRIVER_JAR;
    }

    private static String sha(Path file) {
        try {
            return Sha256.ofBytes(Files.readAllBytes(file)).hex();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ErrorCode codeOf(Throwable thrown) {
        return ((InfrastructureException) thrown).code();
    }

    private static InfrastructureException describing(Path jar, String className, String url) {
        return catchThrowableOfType(InfrastructureException.class,
                () -> LOADER.describe(jar, className, url, NOW));
    }

    static Stream<Path> unusableJarPaths() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
        return Stream.of(Path.of("relative.jar"), tmp, tmp.resolve("no-such-driver.jar"),
                tmp.resolve("sub").resolve("..").resolve("x.jar"));
    }

    @ParameterizedTest
    @MethodSource("unusableJarPaths")
    void drv002_aPathThatIsNotAUsableJarIsRefused(Path path) {
        assertThat(codeOf(describing(path, PostgresDriver.CLASS_NAME, "jdbc:postgresql://127.0.0.1:1/x"))).isEqualTo(
                InfrastructureCode.DRIVER_INVALID);
    }

    /** A file that is called a jar and is not one. */
    private record Broken(String name, byte[] content) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Broken> brokenArchives() throws IOException {
        byte[] real = Files.readAllBytes(driverJar());
        return Stream.of(new Broken("text file", "this is not an archive".getBytes(StandardCharsets.UTF_8)),
                new Broken("empty file", new byte[0]),
                new Broken("truncated jar", java.util.Arrays.copyOf(real, 100)));
    }

    @ParameterizedTest
    @MethodSource("brokenArchives")
    void drv003_aFileThatIsNotAJarIsRefused(Broken broken) throws IOException {
        Path file = OwnerOnlyFiles.write(temp.resolve("broken.jar"), broken.content());
        assertThat(codeOf(describing(file, PostgresDriver.CLASS_NAME, "jdbc:postgresql://127.0.0.1:1/x"))).isEqualTo(
                InfrastructureCode.DRIVER_INVALID);
    }

    /** A class name and the part of the reason that tells it apart. */
    private record ClassProblem(String className, String reason) {
    }

    static Stream<ClassProblem> classProblems() {
        return Stream.of(new ClassProblem("org.veridger.database.fake.Missing", "is not in"),
                new ClassProblem("java.lang.String", "does not implement java.sql.Driver"),
                new ClassProblem(NoDefaultConstructorDriver.class.getName(), "no public no-argument constructor"));
    }

    @ParameterizedTest
    @MethodSource("classProblems")
    void drv004_aClassThatIsNotAUsableDriverIsRefusedWithItsOwnReason(ClassProblem problem) {
        InfrastructureException thrown = describing(fakeJar, problem.className(), "jdbc:fake:x");
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(thrown.args().getFirst()).contains(problem.reason());
    }

    @Test
    void drv005_aDriverThatDoesNotAcceptTheUrlIsRefusedAndThePasswordIsNotShown() {
        InfrastructureException thrown = describing(driverJar(), PostgresDriver.CLASS_NAME,
                "jdbc:other://localhost/db?user=a&password=top-secret");
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(thrown.args().getFirst()).doesNotContain("top-secret").contains("***");
    }

    private static Set<String> registeredDrivers() {
        return Collections.list(DriverManager.getDrivers()).stream().map(d -> d.getClass().getName())
                .collect(Collectors.toSet());
    }

    @Test
    void drv006_007_011_theChainIsClassLoaderDriverSimpleDataSourceHikariAndTheDriverManagerIsNotUsed(
            Databases databases) throws SQLException {
        // the real chain on the PostgreSQL of the test PC, in a schema of its own (the product's ledger.schema.name)
        TestDatabaseSettings settings = databases.database().settings();
        String url = settings.url();
        String schema = databases.reserveSchemaName();
        Path jar = driverJar();
        Set<String> before = registeredDrivers();
        DriverRegistration registration = LOADER.describe(jar, PostgresDriver.CLASS_NAME, url, NOW);
        assertThat(registration).isEqualTo(new DriverRegistration(jar, PostgresDriver.CLASS_NAME, sha(jar), NOW));
        LedgerJdbcProperties properties = new LedgerJdbcProperties(url, settings.user(),
                settings.ownerPassword().orElse(null), 2, registration);
        try (RegisteredDriver registered = LOADER.load(registration);
                HikariDataSource pool = DriverDataSources.create(registered, properties, schema)) {
            Driver driver = registered.driver();
            assertThat(registeredDrivers()).as("the DriverManager is not involved while the pool runs").isEqualTo(before);
            assertThat(driver.getClass().getClassLoader()).isInstanceOf(URLClassLoader.class);
            assertThat(pool.getDataSource()).isInstanceOf(SimpleDriverDataSource.class);
            try (Connection c = pool.getConnection(); var s = c.createStatement(); var rs = s.executeQuery("SELECT 1")) {
                assertThat(rs.next()).isTrue();
            }
            SchemaProperties named = new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, true,
                    schema);
            // the pool names the schema itself (it is not there yet): the migrator creates it through the same pool
            TestSchemaServices.migrator(pool, named, java.time.Clock.systemUTC()).migrate();
            TestSchemaServices.verifier(pool, named).verify();
        }
        assertThat(registeredDrivers()).isEqualTo(before);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("DBN-004 the pool asks for READ_COMMITTED with the JDBC standard name, whatever default the DBMS has; "
            + "its connections report it")
    void dbn004_thePoolIsReadCommitted(Databases databases) throws SQLException {
        TestDatabaseSettings settings = databases.database().settings();
        String url = settings.url();
        Path jar = driverJar();
        DriverRegistration registration = LOADER.describe(jar, PostgresDriver.CLASS_NAME, url, NOW);
        LedgerJdbcProperties properties = new LedgerJdbcProperties(url, settings.user(),
                settings.ownerPassword().orElse(null), 2, registration);
        try (RegisteredDriver registered = LOADER.load(registration);
                HikariDataSource pool = DriverDataSources.create(registered, properties, null)) {
            // red before the fix: the pool left the isolation to the default of the DBMS (null here)
            assertThat(pool.getTransactionIsolation()).isEqualTo("TRANSACTION_READ_COMMITTED");
            try (Connection c = pool.getConnection()) {
                assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            }
        }
    }

    @Test
    void drv008_aJarThatChangedSinceRegistrationIsRefusedBeforeAnyConnection() {
        DriverRegistration registration = new DriverRegistration(driverJar(), PostgresDriver.CLASS_NAME,
                "0".repeat(64), NOW);
        InfrastructureException thrown = catchThrowableOfType(InfrastructureException.class,
                () -> LOADER.load(registration));
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(thrown.args().getFirst()).contains("digest mismatch").contains(driverJar().toString());
    }

    @Test
    void drv009_aJarThatIsGoneIsRefusedWithItsPath() throws IOException {
        Path copy = OwnerOnlyFiles.copy(driverJar(), temp.resolve("gone.jar"));
        DriverRegistration registration = LOADER.describe(copy, PostgresDriver.CLASS_NAME, "jdbc:postgresql://127.0.0.1:1/g", NOW);
        Files.delete(copy);
        InfrastructureException thrown = catchThrowableOfType(InfrastructureException.class,
                () -> LOADER.load(registration));
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(thrown.args().getFirst()).contains(copy.toString());
    }

    static Stream<DriverRegistration> unfinishedRegistrations() {
        return Stream.of(new DriverRegistration(null, null, null, null),
                new DriverRegistration(null, "  ", null, null));
    }

    @ParameterizedTest
    @MethodSource("unfinishedRegistrations")
    void drv012_withoutARegistrationTheStartFailsFastAndNamesTheCommand(DriverRegistration none) {
        InfrastructureException thrown = catchThrowableOfType(InfrastructureException.class,
                () -> LOADER.load(none));
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_NOT_CONFIGURED);
        assertThat(thrown.getMessage()).contains("driver-register");
    }

    @Test
    void drv012_aHalfFilledRegistrationNamesTheMissingKey() {
        InfrastructureException thrown = catchThrowableOfType(InfrastructureException.class,
                () -> LOADER.load(new DriverRegistration(driverJar(), null, null, null)));
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(thrown.args().getFirst()).contains("ledger.jdbc.driver.class-name");
    }

    /** DRV-017 (changed by SFR-030): who may write the jar is not looked at, the jar loads. */
    @Test
    void drv017_aJarThatOthersCanWriteIsLoaded() throws IOException {
        assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"), "needs POSIX permissions");
        Path copy = OwnerOnlyFiles.copy(driverJar(), temp.resolve("writable.jar"));
        Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rw-rw-r--"));
        DriverRegistration registration = LOADER.describe(copy, PostgresDriver.CLASS_NAME, "jdbc:postgresql://127.0.0.1:1/w", NOW);
        assertThat(registration.sha256()).isEqualTo(sha(copy));
    }


    private static boolean hasAcl(Path path) {
        return Files.getFileAttributeView(path, AclFileAttributeView.class) != null;
    }

    private static boolean hasPosix(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class) != null;
    }

    /** DRV-030 (changed by SFR-030): a directory that the group or others can write is not looked at, the jar in it loads. */
    @Test
    void drv030_aDirectoryThatOthersCanWriteIsNotRefused() throws IOException {
        assumeTrue(hasPosix(temp), "needs POSIX permissions");
        Path directory = OwnerOnlyFiles.restrict(Files.createDirectory(temp.resolve("drivers-open")));
        Path jar = OwnerOnlyFiles.copy(driverJar(), directory.resolve("postgresql.jar"));
        LOADER.describe(jar, PostgresDriver.CLASS_NAME, "jdbc:postgresql://127.0.0.1:1/dir-ok", NOW);

        for (String mode : List.of("rwxrwx---", "rwx---rwx", "rwx-w----", "rwx----w-")) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(mode));
            DriverRegistration registration = LOADER.describe(jar, PostgresDriver.CLASS_NAME, "jdbc:postgresql://127.0.0.1:1/dir", NOW);
            assertThat(registration.sha256()).as(mode).isEqualTo(sha(jar));
        }
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }

    /**
     * DRV-031 (changed by SFR-030): with ACLs, an entry that lets another account change the jar or its directory is not looked
     * at either: the jar loads whatever the entry holds.
     */
    @Test
    void drv031_aJarOrDirectoryThatAnotherAccountCanWriteIsNotRefusedByAcl() throws IOException {
        assumeTrue(hasAcl(temp) && !hasPosix(temp), "needs ACLs");
        UserPrincipal everyone = temp.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");
        for (boolean onDirectory : new boolean[] {true, false}) {
            Path directory = OwnerOnlyFiles.restrict(Files.createDirectory(temp.resolve("acl-" + onDirectory)));
            Path jar = OwnerOnlyFiles.copy(driverJar(), directory.resolve("postgresql.jar"));
            Path target = onDirectory ? directory : jar;
            AclFileAttributeView view = Files.getFileAttributeView(target, AclFileAttributeView.class);
            List<AclEntry> original = view.getAcl();

            view.setAcl(withEntry(original, everyone, AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES,
                    AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE));
            LOADER.describe(jar, PostgresDriver.CLASS_NAME, "jdbc:postgresql://127.0.0.1:1/acl-read-" + onDirectory, NOW);

            for (AclEntryPermission change : List.of(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
                    AclEntryPermission.DELETE, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER)) {
                view.setAcl(withEntry(original, everyone, AclEntryPermission.READ_DATA, change));
                DriverRegistration registration = LOADER.describe(jar, PostgresDriver.CLASS_NAME,
                        "jdbc:postgresql://127.0.0.1:1/acl", NOW);
                assertThat(registration.sha256()).as("%s on the %s", change, onDirectory ? "directory" : "jar").isEqualTo(sha(jar));
            }
            view.setAcl(original);
            LOADER.describe(jar, PostgresDriver.CLASS_NAME, "jdbc:postgresql://127.0.0.1:1/acl-again-" + onDirectory, NOW);
        }
    }

    private static List<AclEntry> withEntry(List<AclEntry> original, UserPrincipal who, AclEntryPermission... permissions) {
        List<AclEntry> entries = new ArrayList<>(original);
        entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who)
                .setPermissions(EnumSet.copyOf(List.of(permissions))).build());
        return entries;
    }

    // DRV-032 (audit2 N4, S3: a directory whose ACL has no entry at all is refused) was removed by SFR-030: the ACL of the directory
    // of the jar is not looked at any more. The case cannot be kept as "loads": the directory would grant nobody anything.

    /** DRV-033 (changed by SFR-031): the jar is opened once and a link to a jar is followed: it loads and its digest is the jar's. */
    @Test
    void drv033_aSymbolicLinkToAJarIsFollowed() throws IOException {
        Path link = temp.resolve("link.jar");
        try {
            Files.createSymbolicLink(link, driverJar());
        } catch (UnsupportedOperationException | IOException cannot) {
            throw new TestAbortedException("this file system cannot make symbolic links here: " + cannot, cannot);
        }
        DriverRegistration registration = LOADER.describe(link, PostgresDriver.CLASS_NAME, "jdbc:postgresql://127.0.0.1:1/link", NOW);
        assertThat(registration.sha256()).isEqualTo(sha(driverJar()));
    }

    /**
     * SFR-030: the jar and its directory opened to other accounts with write rights (an ALLOW entry of Authenticated Users, or
     * group and other bits) load: the loader does not look at who may write them.
     */
    @Test
    void sfr030_aJarAndADirectoryOpenedToOtherAccountsLoad() throws IOException {
        assumeTrue(BroadAccess.supported(temp), "the file system has neither POSIX permissions nor ACLs");
        Path directory = OwnerOnlyFiles.restrict(Files.createDirectory(temp.resolve("drivers-broad")));
        Path jar = OwnerOnlyFiles.copy(driverJar(), directory.resolve("postgresql.jar"));
        BroadAccess.open(directory);
        BroadAccess.open(jar);
        assertThat(BroadAccess.reachedByOthers(directory)).isTrue();
        assertThat(BroadAccess.reachedByOthers(jar)).isTrue();

        DriverRegistration registration = LOADER.describe(jar, PostgresDriver.CLASS_NAME, "jdbc:postgresql://127.0.0.1:1/broad", NOW);

        assertThat(registration.sha256()).isEqualTo(sha(jar));
        try (RegisteredDriver driver = LOADER.load(registration)) {
            assertThat(driver).isNotNull();
        }
    }

    /** SFR-032: a directory and an empty file at the place of the jar are refused with DRIVER_INVALID (DRV-002 and DRV-003 keep the other forms). */
    @Test
    void sfr032_aDirectoryOrAnEmptyFileAtTheJarPathIsRefused() throws IOException {
        Path directory = Files.createDirectory(temp.resolve("jar-is-a-directory.jar"));
        Path empty = Files.createFile(temp.resolve("empty.jar"));

        assertThat(codeOf(describing(directory, PostgresDriver.CLASS_NAME, "jdbc:postgresql://127.0.0.1:1/d")))
                .isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(codeOf(describing(empty, PostgresDriver.CLASS_NAME, "jdbc:postgresql://127.0.0.1:1/e")))
                .isEqualTo(InfrastructureCode.DRIVER_INVALID);
    }

    @Test
    void drv018_onlyTheNamedClassIsLoadedWhenTwoDriversShareAJar() {
        System.clearProperty(FakeDriverA.MARK);
        System.clearProperty(FakeDriverB.MARK);
        LOADER.describe(fakeJar, FakeDriverA.class.getName(), "jdbc:fake:x", NOW);
        assertThat(System.getProperty(FakeDriverA.MARK)).isEqualTo("yes");
        assertThat(System.getProperty(FakeDriverB.MARK)).isNull();
    }

    @Test
    void drv019_closingReleasesTheJarSoItCanBeDeleted(Databases databases) throws IOException, SQLException {
        Path copy = OwnerOnlyFiles.copy(driverJar(), temp.resolve("release.jar"));
        TestDatabaseSettings settings = databases.database().settings();
        String url = settings.url();
        DriverRegistration registration = LOADER.describe(copy, PostgresDriver.CLASS_NAME, url, NOW);
        try (RegisteredDriver registered = LOADER.load(registration);
                HikariDataSource pool = DriverDataSources.create(registered,
                        new LedgerJdbcProperties(url, settings.user(), settings.ownerPassword().orElse(null), 1,
                                registration), null);
                Connection c = pool.getConnection()) {
            assertThat(c.isValid(2)).isTrue();
        }
        Files.delete(copy);
        assertThat(copy).doesNotExist();
    }

    private static final Path TEMPORARY_DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"));

    /** The files of the temporary-file directory that look like a jar: the private copy of the loader is one of them. */
    private static Set<Path> temporaryJars() throws IOException {
        try (Stream<Path> files = Files.list(TEMPORARY_DIRECTORY)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".jar")).collect(Collectors.toSet());
        }
    }

    @Test
    void drv025_theClassesComeFromAPrivateCopyOfTheBytesThatWereHashed() throws Exception {
        Path registered = OwnerOnlyFiles.copy(fakeJar, temp.resolve("swapped-after-load.jar"));
        DriverRegistration registration = LOADER.describe(registered, FakeDriverA.class.getName(), "jdbc:fake:x",
                NOW);
        try (RegisteredDriver driver = LOADER.load(registration)) {
            Path source = Path.of(driver.driver().getClass().getProtectionDomain().getCodeSource().getLocation()
                    .toURI());
            assertThat(Files.isSameFile(source, registered)).as("the classes are not read from the registered file")
                    .isFalse();
            Files.write(registered, Files.readAllBytes(driverJar()));
            Class<?> notLoadedYet = driver.driver().getClass().getClassLoader().loadClass(FakeDriverB.class.getName());
            assertThat(notLoadedYet.getName()).as("still the old bytes after the file was overwritten")
                    .isEqualTo(FakeDriverB.class.getName());
        }
    }

    @Test
    void drv026_theRegisteredFileCanBeDeletedWhileTheDriverIsOpen() throws Exception {
        Path registered = OwnerOnlyFiles.copy(fakeJar, temp.resolve("deleted-while-open.jar"));
        DriverRegistration registration = LOADER.describe(registered, FakeDriverA.class.getName(), "jdbc:fake:x",
                NOW);
        try (RegisteredDriver driver = LOADER.load(registration)) {
            Files.delete(registered);
            assertThat(registered).doesNotExist();
            assertThat(driver.driver().acceptsURL("jdbc:fake:y")).isTrue();
            assertThat(driver.driver().getClass().getClassLoader().loadClass(FakeDriverB.class.getName()))
                    .isNotNull();
        }
    }

    @Test
    void drv027_thePrivateCopyExistsWhileTheDriverIsOpenAndIsGoneAfterwards() throws Exception {
        Path registered = OwnerOnlyFiles.copy(fakeJar, temp.resolve("lifecycle.jar"));
        Set<Path> before = temporaryJars();

        DriverRegistration registration = LOADER.describe(registered, FakeDriverA.class.getName(), "jdbc:fake:x",
                NOW);
        assertThat(temporaryJars()).as("describe closes its driver and leaves no copy").isEqualTo(before);

        try (RegisteredDriver driver = LOADER.load(registration)) {
            Set<Path> added = temporaryJars().stream().filter(file -> !before.contains(file))
                    .collect(Collectors.toSet());
            assertThat(added).as("one private copy while the driver is open").hasSize(1);
            if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                assertThat(Files.getPosixFilePermissions(added.iterator().next()))
                        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
            }
            assertThat(driver.driver()).isNotNull();
        }
        assertThat(temporaryJars()).as("closing the driver removes the copy").isEqualTo(before);
    }

    @Test
    void drv027_aRefusedLoadLeavesNoCopy() throws IOException {
        Path registered = OwnerOnlyFiles.copy(fakeJar, temp.resolve("refused.jar"));
        Path notAnArchive = OwnerOnlyFiles.write(temp.resolve("refused-text.jar"),
                "this is not an archive".getBytes(StandardCharsets.UTF_8));
        Set<Path> before = temporaryJars();

        assertThat(codeOf(catchThrowableOfType(InfrastructureException.class, () -> LOADER.load(
                new DriverRegistration(registered, FakeDriverA.class.getName(), "0".repeat(64), NOW)))))
                .isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(codeOf(catchThrowableOfType(InfrastructureException.class, () -> LOADER.load(
                new DriverRegistration(notAnArchive, FakeDriverA.class.getName(), sha(notAnArchive), NOW)))))
                .isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(codeOf(catchThrowableOfType(InfrastructureException.class, () -> LOADER.load(
                new DriverRegistration(registered, "org.veridger.database.fake.Missing", sha(registered), NOW)))))
                .isEqualTo(InfrastructureCode.DRIVER_INVALID);

        assertThat(temporaryJars()).isEqualTo(before);
    }

    @Test
    void drv029_aJarPathWithAPlaceholderIsRefusedByTheLoader() throws IOException {
        Path directory = Files.createDirectory(temp.resolve("${x}"));
        Path jar = OwnerOnlyFiles.copy(driverJar(), directory.resolve("postgresql.jar"));

        InfrastructureException described = describing(jar, PostgresDriver.CLASS_NAME, "jdbc:postgresql://127.0.0.1:1/p");
        InfrastructureException loaded = catchThrowableOfType(InfrastructureException.class,
                () -> LOADER.load(new DriverRegistration(jar, PostgresDriver.CLASS_NAME, sha(jar), NOW)));

        assertThat(described).as("describe refuses a path with ${").isNotNull();
        assertThat(codeOf(described)).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(described.args().getFirst()).contains("${");
        assertThat(loaded).as("load refuses a path with ${").isNotNull();
        assertThat(codeOf(loaded)).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(loaded.args().getFirst()).contains("${");
    }

    /** A registration file and what starting an application from it must do. */
    private record RegistrationFile(String name, String yaml, ErrorCode failure) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<RegistrationFile> registrationFiles() {
        String jar = driverJar().toString();
        String sha = sha(driverJar());
        // A YAML single-quoted scalar keeps the path exactly as the file system writes it.
        // the complete registration connects to the test database (the URL of the test settings); the others stop before
        String url = TestDatabaseSettings.fromSystemProperties().url();
        String head = "ledger:\n  jdbc:\n    url: " + url + "\n    driver:\n      jar: '" + jar + "'\n";
        return Stream.of(
                new RegistrationFile("complete", head + "      class-name: org.postgresql.Driver\n      sha256: " + sha
                        + "\n      registered-at: 2026-11-05T10:00:00Z\n", null),
                new RegistrationFile("without digest", head + "      class-name: org.postgresql.Driver\n",
                        InfrastructureCode.DRIVER_INVALID),
                new RegistrationFile("without class", head + "      sha256: " + sha + "\n",
                        InfrastructureCode.DRIVER_INVALID),
                new RegistrationFile("relative jar path", "ledger:\n  jdbc:\n    url: " + url + "\n    driver:\n"
                        + "      jar: relative/driver.jar\n      class-name: org.postgresql.Driver\n      sha256: " + sha + "\n",
                        InfrastructureCode.DRIVER_INVALID),
                new RegistrationFile("nothing registered", "ledger:\n  jdbc:\n    url: " + url + "\n",
                        InfrastructureCode.DRIVER_NOT_CONFIGURED));
    }

    @ParameterizedTest
    @MethodSource("registrationFiles")
    void drv021_theRegistrationFileIsReadThroughConfigImportAndAnIncompleteOneFailsFast(RegistrationFile file)
            throws IOException {
        Path yaml = Files.writeString(temp.resolve("jdbc-driver.yaml"), file.yaml());
        // the account of the test database; the password (when a password file is set) is handed over as a property value and
        // appears in no message of this test; one connection is enough for the check below
        TestDatabaseSettings settings = TestDatabaseSettings.fromSystemProperties();
        List<String> properties = new ArrayList<>(List.of("spring.config.import=file:" + yaml.toUri().getPath(),
                "spring.main.banner-mode=off", "logging.level.root=WARN", "ledger.jdbc.username=" + settings.user(),
                "ledger.jdbc.pool-size=1"));
        settings.ownerPassword().ifPresent(password -> properties.add("ledger.jdbc.password=" + password));
        Throwable failure = catchThrowableOfType(Throwable.class, () -> {
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                    LedgerDataSourceConfiguration.class).web(WebApplicationType.NONE)
                    .properties(properties.toArray(String[]::new)).run()) {
                DataSource dataSource = context.getBean(DataSource.class);
                try (Connection c = dataSource.getConnection()) {
                    assertThat(c.isValid(2)).isTrue();
                }
            }
        });
        assertThat(failure == null ? null : codeOf(NestedExceptionUtils.getMostSpecificCause(failure)))
                .isEqualTo(file.failure());
    }

    @Test
    void drv024_aJarValueThatIsNotAPathStopsTheStartWithTheBindingFailureNamingTheKey() throws IOException {
        Path yaml = Files.writeString(temp.resolve("jdbc-driver-bad-path.yaml"),
                "ledger:\n  jdbc:\n    url: jdbc:postgresql://127.0.0.1:1/cfg\n    driver:\n      jar: \"bad\\0path.jar\"\n"
                        + "      class-name: org.postgresql.Driver\n");
        Throwable failure = catchThrowableOfType(Throwable.class, () -> {
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                    LedgerDataSourceConfiguration.class).web(WebApplicationType.NONE)
                    .properties("spring.config.import=file:" + yaml.toUri().getPath(), "spring.main.banner-mode=off",
                            "logging.level.root=WARN").run()) {
                assertThat(context.isRunning()).isFalse();
            }
        });
        assertThat(failure).isNotNull();
        Throwable cause = failure;
        while (cause != null && !(cause instanceof BindException)) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(BindException.class);
        assertThat(((BindException) cause).getProperty().getName().toString()).isEqualTo("ledger.jdbc.driver.jar");
    }
}
