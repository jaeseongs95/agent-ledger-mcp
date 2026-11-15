package org.pgledger.application.gate;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.attestation.AttestationClaims;
import org.pgledger.attestation.AttestationException;
import org.pgledger.attestation.AttestationToken;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.database.repository.SessionKeyStateRepository;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.value.AuthorId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Ed25519 check of the author attestation. The checks run in a fixed order and the first one that fails is the
 * answer, so a caller that cannot prove who it is learns nothing about the later checks: parse and author format, the
 * signature under the live registered key of the author (a missing key and a wrong signature are one and the same
 * answer, {@link TrustCode#ATTESTATION_SIGNATURE_INVALID} without any argument, so a caller that cannot sign cannot find
 * out which authors have a key; the precise reason is logged here and nowhere else), the binding to the tool, the
 * operation key and the arguments of the call, the time window. The time is the injected {@link Clock}.
 */
@Component
public class SignedAttestationVerifier implements AttestationVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(SignedAttestationVerifier.class);

    /**
     * The public key of an Ed25519 key pair that is generated when the verifier is made (JDK {@code KeyPairGenerator},
     * https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/security/KeyPairGenerator.html) and whose private
     * key is dropped at once, so nobody can sign for it. It is a real curve point, so a check against it costs what a
     * check against a registered key costs. An author without a live key is checked against it ({@link #provenKey}); the
     * result of that check is never used for anything but the time it takes.
     */
    private final String dummyPublicKey;

    private final SessionKeyStateRepository keys;
    private final AttestationProperties properties;
    private final Clock clock;

    public SignedAttestationVerifier(SessionKeyStateRepository keys, AttestationProperties properties, Clock clock) {
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
        try {
            // the private key of the pair is not kept: only its public half is needed, and nobody can sign for it
            this.dummyPublicKey = PublicKeys.encode(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("This JDK has no Ed25519 key pair generator", failure);
        }
    }

    @Override
    public Proof prove(String presented, ToolCall call, String operationKey) {
        return check(presented, call, operationKey);
    }

    /**
     * ceiling: the live key is read, not locked (principle 4.4: no lock device, READ_COMMITTED). The write that follows
     * ({@link WriteGate} stamps the grant with a conditional UPDATE) does not touch the row of the key, and the close of a key does
     * not touch the grants of the author (they stay), so no statement of the write can decide a race with a close of the key.
     * The window is the time of the write transaction: a write that read the key as live before the owner's close committed
     * may commit after it. Every write that reads the key after the close committed is refused
     * ({@code ATTESTATION_SIGNATURE_INVALID}).
     */
    @Override
    public AuthorId verifyForWrite(String presented, ToolCall call, String operationKey) {
        return check(presented, call, operationKey).requireAuthor();
    }

    private Proof check(String presented, ToolCall call, String operationKey) {
        requireTokenForm(presented);
        if (!presented.startsWith(AttestationToken.PREFIX + ".")) {
            // not a token and not a bare author (requireTokenForm refused that): a malformed author
            return new Proof.MalformedAuthor(presented);
        }
        AttestationToken token = parse(presented);
        AttestationClaims claims = token.claims();
        if (claims.author() != null && !AuthorId.matches(claims.author())) {
            return new Proof.MalformedAuthor(claims.author());
        }
        AuthorId author = AuthorId.of(claims.author());
        provenKey(token, author);
        verifyBinding(claims, call, operationKey);
        verifyWindow(claims);
        return new Proof.Proven(author);
    }

    /**
     * A value that is not a token is told apart: a bare {@code <vendor>:<session-id>} is the unsigned old format
     * (ATTESTATION_UNSIGNED); nothing at all is HOST_ATTESTATION_REQUIRED. Any other value that is not a token has a
     * malformed author, which the caller decides.
     */
    private static void requireTokenForm(String presented) {
        if (presented == null) {
            throw new TrustException(TrustCode.HOST_ATTESTATION_REQUIRED, List.of());
        }
        if (!presented.startsWith(AttestationToken.PREFIX + ".") && AuthorId.matches(presented)) {
            throw new TrustException(TrustCode.ATTESTATION_UNSIGNED, List.of());
        }
    }

    private static AttestationToken parse(String presented) {
        try {
            return AttestationToken.parse(presented);
        } catch (AttestationException failure) {
            throw new TrustException(TrustCode.ATTESTATION_MALFORMED, List.of(), Map.of(), failure);
        }
    }

    /**
     * The live key of the author, once the signature is proven under it; every other outcome is one answer. An author
     * without a live key is answered after the same work as one with a key: the signature is checked against
     * {@link #dummyPublicKey}, a valid key made at start that nobody can sign for, so the time of the answer does not tell a
     * caller which authors have a key (the answer itself is already one for both cases).
     */
    private SessionPublicKey provenKey(AttestationToken token, AuthorId author) {
        Optional<SessionPublicKey> registered = keys.findLive(author);
        String keyText = registered.map(SessionPublicKey::publicKey).orElse(dummyPublicKey);
        try {
            PublicKey key = PublicKeys.decode(keyText);
            token.verify(key);
        } catch (AttestationException failure) {
            LOG.info(registered.isPresent()
                    ? "attestation of {} refused: the signature does not match its live session key"
                    : "attestation of {} refused: no live session key is registered", author.value());
            throw new TrustException(TrustCode.ATTESTATION_SIGNATURE_INVALID, List.of(), Map.of(), failure);
        }
        // an empty registration is not reachable (nobody can sign for the dummy key); the answer would be the same if it were
        return registered.orElseThrow(() -> {
            LOG.warn("attestation of {} verified under the dummy key and is refused: no live session key", author.value());
            return signatureInvalid();
        });
    }

    private static TrustException signatureInvalid() {
        return new TrustException(TrustCode.ATTESTATION_SIGNATURE_INVALID, List.of());
    }

    private static void verifyBinding(AttestationClaims claims, ToolCall call, String operationKey) {
        if (!call.isTool(claims.tool())) {
            throw mismatch("tool");
        }
        if (!Objects.equals(operationKey == null ? "" : operationKey, claims.operationKey())) {
            throw mismatch(AttestationClaims.Field.OPERATION_KEY.token());
        }
        if (!call.carries(claims.argumentsDigest())) {
            throw mismatch("arguments");
        }
    }

    private static TrustException mismatch(String field) {
        return new TrustException(TrustCode.ATTESTATION_BINDING_MISMATCH, List.of(field),
                Map.of("field", List.of(field)));
    }

    private void verifyWindow(AttestationClaims claims) {
        long window = properties.window().toSeconds();
        if (Math.abs(clock.instant().getEpochSecond() - claims.issuedAt()) > window) {
            throw new TrustException(TrustCode.ATTESTATION_TIME_WINDOW, List.of(Long.toString(window)),
                    Map.of("window_seconds", List.of(Long.toString(window))));
        }
    }
}
