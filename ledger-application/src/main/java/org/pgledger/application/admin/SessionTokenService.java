package org.pgledger.application.admin;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.SessionTokenRepository;
import org.pgledger.database.repository.SessionTokenStateRepository;
import org.pgledger.domain.context.ActorScope;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.SessionToken;
import org.pgledger.domain.rule.OwnerAction;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.OwnerIdentity;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.UtcInstant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, authenticates, lists, and revokes standard HTTP Bearer {@code session_token} rows.
 *
 * <p>Only the SHA-256 hex digest and a short display prefix are stored; the raw bearer secret is returned once at
 * issuance and never logged or persisted. Rows are never deleted.
 */
@Service
@Transactional
public class SessionTokenService {

    /** Prefix of every issued bearer token so secret scanners and operators recognize it immediately. */
    public static final String TOKEN_PREFIX = "lgt_";

    private static final int SECRET_BYTES = 32;
    private static final int DISPLAY_PREFIX_CHARS = 12;

    /** Request to issue a new bearer token for {@code author}. */
    public record IssueCommand(String author, String expiresAt, String note) {
    }

    /** Newly issued bearer token; {@code rawToken} is shown once to the owner. */
    public record IssuedToken(long id, String author, String rawToken, String tokenPrefix, Instant issuedAt,
            Instant expiresAt) {
    }

    private final SessionTokenRepository tokens;
    private final SessionTokenStateRepository state;
    private final IdAllocator ids;
    private final OwnerAuditWriter audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SessionTokenService(SessionTokenRepository tokens, SessionTokenStateRepository state, IdAllocator ids,
            OwnerAuditWriter audit, Clock clock) {
        this.tokens = tokens;
        this.state = state;
        this.ids = ids;
        this.audit = audit;
        this.clock = clock;
    }

    /** Issues a fresh bearer session token and records {@link OwnerAction#TOKEN_ISSUED} in the owner audit trail. */
    public IssuedToken issue(IssueCommand command, OwnerIdentity actor) {
        AuthorId author = Authors.of(command.author());
        if (AuthorId.OWNER.equals(author)) {
            throw ValidationException.invalid("author", ValidationReason.MUST_BE_A_SESSION_AUTHOR);
        }
        Instant expiresAt = command.expiresAt() == null || command.expiresAt().isBlank() ? null
                : UtcInstant.parse("expires_at", command.expiresAt()).value();
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        String rawToken = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String tokenHash = Sha256.ofText(rawToken).hex();
        String prefix = rawToken.substring(0, DISPLAY_PREFIX_CHARS);
        Instant now = clock.instant();
        long id = ids.next(Counter.SESSION_TOKEN);
        SessionToken row = SessionToken.issue(id, author, tokenHash, prefix, AuthorId.OWNER, now, expiresAt,
                command.note());
        try {
            ScopedValue.where(ActorScope.CURRENT, AuthorId.OWNER).call(() -> tokens.insert(row));
        } catch (DuplicateKeyException duplicate) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("session_token " + id), duplicate);
        }
        audit.record(actor, OwnerAction.TOKEN_ISSUED, author.value(),
                "token_id=" + id + " prefix=" + prefix + " expires=" + (expiresAt == null ? "never" : expiresAt));
        return new IssuedToken(id, author.value(), rawToken, prefix, now, expiresAt);
    }

    /** Revokes a single token by id and records {@link OwnerAction#TOKEN_REVOKED}; returns true if newly revoked. */
    public boolean revoke(long tokenId, OwnerIdentity actor) {
        SessionToken existing = tokens.findById(tokenId)
                .orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND,
                        List.of("session_token", Long.toString(tokenId))));
        boolean changed = state.revoke(tokenId, clock.instant());
        audit.record(actor, OwnerAction.TOKEN_REVOKED, existing.authorId().value(),
                "token_id=" + tokenId + " revoked=" + changed);
        return changed;
    }

    /**
     * Authenticates a presented raw bearer token against active non-expired rows and stamps {@code last_used_at}.
     */
    public Optional<SessionToken> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String hash = Sha256.ofText(rawToken.strip()).hex();
        Instant now = clock.instant();
        Optional<SessionToken> found = state.findLiveByHash(hash, now);
        found.ifPresent(token -> state.touchLastUsed(token.id(), now));
        return found;
    }

    @Transactional(readOnly = true)
    public List<SessionToken> live(String author) {
        return state.findLiveByAuthor(Authors.of(author), clock.instant());
    }

    @Transactional(readOnly = true)
    public List<SessionToken> listUnrevoked() {
        return state.findAllUnrevoked();
    }
}
