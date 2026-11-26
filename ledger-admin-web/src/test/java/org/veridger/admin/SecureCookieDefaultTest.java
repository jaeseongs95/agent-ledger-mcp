// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.admin.support.AdminTestInfrastructure;
import org.veridger.admin.support.AdminTestSettings;
import org.veridger.testing.database.TestSchemaInitializer;
import org.springframework.test.context.ContextConfiguration;
import org.veridger.admin.support.RealHttp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.veridger.testing.database.TestDatabaseScanFilter;
import org.veridger.testing.database.TestDriverRegistration;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;

/**
 * ADM-056 (security audit): with the default settings the session cookie is {@code Secure}. This context does not carry
 * the property that the other real-server tests use to talk plain HTTP.
 */
@ContextConfiguration(initializers = {TestSchemaInitializer.class, TestDriverRegistration.class})
@SpringBootTest(classes = LedgerAdminApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {AdminTestSettings.TOKEN_PROPERTY, AdminTestSettings.AGENT_TOKEN_PROPERTY,
            AdminTestSettings.NO_GLOBAL_CAP, AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG,
            AdminTestSettings.NO_JMX})
@TypeExcludeFilters(TestDatabaseScanFilter.class)
@Import(AdminTestInfrastructure.class)
class SecureCookieDefaultTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("ADM-056: by default the session cookie is Secure, HttpOnly and SameSite=Strict, also on a plain HTTP request")
    void adm056_theCookieIsSecureByDefault() {
        String cookie = new RealHttp(port).get("/login").sessionCookieLine().orElseThrow();

        assertThat(cookie).contains("Secure").contains("HttpOnly").contains("SameSite=Strict");
    }
}
