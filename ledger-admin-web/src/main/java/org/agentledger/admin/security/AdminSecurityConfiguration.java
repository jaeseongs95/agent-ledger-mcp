// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.security;

import jakarta.servlet.DispatcherType;
import java.util.List;
import java.util.Set;
import org.agentledger.admin.app.AdminMode;
import org.agentledger.admin.app.LedgerAdminProperties;
import org.agentledger.admin.app.SignInMethod;
import org.agentledger.admin.passkey.EnrollmentTokenService;
import org.agentledger.admin.passkey.PasskeyEndpointGate;
import org.agentledger.admin.passkey.OwnerAccounts;
import org.agentledger.admin.passkey.PasskeyLoginGuard;
import org.agentledger.admin.passkey.PasskeyRelyingParty;
import org.agentledger.domain.error.AdminCode;
import org.agentledger.domain.error.AdminException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.security.autoconfigure.web.servlet.PathRequest;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The security of the admin web, all of it Spring Security in one filter chain: the Host allow list (the firewall refuses
 * any other Host with 403, before anything else runs: the guard against DNS rebinding), the sign-in the owner chose
 * ({@code ledger.admin.sign-in}: the admin token with {@code formLogin} of the application's own page, OpenID Connect
 * {@code oauth2Login} for the one e-mail address of the owner, or a passkey with {@code webAuthn}), the one-time code of the owner's shell ({@code oneTimeTokenLogin}) that reaches the registration of a
 * passkey, the admin token as the bearer of the read-only JSON endpoints, the CSRF token with the session cookie of Spring
 * Boot ({@code SameSite=Strict}), a new session id after the sign-in (fixation protection), the delay after many failed attempts and
 * the headers (CSP and the rest). The approval of every owner write by a passkey is checked by the interceptor
 * {@code ApprovalInterceptor}, whichever sign-in was used.
 *
 * <p>In setup mode (no JDBC driver, so no database to keep a passkey in) nothing but the login page and the health check is
 * open: the page says to register the driver with the command line tool.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class AdminSecurityConfiguration {

    /** How long the sign-in with a one-time code lasts: the time to register a passkey. */
    private static final int ENROLLMENT_SECONDS = 900;

    /**
     * No inline script or style: the pages load their script and style from the application itself. Nobody may frame the
     * pages, and forms post only back to the application.
     */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; "
            + "form-action 'self'; base-uri 'none'; object-src 'none'";

    /**
     * A {@code @Bean} method because the firewall is a Spring Security type that the framework picks up by type and that
     * is configured with the allowed hosts of this application ({@link AllowedHosts}); it has no constructor the
     * container could fill.
     */
    @Bean
    HttpFirewall httpFirewall(AllowedHosts hosts) {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowedHostnames(hosts::allows);
        return firewall;
    }

    /**
     * A request the firewall refuses: 403 DNS_REBINDING_BLOCKED when the Host is the reason, 400 for any other.
     * A {@code @Bean} method because the handler is the one-method interface of Spring Security, which the framework picks
     * up by type, made here from the resolver that hands the failure to the error advice.
     */
    @Bean
    RequestRejectedHandler requestRejectedHandler(AllowedHosts hosts,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (request, response, rejected) -> resolver.resolveException(request, response, null,
                hosts.allows(request.getServerName()) ? new ErrorResponseException(HttpStatus.BAD_REQUEST)
                        : new AdminException(AdminCode.DNS_REBINDING_BLOCKED, List.of()));
    }

    @Bean
    SecurityFilterChain adminSecurity(HttpSecurity http, AdminMode mode, LedgerAdminProperties properties,
            AdminTokenProvider tokens, SecurityFailures failures, ObjectProvider<ClientRegistrationRepository> clients,
            OwnerOidcUserService owners, ServerProperties server, ObjectProvider<PasskeyRelyingParty> parties,
            ObjectProvider<PasskeyLoginGuard> guards, ObjectProvider<PasskeyEndpointGate> gates,
            ObjectProvider<EnrollmentTokenService> codes) throws Exception {
        AuthenticationManager bearer = new ProviderManager(tokens);
        boolean console = mode == AdminMode.CONSOLE;
        boolean oidc = properties.effectiveSignIn() == SignInMethod.OIDC;
        boolean passkeySignIn = properties.effectiveSignIn() == SignInMethod.PASSKEY;
        http
                .authorizeHttpRequests(requests -> {
                    requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                            .requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                            .requestMatchers("/login", "/login/ott", "/oauth2/authorization/**", "/login/oauth2/code/**")
                            .permitAll();
                    if (!console) {
                        // setup mode has no database to keep a passkey in: no sign-in, the page says how to register a driver
                        requests.anyRequest().denyAll();
                        return;
                    }
                    if (passkeySignIn) {
                        requests.requestMatchers("/login/webauthn", "/webauthn/authenticate/**").permitAll();
                    } else {
                        // one sign-in at a time: with the token or OpenID Connect the passkey is for approvals, never for signing in
                        requests.requestMatchers("/login/webauthn", "/webauthn/authenticate/**").denyAll();
                    }
                    requests.requestMatchers(HttpMethod.GET, "/api/session")
                            .hasAnyAuthority(AdminTokenProvider.OWNER_AUTHORITY, AdminTokenProvider.API_READ_AUTHORITY,
                                    OwnerAccounts.ENROLL_AUTHORITY)
                            .requestMatchers(HttpMethod.GET, "/api/**")
                            .hasAnyAuthority(AdminTokenProvider.OWNER_AUTHORITY, AdminTokenProvider.API_READ_AUTHORITY)
                            .requestMatchers("/api/**").hasAuthority(AdminTokenProvider.OWNER_AUTHORITY)
                            .requestMatchers(HttpMethod.DELETE, "/webauthn/register/**").denyAll()
                            // a passkey is registered only in the enrolment session of a one-time code, whatever the owner
                            // signed in with; the filters of the framework answer these paths before this rule is asked, so
                            // PasskeyEndpointGate enforces it in front of them, and this rule is the second line
                            .requestMatchers("/webauthn/register/**").hasAuthority(OwnerAccounts.ENROLL_AUTHORITY)
                            .requestMatchers("/passkeys")
                            .hasAnyAuthority(AdminTokenProvider.OWNER_AUTHORITY, OwnerAccounts.ENROLL_AUTHORITY)
                            .anyRequest().hasAuthority(AdminTokenProvider.OWNER_AUTHORITY);
                })
                .logout(logout -> {
                    logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout").invalidateHttpSession(true);
                    String cookie = server.getServlet().getSession().getCookie().getName();
                    if (cookie != null) {
                        logout.deleteCookies(cookie);
                    }
                })
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .exceptionHandling(errors -> errors.accessDeniedHandler(failures)
                        .defaultAuthenticationEntryPointFor(failures,
                                PathPatternRequestMatcher.withDefaults().matcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"),
                                browserPages()))
                .oauth2ResourceServer(resource -> resource.authenticationManagerResolver(request -> bearer)
                        .authenticationEntryPoint(failures))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(policy -> policy.policy("camera=(), microphone=(), geolocation=()")));
        if (console) {
            passkeys(http, failures, parties.getObject(), guards.getObject(), gates.getObject(), codes.getObject());
        }
        if (console && properties.effectiveSignIn() == SignInMethod.TOKEN) {
            // the admin token as the password of the application's own page (the personal edition): the budget of checks
            // is in AdminTokenProvider, CSRF and the new session id after the sign-in are the defaults of this chain
            http.formLogin(form -> form.loginPage("/login").passwordParameter("token").defaultSuccessUrl("/")
                    .failureHandler(failures));
        }
        ClientRegistrationRepository registrations = clients.getIfAvailable();
        if (console && oidc) {
            if (registrations == null) {
                throw new IllegalStateException("ledger.admin.sign-in=oidc needs a client registration "
                        + "(spring.security.oauth2.client.registration.*)");
            }
            // PKCE for every client, also a confidential one (Spring Security: OAuth2AuthorizationRequestCustomizers.withPkce)
            DefaultOAuth2AuthorizationRequestResolver authorizations = new DefaultOAuth2AuthorizationRequestResolver(
                    registrations, "/oauth2/authorization");
            authorizations.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
            http.oauth2Login(login -> login.loginPage("/login").defaultSuccessUrl("/")
                    .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizations))
                    .userInfoEndpoint(user -> user.oidcUserService(owners)).failureHandler(failures));
        }
        return http.build();
    }

    /**
     * The requests of a browser that asks for a page (it names {@code text/html}, a wildcard does not count, and it is not
     * an XMLHttpRequest): they go to the login page when nobody is signed in. Any other request gets the 401 problem of the
     * first entry point, as the form login of Spring Security did before.
     */
    private static RequestMatcher browserPages() {
        MediaTypeRequestMatcher html = new MediaTypeRequestMatcher(MediaType.TEXT_HTML, MediaType.APPLICATION_XHTML_XML);
        html.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        RequestMatcher ajax = new RequestHeaderRequestMatcher("X-Requested-With", "XMLHttpRequest");
        return new AndRequestMatcher(html, new NegatedRequestMatcher(ajax));
    }

    /**
     * The passkeys, on the WebAuthn support of Spring Security: the passkey sign-in and the registration endpoints (the default
     * registration page is off: it has an inline script, which the content security policy forbids, so the passkey page of the
     * application drives the endpoints), the delay after many failed attempts in front of the sign-in, and the one-time code of the
     * owner's shell as the way to reach the registration (the enrolment sign-in, which lasts a quarter of an hour and may do
     * nothing but register a passkey).
     */
    private static void passkeys(HttpSecurity http, SecurityFailures failures, PasskeyRelyingParty party,
            PasskeyLoginGuard guard, PasskeyEndpointGate gate, EnrollmentTokenService codes) throws Exception {
        RedirectStrategy redirects = new DefaultRedirectStrategy();
        // the gate stands in front of the guard: a refused sign-in is not a check that counts against the budget
        http.addFilterBefore(guard, LogoutFilter.class).addFilterBefore(gate, PasskeyLoginGuard.class)
                .webAuthn(web -> web.rpName(party.rpName()).rpId(party.rpId()).allowedOrigins(party.origins())
                        .disableDefaultRegistrationPage(true))
                // loginPage: the application's own page, so Spring Security generates no default login page (which would also
                // take over GET /login and add its own passkey script, forbidden by the content security policy)
                .oneTimeTokenLogin(ott -> ott.tokenService(codes).loginPage("/login").loginProcessingUrl("/login/ott")
                        .showDefaultSubmitPage(false).tokenGenerationSuccessHandler((request, response, token) -> { })
                        .failureHandler(failures)
                        .successHandler((request, response, authentication) -> {
                            request.getSession().setMaxInactiveInterval(ENROLLMENT_SECONDS);
                            redirects.sendRedirect(request, response, "/passkeys");
                        }));
    }
}
