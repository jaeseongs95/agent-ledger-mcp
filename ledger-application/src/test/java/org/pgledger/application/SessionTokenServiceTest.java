// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.admin.SessionTokenService;
import org.pgledger.application.admin.SessionTokenService.IssueCommand;
import org.pgledger.application.admin.SessionTokenService.IssuedToken;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.model.SessionToken;
import org.pgledger.testing.TestOwners;

/**
 * Verifies bearer {@link SessionTokenService} issuance, SHA-256 storage, authentication, expiry, revocation, and owner
 * audit records.
 */
@LedgerServiceTest
class SessionTokenServiceTest {

    private static String freshAuthor() {
        return "agent:tok-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("TOK-01: issuing a token stores only its SHA-256 hash, authenticates and touches last_used_at, and audits TOKEN_ISSUED")
    void tok01_issueAndAuthenticate(Scenario s) {
        SessionTokenService service = s.bean(SessionTokenService.class);
        String author = freshAuthor();

        IssuedToken issued = service.issue(new IssueCommand(author, null, "ci-worker"), TestOwners.OWNER);

        assertThat(issued.rawToken()).startsWith(SessionTokenService.TOKEN_PREFIX);
        assertThat(issued.tokenPrefix()).hasSize(12);
        assertThat(s.count("SELECT COUNT(*) FROM session_token WHERE token_hash = ?", issued.rawToken())).isZero();

        Instant later = s.ctx().clock().instant().plusSeconds(120);
        s.ctx().clock().set(later);
        SessionToken authed = service.authenticate(issued.rawToken()).orElseThrow();
        assertThat(authed.id()).isEqualTo(issued.id());
        assertThat(authed.authorId().value()).isEqualTo(author);
        assertThat(service.live(author)).singleElement().satisfies(t -> assertThat(t.lastUsedAt()).isEqualTo(later));
        assertThat(s.count("SELECT COUNT(*) FROM owner_audit WHERE operation = 'TOKEN_ISSUED' AND subject = ?",
                author)).isEqualTo(1);
    }

    @Test
    @DisplayName("TOK-02: expired and revoked tokens fail authentication; revoking records TOKEN_REVOKED and keeps the row")
    void tok02_expiryAndRevocation(Scenario s) {
        SessionTokenService service = s.bean(SessionTokenService.class);
        String author = freshAuthor();
        Instant start = s.ctx().clock().instant();

        IssuedToken expiring = service.issue(new IssueCommand(author, start.plusSeconds(60).toString(), null),
                TestOwners.OWNER);
        IssuedToken revocable = service.issue(new IssueCommand(author, null, "to-revoke"), TestOwners.OWNER);

        assertThat(service.authenticate(expiring.rawToken())).isPresent();
        s.ctx().clock().set(start.plusSeconds(61));
        assertThat(service.authenticate(expiring.rawToken())).isEmpty();

        assertThat(service.revoke(revocable.id(), TestOwners.OWNER)).isTrue();
        assertThat(service.revoke(revocable.id(), TestOwners.OWNER)).isFalse();
        assertThat(service.authenticate(revocable.rawToken())).isEmpty();
        assertThat(service.listUnrevoked()).extracting(SessionToken::id).doesNotContain(revocable.id());
        assertThat(s.count("SELECT COUNT(*) FROM session_token WHERE author_id = ?", author)).isEqualTo(2);
        assertThat(s.count("SELECT COUNT(*) FROM owner_audit WHERE operation = 'TOKEN_REVOKED' AND subject = ?",
                author)).isEqualTo(2);

        Expect.failure(() -> service.revoke(999999L, TestOwners.OWNER), LookupCode.NOT_FOUND);
        Expect.failure(() -> service.issue(new IssueCommand("user:owner", null, null), TestOwners.OWNER),
                ValidationCode.VALIDATION_FAILED);
    }
}
