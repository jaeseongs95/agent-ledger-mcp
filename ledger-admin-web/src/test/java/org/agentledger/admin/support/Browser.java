// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.support;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.agentledger.admin.passkey.ApprovalService;
import org.agentledger.testing.SoftwarePasskey;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * One browser: a session of its own and a client address of its own (nothing limits per address any more, but the address is
 * what the application sees of the client, so tests keep one each). It reads the CSRF token the way a browser does, from the hidden field that
 * Thymeleaf puts into every form, and posts forms the way a browser does.
 *
 * <p>A browser that holds a passkey ({@link #withPasskey}) approves the writes it posts as the page does: it tells the server
 * what it is about to post, signs the challenge that comes back with the software authenticator and sends the assertion with
 * the form ({@link #form}). A browser without a passkey posts the form as it is, which the admin refuses for a write
 * ({@link #formWithoutApproval}).
 */
public final class Browser {

    private static final Pattern CSRF_FIELD = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
    private static final AtomicInteger ADDRESSES = new AtomicInteger();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;
    private final String address;
    private final MockHttpSession session = new MockHttpSession();
    private SoftwarePasskey passkey;

    public Browser(MockMvc mvc) {
        this.mvc = mvc;
        int number = ADDRESSES.incrementAndGet();
        this.address = "10." + (number / 65_000) + "." + (number % 65_000 / 250) + "." + (number % 250 + 1);
    }

    /** A browser at a client address that the test chooses (an IPv6 address, an IPv4-mapped one ...). */
    public Browser(MockMvc mvc, String address) {
        this.mvc = mvc;
        this.address = address;
    }

    /**
     * A browser that is signed in as the owner (a session of the owner, as a sign-in leaves it: the sign-ins themselves are
     * tested through their endpoints) and holds the passkey of the owner for the approval of its writes.
     */
    public static Browser signedIn(MockMvc mvc) throws Exception {
        Browser browser = new Browser(mvc).withPasskey(TestPasskeys.OWNER);
        browser.session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated("owner", null,
                        List.of(new SimpleGrantedAuthority("ROLE_OWNER")))));
        return browser;
    }

    /** This browser approves its writes with the passkey. */
    public Browser withPasskey(SoftwarePasskey held) {
        this.passkey = held;
        return this;
    }

    public MockHttpSession session() {
        return session;
    }

    public String address() {
        return address;
    }

    public MockHttpServletRequestBuilder get(String uri, Object... variables) {
        return withClient(MockMvcRequestBuilders.get(uri, variables));
    }

    /**
     * A form post with the CSRF token of the given page (a page of the application that has a form). A browser that holds a
     * passkey approves a write first, as the page does.
     */
    public MockHttpServletRequestBuilder form(String uri, MultiValueMap<String, String> fields, String csrf) throws Exception {
        MockHttpServletRequestBuilder request = formWithoutApproval(uri, fields, csrf);
        if (passkey != null && csrf != null && !uri.startsWith("/login") && !"/logout".equals(uri)) {
            // a page that cannot get the approval (its CSRF token is refused, say) posts the form as it is
            String answer = intent(uri, fields, csrf).getResponse().getContentAsString();
            if (answer.startsWith("{\"challenge\"")) {
                ApprovalService.Options options = JSON.readValue(answer, ApprovalService.Options.class);
                request.param("approval", sign(passkey.assertion(options.challenge(), options.rpId(), TestPasskeys.ORIGIN)));
            }
        }
        return request;
    }

    private MvcResult intent(String uri, MultiValueMap<String, String> fields, String csrf) throws Exception {
        List<Map<String, String>> list = new ArrayList<>();
        fields.forEach((name, values) -> values.forEach(value -> list.add(Map.of("name", name, "value", value))));
        String body = JSON.writeValueAsString(Map.of("method", "POST", "path", uri, "fields", list));
        return mvc.perform(withClient(MockMvcRequestBuilders.post("/approval/intent"))
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .header("X-CSRF-TOKEN", csrf).content(body)).andReturn();
    }

    /** A form post as {@link #form} makes it, but never approved. */
    public MockHttpServletRequestBuilder formWithoutApproval(String uri, MultiValueMap<String, String> fields, String csrf) {
        MockHttpServletRequestBuilder request = withClient(MockMvcRequestBuilders.post(uri)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .params(fields);
        return csrf == null ? request : request.param("_csrf", csrf);
    }

    /** A JSON post as the scripts of the passkey page send it (the CSRF token in the header); the body may be absent. */
    public MockHttpServletRequestBuilder json(String uri, String csrf, String body) {
        MockHttpServletRequestBuilder request = withClient(MockMvcRequestBuilders.post(uri))
                .accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON).header("X-CSRF-TOKEN", csrf);
        return body == null ? request : request.content(body);
    }

    /** The approval of a write: what the page does before it posts the form. */
    public String approval(String uri, MultiValueMap<String, String> fields, String csrf) throws Exception {
        ApprovalService.Options options = approvalOptions(uri, fields, csrf);
        return sign(passkey.assertion(options.challenge(), options.rpId(), TestPasskeys.ORIGIN));
    }

    /** The request of the page for the approval of a write (the server remembers the change and the session). */
    public ApprovalService.Options approvalOptions(String uri, MultiValueMap<String, String> fields, String csrf)
            throws Exception {
        return JSON.readValue(intent(uri, fields, csrf).getResponse().getContentAsString(), ApprovalService.Options.class);
    }

    /** The value of the field {@code approval}: an assertion as the page sends it. */
    public static String sign(SoftwarePasskey.Assertion assertion) {
        return JSON.writeValueAsString(new ApprovalService.Assertion(assertion.credentialId(),
                assertion.authenticatorData(), assertion.clientDataJson(), assertion.signature()));
    }

    public ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    /** The CSRF token of the form of a page: what a browser would send back. */
    public String csrfOf(String pageUri) throws Exception {
        String html = perform(get(pageUri)).andReturn().getResponse().getContentAsString();
        Matcher matcher = CSRF_FIELD.matcher(html);
        if (!matcher.find()) {
            throw new AssertionError("no CSRF field in the page " + pageUri);
        }
        return matcher.group(1);
    }

    /** The sign-in with the one-time code of the owner's shell: the login page first (it opens the session and brings the token), then the code form. */
    public ResultActions signInWithCode(String code) throws Exception {
        String csrf = csrfOf("/login");
        return perform(form("/login/ott", Form.of("token", code), csrf));
    }

    private MockHttpServletRequestBuilder withClient(MockHttpServletRequestBuilder request) {
        return request.accept(MediaType.TEXT_HTML).session(session).with(r -> {
            r.setRemoteAddr(address);
            return r;
        });
    }

    /** Form fields from alternating names and values; a name may repeat (check boxes). */
    public static final class Form {

        private Form() {
        }

        public static MultiValueMap<String, String> of(String... namesAndValues) {
            MultiValueMap<String, String> fields = new LinkedMultiValueMap<>();
            for (int i = 0; i < namesAndValues.length; i += 2) {
                fields.add(namesAndValues[i], namesAndValues[i + 1]);
            }
            return fields;
        }
    }
}
