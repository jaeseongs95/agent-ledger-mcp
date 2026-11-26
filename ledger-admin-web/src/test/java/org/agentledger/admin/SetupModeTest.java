// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.admin.support.AdminTestSettings;
import org.agentledger.admin.support.Browser;
import org.agentledger.admin.support.TestDriverJar;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.IdGenerator;

/**
 * ADM-041, ADM-042, ADM-077 and PSK-SET-01: the application without a registered JDBC driver starts in setup mode (no DataSource,
 * no ledger page, and so no passkey to sign in with and no passkey beans). Only the login page and the health check are open, and
 * the login page says to register the driver with the command line tool ({@code driver-register}); the driver page of the admin
 * web is a console page (its write needs the approval of a passkey, which needs the database) and is not reachable here.
 */
@SpringBootTest(classes = LedgerAdminApplication.class, properties = {AdminTestSettings.TOKEN_PROPERTY,
    AdminTestSettings.AGENT_TOKEN_PROPERTY, AdminTestSettings.NO_GLOBAL_CAP,
    AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG, AdminTestSettings.NO_JMX,
    TestDriverJar.URL_PROPERTY})
@AutoConfigureMockMvc
class SetupModeTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("ADM-077: the setup mode context holds exactly one IdGenerator (the problem responses need it)")
    void adm077_setupModeHasTheOneIdGenerator() {
        assertThat(context.getBeansOfType(IdGenerator.class)).hasSize(1);
    }

    @Test
    @DisplayName("ADM-042/PSK-SET-01: in setup mode the login page (which says how to register a driver) and the health check are open; every other route goes to the login, also the driver page; no sign-in is offered")
    void adm042_setupModeOpensOnlyTheLoginPage() throws Exception {
        Browser anonymous = new Browser(mvc);

        String login = anonymous.perform(anonymous.get("/login")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(login).contains("driver-register").doesNotContain("passkey-signin").doesNotContain("/login/ott")
                .doesNotContain("oauth2/authorization");
        anonymous.perform(anonymous.get("/healthz").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());
        for (String path : List.of("/", "/issues", "/specs", "/grants", "/projects", "/history", "/driver", "/passkeys")) {
            anonymous.perform(anonymous.get(path)).andExpect(status().is3xxRedirection());
        }
        anonymous.perform(anonymous.get("/api/overview").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PSK-SET-01: in setup mode there are no passkey endpoints and no code sign-in: they are refused, not served")
    void psk_noPasskeyEndpointsInSetupMode() throws Exception {
        Browser anonymous = new Browser(mvc);

        for (String path : List.of("/webauthn/authenticate/options", "/login/webauthn", "/login/ott", "/webauthn/register/options")) {
            int status = anonymous.perform(anonymous.form(path, Browser.Form.of(), null)
                    .accept(MediaType.APPLICATION_JSON)).andReturn().getResponse().getStatus();
            assertThat(status).as(path).isBetween(400, 403);
        }
    }
}
