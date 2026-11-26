// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.admin.support.AdminTest;
import org.veridger.admin.support.Browser;
import org.veridger.admin.support.Browser.Form;
import org.veridger.admin.support.TestPasskeys;
import org.veridger.application.admin.PasskeyService;
import org.veridger.testing.SoftwarePasskey;
import org.veridger.testing.TestOwners;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.MultiValueMap;

/**
 * PSK-SIN-01, PSK-SIN-02, PSK-ENR-04, PSK-ENR-05 (v4.0.0): passkeys protect owner sign-in (with one-time-code
 * enrollment and last-key protection). Once signed in, owner writes execute directly with CSRF protection without
 * redundant per-write passkey re-approval.
 */
@AdminTest
class PasskeyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasskeyService passkeys;

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static MultiValueMap<String, String> project(String id) {
        return Form.of("id", id, "code", id.toUpperCase(java.util.Locale.ROOT), "title", "t");
    }

    private int projects(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM project WHERE id = :id").param("id", id).query(Integer.class).single();
    }

    @Test
    @DisplayName("PSK-SIN-01: sign-in with a passkey opens an owner session that writes directly with CSRF (no per-write passkey re-approval) and audits as passkey:owner")
    void sin01_signInWithAPasskeyAndWriteDirectly() throws Exception {
        Browser browser = new Browser(mvc);
        String csrf = browser.csrfOf("/login");

        MvcResult optionsResult = browser.perform(post(browser, "/webauthn/authenticate/options", csrf, null))
                .andExpect(status().isOk()).andReturn();
        String challenge = JsonPath.read(optionsResult.getResponse().getContentAsString(), "$.challenge");
        SoftwarePasskey.Assertion assertion = TestPasskeys.OWNER.assertion(challenge, TestPasskeys.RP_ID, TestPasskeys.ORIGIN);

        assertThat(JsonPath.<String>read(optionsResult.getResponse().getContentAsString(), "$.userVerification"))
                .isEqualToIgnoringCase("required");
        browser.perform(post(browser, "/login/webauthn", csrf, loginBody(assertion))).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));

        browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.signIn").value("PASSKEY"));
        browser.perform(browser.get("/projects")).andExpect(status().isOk());

        // Signed-in owner writes directly with CSRF (no /approval/intent round-trip)
        String id = "p" + unique();
        browser.perform(browser.form("/projects", project(id), browser.csrfOf("/projects")))
                .andExpect(status().is3xxRedirection());
        assertThat(projects(id)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT actor FROM owner_audit WHERE operation = 'PROJECT_ADDED' AND subject = :id")
                .param("id", id).query(String.class).single()).isEqualTo("passkey:owner");

        // Missing CSRF is still refused 403, and retired /approval/intent is 404
        String blockedId = "b" + unique();
        browser.perform(browser.form("/projects", project(blockedId), null).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        assertThat(projects(blockedId)).isZero();
        browser.perform(browser.json("/approval/intent", browser.csrfOf("/projects"),
                "{\"method\":\"POST\",\"path\":\"/projects\",\"fields\":[]}")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PSK-SIN-02 (changed by LGN-003): a wrong assertion at the sign-in opens no session; ten of them lock nobody (no 429), and the right assertion signs in")
    void sin02_failuresDoNotLock() throws Exception {
        Browser browser = new Browser(mvc);
        String csrf = browser.csrfOf("/login");

        for (int attempt = 0; attempt < 10; attempt++) {
            MvcResult options = browser.perform(post(browser, "/webauthn/authenticate/options", csrf, null)).andReturn();
            String challenge = JsonPath.read(options.getResponse().getContentAsString(), "$.challenge");
            SoftwarePasskey.Assertion wrong = new SoftwarePasskey().assertion(challenge, TestPasskeys.RP_ID,
                    TestPasskeys.ORIGIN);
            assertThat(browser.perform(post(browser, "/login/webauthn", csrf, loginBody(wrong))).andReturn().getResponse()
                    .getStatus()).isGreaterThanOrEqualTo(400).isNotEqualTo(429);
        }
        browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());

        MvcResult options = browser.perform(post(browser, "/webauthn/authenticate/options", csrf, null))
                .andExpect(status().isOk()).andReturn();
        String challenge = JsonPath.read(options.getResponse().getContentAsString(), "$.challenge");
        SoftwarePasskey.Assertion right = TestPasskeys.OWNER.assertion(challenge, TestPasskeys.RP_ID, TestPasskeys.ORIGIN);
        browser.perform(post(browser, "/login/webauthn", csrf, loginBody(right))).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));
        browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.signIn").value("PASSKEY"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(Browser browser, String path,
            String csrf, String json) {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .session(browser.session()).accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
                .header("X-CSRF-TOKEN", csrf).with(r -> {
                    r.setRemoteAddr(browser.address());
                    return r;
                });
        return json == null ? request : request.content(json);
    }

    /** The body the script sends to /login/webauthn (the same shape as the script of Spring Security). */
    private static String loginBody(SoftwarePasskey.Assertion assertion) {
        return "{\"id\":\"" + assertion.credentialId() + "\",\"rawId\":\"" + assertion.credentialId()
                + "\",\"response\":{\"authenticatorData\":\"" + assertion.authenticatorData() + "\",\"clientDataJSON\":\""
                + assertion.clientDataJson() + "\",\"signature\":\"" + assertion.signature()
                + "\"},\"credType\":\"public-key\",\"clientExtensionResults\":{}}";
    }

    @Test
    @DisplayName("PSK-ENR-04: in the session of a one-time code a passkey registers, signs in a browser, writes directly, and the last passkey cannot be closed")
    void enr04_registerSignInAndClose() throws Exception {
        Browser enrolling = new Browser(mvc);
        enrolling.signInWithCode(passkeys.issueCode(TestOwners.OWNER, false).code()).andExpect(status().is3xxRedirection());
        String csrf = enrolling.csrfOf("/passkeys");
        SoftwarePasskey added = new SoftwarePasskey();

        MvcResult options = enrolling.perform(post(enrolling, "/webauthn/register/options", csrf, null))
                .andExpect(status().isOk()).andReturn();
        String challenge = JsonPath.read(options.getResponse().getContentAsString(), "$.challenge");
        SoftwarePasskey.Registration registration = added.registration(challenge, TestPasskeys.RP_ID, TestPasskeys.ORIGIN);
        String body = "{\"publicKey\":{\"credential\":{\"id\":\"" + registration.credentialId() + "\",\"rawId\":\""
                + registration.credentialId() + "\",\"response\":{\"attestationObject\":\"" + registration.attestationObject()
                + "\",\"clientDataJSON\":\"" + registration.clientDataJson() + "\",\"transports\":[]},\"type\":\"public-key\","
                + "\"clientExtensionResults\":{}},\"label\":\"laptop\"}}";
        enrolling.perform(post(enrolling, "/webauthn/register", csrf, body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(passkeys.live()).extracting(live -> live.label()).contains("laptop");
        assertThat(jdbc.sql("SELECT actor FROM owner_audit WHERE operation = 'PASSKEY_REGISTERED' AND detail = 'label=laptop'")
                .query(String.class).list()).containsExactly("code:enroll");

        // Sign in with the newly registered passkey and write a project
        Browser signedIn = new Browser(mvc);
        String loginCsrf = signedIn.csrfOf("/login");
        MvcResult authOptions = signedIn.perform(post(signedIn, "/webauthn/authenticate/options", loginCsrf, null))
                .andExpect(status().isOk()).andReturn();
        String authChallenge = JsonPath.read(authOptions.getResponse().getContentAsString(), "$.challenge");
        signedIn.perform(post(signedIn, "/login/webauthn", loginCsrf,
                loginBody(added.assertion(authChallenge, TestPasskeys.RP_ID, TestPasskeys.ORIGIN))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true));

        String id = "e" + unique();
        signedIn.perform(signedIn.form("/projects", project(id), signedIn.csrfOf("/projects")))
                .andExpect(status().is3xxRedirection());
        assertThat(projects(id)).isEqualTo(1);

        // Close the added passkey; closing the last passkey is still refused 409 PASSKEY_LAST_KEY
        Browser closing = Browser.signedIn(mvc);
        closing.perform(closing.form("/passkeys/revoke", Form.of("credentialId", added.credentialId()),
                closing.csrfOf("/passkeys"))).andExpect(status().is3xxRedirection());
        assertThat(passkeys.live()).extracting(live -> live.credentialId()).doesNotContain(added.credentialId())
                .contains(TestPasskeys.OWNER.credentialId());
        closing.perform(closing.form("/passkeys/revoke", Form.of("credentialId", TestPasskeys.OWNER.credentialId()),
                closing.csrfOf("/passkeys")).accept(MediaType.APPLICATION_JSON)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PASSKEY_LAST_KEY"));
    }

    @Test
    @DisplayName("PSK-ENR-05 (D-0057): a session of the owner (a passkey sign-in) cannot register a passkey: the registration endpoints are 403 for it, nothing is stored, and it still reads and closes passkeys on the page; only the session of a one-time code registers")
    void enr05_anOwnerSessionCannotRegister() throws Exception {
        Browser owner = Browser.signedIn(mvc);
        String csrf = owner.csrfOf("/passkeys");
        int before = passkeys.live().size();
        SoftwarePasskey added = new SoftwarePasskey();
        String challenge = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        SoftwarePasskey.Registration registration = added.registration(challenge, TestPasskeys.RP_ID, TestPasskeys.ORIGIN);
        String body = "{\"publicKey\":{\"credential\":{\"id\":\"" + registration.credentialId() + "\",\"rawId\":\""
                + registration.credentialId() + "\",\"response\":{\"attestationObject\":\"" + registration.attestationObject()
                + "\",\"clientDataJSON\":\"" + registration.clientDataJson() + "\",\"transports\":[]},\"type\":\"public-key\","
                + "\"clientExtensionResults\":{}},\"label\":\"intruder\"}}";

        owner.perform(owner.json("/webauthn/register/options", csrf, null)).andExpect(status().isForbidden());
        owner.perform(owner.json("/webauthn/register", csrf, body)).andExpect(status().isForbidden());

        assertThat(passkeys.live()).hasSize(before).extracting(live -> live.label()).doesNotContain("intruder");
        owner.perform(owner.get("/passkeys")).andExpect(status().isOk());
    }
}
