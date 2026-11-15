package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.passkey.ApprovalIntents;
import org.pgledger.admin.passkey.ApprovalService;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.Browser.Form;
import org.pgledger.admin.support.MutableClock;
import org.pgledger.admin.support.TestPasskeys;
import org.pgledger.application.admin.PasskeyService;
import org.pgledger.testing.SoftwarePasskey;
import org.pgledger.testing.TestOwners;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.MultiValueMap;

/**
 * PSK-APR-01 .. PSK-APR-09, PSK-SIN-01, PSK-SIN-02, PSK-ENR-04 (TEST_CASES section 39): the approval of every owner write by a
 * passkey (the challenge is derived from the change and the session, used once, short-lived; user verification and the relying
 * party are checked by Spring Security's WebAuthn support), the passkey sign-in through its endpoints with the lock after failed
 * attempts, and the registration of a passkey in the session of the one-time code. The software authenticator writes what a
 * browser passkey sends; nothing is mocked.
 */
@AdminTest
class PasskeyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MutableClock clock;

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

    private static String problemCode(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
    }

    @Test
    @DisplayName("PSK-APR-01: an owner write without the approval of a passkey is 403 PASSKEY_APPROVAL_REQUIRED and stores nothing; a read needs none")
    void apr01_writeWithoutApprovalIsRefused() throws Exception {
        Browser owner = Browser.signedIn(mvc);
        String id = "n" + unique();

        owner.perform(owner.formWithoutApproval("/projects", project(id), owner.csrfOf("/projects"))
                .accept(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSKEY_APPROVAL_REQUIRED"));

        assertThat(projects(id)).isZero();
        owner.perform(owner.get("/projects")).andExpect(status().isOk());
        owner.perform(owner.get("/grants")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PSK-APR-02: every owner write of the console needs it (grant, revoke, key close, code, project, driver, passkey close), the preview that stores nothing does not")
    void apr02_everyWriteNeedsIt() throws Exception {
        Browser owner = Browser.signedIn(mvc);
        String csrf = owner.csrfOf("/grants");

        for (String path : List.of("/grants", "/grants/revoke", "/grants/keys/revoke", "/codes", "/projects", "/driver",
                "/passkeys/revoke")) {
            owner.perform(owner.formWithoutApproval(path, Form.of("a", "b"), csrf).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSKEY_APPROVAL_REQUIRED"));
        }
        // the preview stores nothing: it reaches its handler (a refusal of its own input, not of the approval)
        MvcResult preview = owner.perform(owner.formWithoutApproval("/grants/preview", Form.of("a", "b"), csrf)
                .accept(MediaType.APPLICATION_JSON)).andReturn();
        assertThat(problemCode(preview)).isNotEqualTo("PASSKEY_APPROVAL_REQUIRED");
    }

    @Test
    @DisplayName("PSK-APR-03: an approved write succeeds and the owner audit row names the signed-in owner")
    void apr03_approvedWriteSucceeds() throws Exception {
        Browser owner = Browser.signedIn(mvc);
        String id = "a" + unique();

        owner.perform(owner.form("/projects", project(id), owner.csrfOf("/projects"))).andExpect(status().is3xxRedirection());

        assertThat(projects(id)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT actor FROM owner_audit WHERE operation = 'PROJECT_ADDED' AND subject = :id")
                .param("id", id).query(String.class).single()).isEqualTo("token:owner");
    }

    @Test
    @DisplayName("PSK-APR-04: an approval for another change (a field that differs) or another path is PASSKEY_APPROVAL_INVALID and stores nothing")
    void apr04_approvalForAnotherChangeIsRefused() throws Exception {
        Browser owner = Browser.signedIn(mvc);
        String csrf = owner.csrfOf("/projects");
        String id = "c" + unique();
        String approval = owner.approval("/projects", project(id), csrf);

        MultiValueMap<String, String> changed = Form.of("id", id, "code", id.toUpperCase(java.util.Locale.ROOT), "title", "other");
        owner.perform(owner.formWithoutApproval("/projects", changed, csrf).param("approval", approval)
                .accept(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSKEY_APPROVAL_INVALID"));
        String second = "d" + unique();
        String forAProject = owner.approval("/projects", project(second), csrf);
        owner.perform(owner.formWithoutApproval("/codes", Form.of("group", "DEFECT_CATEGORY", "code", "X"), csrf)
                .param("approval", forAProject).accept(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSKEY_APPROVAL_INVALID"));

        assertThat(projects(id)).isZero();
    }

    @Test
    @DisplayName("PSK-APR-05: an approval is used once: the second post with it is PASSKEY_APPROVAL_INVALID")
    void apr05_approvalIsUsedOnce() throws Exception {
        Browser owner = Browser.signedIn(mvc);
        String csrf = owner.csrfOf("/projects");
        String id = "o" + unique();
        String approval = owner.approval("/projects", project(id), csrf);

        owner.perform(owner.formWithoutApproval("/projects", project(id), csrf).param("approval", approval))
                .andExpect(status().is3xxRedirection());
        owner.perform(owner.formWithoutApproval("/projects", project(id), csrf).param("approval", approval)
                .accept(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSKEY_APPROVAL_INVALID"));

        assertThat(projects(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("PSK-APR-06: an approval of another session, and one that has waited longer than its time to live, is PASSKEY_APPROVAL_INVALID")
    void apr06_otherSessionAndExpiry() throws Exception {
        Browser owner = Browser.signedIn(mvc);
        Browser other = Browser.signedIn(mvc);
        String id = "s" + unique();
        MultiValueMap<String, String> fields = project(id);
        String ownersApproval = owner.approval("/projects", fields, owner.csrfOf("/projects"));
        String expired = owner.approval("/projects", fields, owner.csrfOf("/projects"));

        other.perform(other.formWithoutApproval("/projects", fields, other.csrfOf("/projects")).param("approval", ownersApproval)
                .accept(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSKEY_APPROVAL_INVALID"));
        clock.advance(ApprovalIntents.TTL.plusSeconds(1));
        owner.perform(owner.formWithoutApproval("/projects", fields, owner.csrfOf("/projects")).param("approval", expired)
                .accept(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSKEY_APPROVAL_INVALID"));

        assertThat(projects(id)).isZero();
    }

    @Test
    @DisplayName("PSK-APR-07: an assertion for another host name or origin is PASSKEY_HOST_MISMATCH, one without user verification or from a passkey that is not registered is PASSKEY_ASSERTION_INVALID, and a made-up challenge or garbage is PASSKEY_APPROVAL_INVALID")
    void apr07_badAssertions() throws Exception {
        Browser owner = Browser.signedIn(mvc);
        String csrf = owner.csrfOf("/projects");
        String id = "b" + unique();
        MultiValueMap<String, String> fields = project(id);
        SoftwarePasskey key = TestPasskeys.OWNER;

        String wrongOrigin = approvalWith(owner, fields, csrf, options -> key.assertion(options.challenge(),
                TestPasskeys.RP_ID, "https://evil.example"));
        String wrongRp = approvalWith(owner, fields, csrf, options -> key.assertion(options.challenge(), "other.example",
                TestPasskeys.ORIGIN));
        String noVerification = approvalWith(owner, fields, csrf, options -> key.assertion(
                SoftwarePasskey.clientData("webauthn.get", options.challenge(), TestPasskeys.ORIGIN),
                SoftwarePasskey.authenticatorData(TestPasskeys.RP_ID, 0x01)));
        String unregistered = approvalWith(owner, fields, csrf, options -> new SoftwarePasskey().assertion(
                options.challenge(), TestPasskeys.RP_ID, TestPasskeys.ORIGIN));

        assertThat(post(owner, fields, csrf, wrongOrigin)).isEqualTo("PASSKEY_HOST_MISMATCH");
        assertThat(post(owner, fields, csrf, wrongRp)).isEqualTo("PASSKEY_HOST_MISMATCH");
        assertThat(post(owner, fields, csrf, noVerification)).isEqualTo("PASSKEY_ASSERTION_INVALID");
        assertThat(post(owner, fields, csrf, unregistered)).isEqualTo("PASSKEY_ASSERTION_INVALID");
        assertThat(post(owner, fields, csrf, "not json")).isEqualTo("PASSKEY_APPROVAL_INVALID");
        String madeUp = Browser.sign(key.assertion("bWFkZS11cC1jaGFsbGVuZ2U", TestPasskeys.RP_ID, TestPasskeys.ORIGIN));
        assertThat(post(owner, fields, csrf, madeUp)).isEqualTo("PASSKEY_APPROVAL_INVALID");
        assertThat(projects(id)).isZero();
    }

    private String approvalWith(Browser owner, MultiValueMap<String, String> fields, String csrf,
            java.util.function.Function<ApprovalService.Options, SoftwarePasskey.Assertion> assertion) throws Exception {
        return Browser.sign(assertion.apply(owner.approvalOptions("/projects", fields, csrf)));
    }

    private static String post(Browser owner, MultiValueMap<String, String> fields, String csrf, String approval)
            throws Exception {
        return problemCode(owner.perform(owner.formWithoutApproval("/projects", fields, csrf).param("approval", approval)
                .accept(MediaType.APPLICATION_JSON)).andReturn());
    }

    @Test
    @DisplayName("PSK-APR-08: the request for an approval refuses a read method, an exempt path and a path that is not a write; it needs the signed-in owner")
    void apr08_intentValidation() throws Exception {
        Browser owner = Browser.signedIn(mvc);
        String csrf = owner.csrfOf("/projects");

        for (String body : List.of("{\"method\":\"GET\",\"path\":\"/projects\",\"fields\":[]}",
                "{\"method\":\"POST\",\"path\":\"/grants/preview\",\"fields\":[]}",
                "{\"method\":\"POST\",\"path\":\"projects\",\"fields\":[]}")) {
            owner.perform(post(owner, "/approval/intent", csrf, body)).andExpect(status().isBadRequest());
        }
        Browser anonymous = new Browser(mvc);
        anonymous.perform(post(anonymous, "/approval/intent", anonymous.csrfOf("/login"),
                "{\"method\":\"POST\",\"path\":\"/projects\",\"fields\":[]}")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PSK-SIN-01: sign-in with a passkey through the framework's endpoints (options, then the assertion): a session of the owner; the audit actor is passkey:owner")
    void sin01_signInWithAPasskey() throws Exception {
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
    @DisplayName("PSK-ENR-04: in the session of a one-time code a passkey registers through the framework's endpoints (the owner audit says who and which key), the new passkey approves a write, and the last passkey cannot be closed but another can, with an approval")
    void enr04_registerApproveAndClose() throws Exception {
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
        Browser owner = Browser.signedIn(mvc).withPasskey(added);
        String id = "e" + unique();
        owner.perform(owner.form("/projects", project(id), owner.csrfOf("/projects"))).andExpect(status().is3xxRedirection());
        assertThat(projects(id)).isEqualTo(1);

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
    @DisplayName("PSK-APR-09: the challenge is the SHA-256 of an intent that names the change and the session: two requests for the same change give two challenges")
    void apr09_challengesDiffer() throws Exception {
        Browser owner = Browser.signedIn(mvc);
        String csrf = owner.csrfOf("/projects");
        MultiValueMap<String, String> fields = project("z" + unique());

        ApprovalService.Options first = owner.approvalOptions("/projects", fields, csrf);
        ApprovalService.Options second = owner.approvalOptions("/projects", fields, csrf);

        assertThat(first.challenge()).isNotEqualTo(second.challenge());
        assertThat(Base64.getUrlDecoder().decode(first.challenge())).hasSize(32);
        assertThat(first.rpId()).isEqualTo(TestPasskeys.RP_ID);
        assertThat(first.userVerification()).isEqualTo("required");
        assertThat(first.allowCredentials()).contains(TestPasskeys.OWNER.credentialId());
        assertThat(Duration.ofMillis(first.timeout())).isEqualTo(Duration.ofMinutes(2));
    }
}
