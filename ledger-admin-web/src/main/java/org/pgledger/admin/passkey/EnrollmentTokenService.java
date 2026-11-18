// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.passkey;

import java.time.Clock;
import java.time.Duration;
import org.pgledger.admin.security.AdminAuthenticationException;
import org.pgledger.admin.security.LoginAttempts;
import org.pgledger.admin.security.LoginDelay;
import org.pgledger.application.admin.PasskeyService;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.error.AdminException;
import org.springframework.security.authentication.ott.DefaultOneTimeToken;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.authentication.ott.OneTimeTokenService;
import org.springframework.stereotype.Component;

/**
 * The one-time code of the owner's shell as a one-time token of Spring Security's {@code oneTimeTokenLogin}: the owner runs
 * {@code ledger-cli passkey-code} on the server, which stores the hash of a code ({@link PasskeyService#issueCode}); the code
 * typed into the login page is "consumed" here, which spends it, and signs the browser in as the enrolment account
 * ({@code OwnerAccounts.ENROLL}): a session that may register a passkey (the framework's registration endpoints and the
 * passkey page) and nothing else. Generating a token through the web is not offered: {@link #generate} always refuses, because
 * the code is made only by the command on the server (that the owner can run it there is the proof that the owner is the owner).
 *
 * <p>The attempts are counted like every other sign-in attempt ({@link LoginAttempts}): beyond the global budget the check is
 * delayed, never refused, so the right code always signs in.
 */
@Component
public final class EnrollmentTokenService implements OneTimeTokenService {

    private final PasskeyService passkeys;
    private final LoginAttempts attempts;
    private final LoginDelay delays;
    private final Clock clock;

    public EnrollmentTokenService(PasskeyService passkeys, LoginAttempts attempts, LoginDelay delays, Clock clock) {
        this.passkeys = passkeys;
        this.attempts = attempts;
        this.delays = delays;
        this.clock = clock;
    }

    /**
     * The web makes no code: the framework's endpoint that asks for one ({@code /ott/generate}) gets a token that is stored
     * nowhere and that nothing delivers (the delivery handler of the sign-in does nothing), so it cannot be used, and no row of
     * {@code owner_passkey_code} is written.
     */
    @Override
    public OneTimeToken generate(GenerateOneTimeTokenRequest request) {
        return new DefaultOneTimeToken("unused", request.getUsername(), clock.instant());
    }

    @Override
    public OneTimeToken consume(OneTimeTokenAuthenticationToken authenticationToken) {
        try {
            delays.await(attempts.admit());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AdminAuthenticationException(AdminCode.TOO_MANY_AUTH_FAILURES, interrupted);
        }
        try {
            passkeys.spendCode(authenticationToken.getTokenValue());
        } catch (AdminException refused) {
            throw new AdminAuthenticationException(AdminCode.PASSKEY_CODE_INVALID, refused);
        }
        attempts.succeeded();
        return new DefaultOneTimeToken(authenticationToken.getTokenValue(), OwnerAccounts.ENROLL,
                clock.instant().plus(Duration.ofSeconds(1)));
    }
}
