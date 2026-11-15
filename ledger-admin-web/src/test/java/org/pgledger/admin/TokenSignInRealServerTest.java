package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTestInfrastructure;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.RealHttp;
import org.pgledger.admin.support.RealHttp.Reply;
import org.pgledger.testing.database.TestDatabaseScanFilter;
import org.pgledger.testing.database.TestDriverRegistration;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

/**
 * PSK-TOK-06: the token form sign-in ({@code ledger.admin.sign-in=token}) in a real servlet container, because the session id
 * after the sign-in and the attributes of the cookie are what Tomcat and Spring Security do together (fixation protection).
 */
@ContextConfiguration(initializers = {TestSchemaInitializer.class, TestDriverRegistration.class})
@SpringBootTest(classes = LedgerAdminApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {AdminTestSettings.TOKEN_PROPERTY, AdminTestSettings.AGENT_TOKEN_PROPERTY, AdminTestSettings.INSECURE_COOKIE,
            AdminTestSettings.NO_GLOBAL_CAP, AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG,
            AdminTestSettings.NO_JMX, "ledger.admin.sign-in=token"})
@TypeExcludeFilters(TestDatabaseScanFilter.class)
@Import(AdminTestInfrastructure.class)
class TokenSignInRealServerTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("PSK-TOK-06: the token form gives a new session id (the id from before is dead: 401), the cookie is HttpOnly and SameSite=Strict")
    void tok06_fixationAndCookie() {
        RealHttp http = new RealHttp(port);
        Reply page = http.get("/login");
        String cookie = page.sessionCookieLine().orElseThrow();
        assertThat(cookie).startsWith("LEDGER_ADMIN_SESSION=").contains("HttpOnly").contains("SameSite=Strict");
        assertThat(page.body()).contains("type=\"password\"");

        String before = page.sessionId().orElseThrow();
        Reply login = http.postForm("/login", "username=owner&token=" + AdminTestSettings.TOKEN + "&_csrf=" + page.csrf(), "Cookie",
                RealHttp.cookie(before));
        String after = login.sessionId().orElseThrow();

        assertThat(login.status()).isEqualTo(302);
        assertThat(after).isNotEqualTo(before);
        assertThat(login.sessionCookieLine().orElseThrow()).contains("HttpOnly").contains("SameSite=Strict");
        assertThat(http.get("/api/session", "Cookie", RealHttp.cookie(after), "Accept", "application/json").status())
                .isEqualTo(200);
        assertThat(http.get("/api/session", "Cookie", RealHttp.cookie(before), "Accept", "application/json").status())
                .as("the session id from before the sign-in is not a session any more").isEqualTo(401);
    }
}
