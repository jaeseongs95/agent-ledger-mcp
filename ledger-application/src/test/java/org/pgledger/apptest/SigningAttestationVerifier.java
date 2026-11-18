// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.apptest;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.gate.AttestationVerifier;
import org.pgledger.application.gate.SignedAttestationVerifier;
import org.pgledger.application.gate.ToolCall;
import org.pgledger.attestation.AttestationToken;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.SessionKeyRepository;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.value.AuthorId;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.pgledger.testing.TestOwners;

/**
 * The service tests call the services with the plain author of a test ({@code claude:e2e-author}), as the services were
 * called before attestations were signed. This class stands in for the hook of that author: a plain value is
 * signed with the key of the author (registered as the owner does, in a transaction of its own, the first time the
 * author is used) and then checked by the real {@link SignedAttestationVerifier}, so every call of a service test runs
 * the whole verification: key lookup, signature, binding, window. A value that already is a token (a test of the
 * verification itself) is passed to the real verifier as it is.
 *
 * <p>A plain value that has no author format is signed with a key that nobody registered: the format check of the
 * verifier comes first and refuses it, as it did before.
 */
public final class SigningAttestationVerifier implements AttestationVerifier {

    /** The tool of a call that is not made through a tool binding (the service tests call the services directly). */
    static final String DIRECT_TOOL = "ledger_service_test";

    private final SignedAttestationVerifier real;
    private final TestKeyRing keys;
    private final Clock clock;
    private final SessionKeyService sessionKeys;
    private final SessionKeyRepository keyRows;
    private final IdAllocator ids;
    private final TransactionTemplate ownTransaction;
    private final Set<String> enrolled = ConcurrentHashMap.newKeySet();

    SigningAttestationVerifier(SignedAttestationVerifier real, TestKeyRing keys, Clock clock,
            SessionKeyService sessionKeys, SessionKeyRepository keyRows, IdAllocator ids,
            PlatformTransactionManager transactions) {
        this.real = real;
        this.keys = keys;
        this.clock = clock;
        this.sessionKeys = sessionKeys;
        this.keyRows = keyRows;
        this.ids = ids;
        this.ownTransaction = new TransactionTemplate(transactions,
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Override
    public Proof prove(String presented, ToolCall call, String operationKey) {
        return check(presented, call, operationKey, false);
    }

    @Override
    public AuthorId verifyForWrite(String presented, ToolCall call, String operationKey) {
        return check(presented, call, operationKey, true).requireAuthor();
    }

    private Proof check(String presented, ToolCall call, String operationKey, boolean write) {
        if (presented == null || presented.startsWith(AttestationToken.PREFIX + ".")) {
            return verifyWith(presented, call, operationKey, write);
        }
        if (AuthorId.matches(presented)) {
            enroll(presented);
        }
        // a call that is not made through a tool binding is signed for the tool and the digest of the direct calls
        ToolCall.Digested signed = switch (call) {
            case ToolCall.Outside outside -> new ToolCall.Digested(DIRECT_TOOL, TestKeyRing.DIRECT_DIGEST);
            case ToolCall.Named named -> new ToolCall.Digested(named.tool(), TestKeyRing.DIRECT_DIGEST);
            case ToolCall.Digested digested -> digested;
        };
        String token = keys.token(presented, signed.tool(), operationKey == null ? "" : operationKey,
                signed.argumentsDigest(), clock.instant());
        return verifyWith(token, signed, operationKey, write);
    }

    private Proof verifyWith(String token, ToolCall call, String operationKey, boolean write) {
        return write ? new Proof.Proven(real.verifyForWrite(token, call, operationKey))
                : real.prove(token, call, operationKey);
    }

    /**
     * Registers the key of the author as the owner would (once per context): the transaction of its own commits at
     * once, so a rollback of the service call that needs the key does not take the key with it.
     */
    public void enroll(String author) {
        if (!enrolled.contains(author)) {
            ownTransaction.executeWithoutResult(status -> {
                if (Objects.equals(AuthorId.OWNER.value(), author)) {
                    insertOwnerRoleKey(author);
                } else {
                    sessionKeys.register(
                            new SessionKeyService.RegisterCommand(author, keys.publicKeyOf(author), null), TestOwners.OWNER);
                }
            });
            enrolled.add(author);
        }
    }

    /**
     * The owner role {@code user:owner} gets no key from the service (audit2 S5: only sessions sign), so the service
     * tests that write as that author (the gate needs a grant for it too, SVC-GATE-16) put its key row in directly. The
     * real server can never verify a write of that author.
     */
    private void insertOwnerRoleKey(String author) {
        var publicKey = keys.pairOf(author).getPublic();
        keyRows.insert(SessionPublicKey.register(ids.next(Counter.SESSION_KEY), AuthorId.OWNER,
                PublicKeys.encode(publicKey), PublicKeys.fingerprint(publicKey), AuthorId.OWNER, clock.instant(), null));
    }
}
