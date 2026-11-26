// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.support;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * One browser: a session of its own and a client address of its own. It reads the CSRF token the way a browser does,
 * from the hidden field that Thymeleaf puts into every form, and posts forms with that CSRF token.
 */
public final class Browser {

    private static final Pattern CSRF_FIELD = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
    private static final AtomicInteger ADDRESSES = new AtomicInteger();

    private final MockMvc mvc;
    private final String address;
    private final MockHttpSession session = new MockHttpSession();

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
     * A browser that is signed in as the owner (a session of the owner, as a sign-in leaves it: the sign-ins
     * themselves are tested through their endpoints).
     */
    public static Browser signedIn(MockMvc mvc) {
        Browser browser = new Browser(mvc);
        browser.session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated("owner", null,
                        List.of(new SimpleGrantedAuthority("ROLE_OWNER")))));
        return browser;
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

    /** A form post with the CSRF token of the given page (when non-null). */
    public MockHttpServletRequestBuilder form(String uri, MultiValueMap<String, String> fields, String csrf) {
        MockHttpServletRequestBuilder request = withClient(MockMvcRequestBuilders.post(uri))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .params(fields);
        return csrf == null ? request : request.param("_csrf", csrf);
    }

    /** A JSON post as the scripts of the passkey page send it (the CSRF token in the header); the body may be absent. */
    public MockHttpServletRequestBuilder json(String uri, String csrf, String body) {
        MockHttpServletRequestBuilder request = withClient(MockMvcRequestBuilders.post(uri))
                .accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON).header("X-CSRF-TOKEN", csrf);
        return body == null ? request : request.content(body);
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

    /** The sign-in with the one-time code of the owner's shell: the login page first, then the code form. */
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
