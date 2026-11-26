// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.admin.support.AdminTestInfrastructure;
import org.veridger.admin.support.AdminTestSettings;
import org.veridger.admin.support.Browser;
import org.veridger.admin.support.Browser.Form;
import org.veridger.admin.support.TestDriverJar;
import org.veridger.testing.OwnerOnlyFiles;
import org.veridger.testing.database.TestSchemaInitializer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.veridger.testing.database.TestDatabaseScanFilter;
import org.veridger.testing.database.TestDriverRegistration;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADM-072 (audit2 N3): a driver registration made on the driver page of a console that has its database (the test
 * database of the admin tests) is recorded in the owner audit trail with the signed-in owner, in the transaction of the file
 * replacement. In setup mode there is no database and the registration is logged instead (SetupModeTest).
 */
@ContextConfiguration(initializers = {TestSchemaInitializer.class, TestDriverRegistration.class})
@SpringBootTest(classes = LedgerAdminApplication.class,
    properties = {AdminTestSettings.TOKEN_PROPERTY,
    AdminTestSettings.AGENT_TOKEN_PROPERTY, AdminTestSettings.NO_GLOBAL_CAP, AdminTestSettings.INSECURE_COOKIE,
    AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG, AdminTestSettings.NO_JMX,
    TestDriverJar.URL_PROPERTY})
@AutoConfigureMockMvc
@TypeExcludeFilters(TestDatabaseScanFilter.class)
@Import(AdminTestInfrastructure.class)
class DriverAuditTest {

    private static final Path DIRECTORY = temporaryDirectory();
    private static final Path DRIVERS = driverDirectory();

    @DynamicPropertySource
    static void registrationFiles(DynamicPropertyRegistry registry) {
        registry.add("ledger.admin.driver-file", () -> DIRECTORY.resolve("jdbc-driver.yaml").toString());
        registry.add("ledger.admin.driver-directory", () -> DRIVERS.toString());
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("admin-driver-audit").toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path driverDirectory() {
        try {
            return OwnerOnlyFiles.restrict(Files.createDirectory(DIRECTORY.resolve("drivers")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path driverJar() {
        try {
            return OwnerOnlyFiles.restrict(Files.copy(TestDriverJar.jar(), DRIVERS.resolve("postgresql.jar"),
                    StandardCopyOption.REPLACE_EXISTING)).toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("cannot copy the PostgreSQL driver jar", e);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("ADM-072: registering a driver is an owner_audit row DRIVER_REGISTERED with token:owner, the class as subject, the jar and its digest in the detail; a refused registration leaves no row")
    void adm072_aRegistrationIsAudited() throws Exception {
        Browser owner = Browser.signedIn(mvc);
        Path jar = driverJar();

        owner.perform(owner.form("/driver", Form.of("jar", DRIVERS.resolve("missing.jar").toString(), "className",
                TestDriverJar.DRIVER_CLASS), owner.csrfOf("/driver"))).andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM owner_audit WHERE operation = 'DRIVER_REGISTERED'").query(Long.class)
                .single()).isZero();

        owner.perform(owner.form("/driver", Form.of("jar", jar.toString(), "className", TestDriverJar.DRIVER_CLASS),
                owner.csrfOf("/driver"))).andExpect(status().is3xxRedirection());

        assertThat(jdbc.sql("SELECT actor || ' ' || subject FROM owner_audit WHERE operation = 'DRIVER_REGISTERED'")
                .query(String.class).list()).containsExactly("token:owner " + TestDriverJar.DRIVER_CLASS);
        assertThat(jdbc.sql("SELECT detail FROM owner_audit WHERE operation = 'DRIVER_REGISTERED'").query(String.class)
                .single()).contains(jar.toString()).containsPattern("sha256=[0-9a-f]{64}");
    }
}
