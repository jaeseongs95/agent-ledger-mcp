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
 * ADM-026 (trusted proxy): behind a reverse proxy that Tomcat trusts (here the loopback address, one of the internal
 * proxies of Tomcat by default), {@code X-Forwarded-Proto: https} makes the session cookie {@code Secure}.
 */
@ContextConfiguration(initializers = {TestSchemaInitializer.class, TestDriverRegistration.class})
@SpringBootTest(classes = LedgerAdminApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {AdminTestSettings.TOKEN_PROPERTY, AdminTestSettings.AGENT_TOKEN_PROPERTY, AdminTestSettings.INSECURE_COOKIE,
            AdminTestSettings.NO_GLOBAL_CAP, AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG,
            AdminTestSettings.NO_JMX})
@TypeExcludeFilters(TestDatabaseScanFilter.class)
@Import(AdminTestInfrastructure.class)
class ForwardedProtoTrustedTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("ADM-026: X-Forwarded-Proto: https from a trusted proxy makes the session cookie Secure; without it, no")
    void adm026_trustedProxyMakesTheCookieSecure() {
        RealHttp http = new RealHttp(port);

        String forwarded = http.get("/login", "X-Forwarded-Proto", "https", "X-Forwarded-For", "203.0.113.9")
                .sessionCookieLine().orElseThrow();
        String plain = http.get("/login").sessionCookieLine().orElseThrow();

        assertThat(forwarded).contains("Secure").contains("SameSite=Strict").contains("HttpOnly");
        assertThat(plain).doesNotContain("Secure");
    }
}
