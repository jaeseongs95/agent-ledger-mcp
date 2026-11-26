// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.apptest;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.agentledger.attestation.ArgumentsDigest;
import org.agentledger.attestation.AttestationClaims;
import org.agentledger.attestation.AttestationToken;
import org.agentledger.attestation.PublicKeys;

/**
 * The Ed25519 key pairs of the test authors of one Spring context, made on first use, and the signing of attestations
 * with them. It plays the hook of the author: the one place of the service tests where a signature is made.
 *
 * <p>The service tests call the services directly, without the tool binding that computes the digest of the raw
 * arguments of a call, so a call of a service test carries {@link #DIRECT_DIGEST} (the digest of an empty arguments
 * object) unless a test binds another one: the tests of the arguments binding sign and bind digests of their own.
 */
public final class TestKeyRing {

    /** The arguments digest of a service test that does not care about it: the digest of {@code {}}. */
    public static final String DIRECT_DIGEST = ArgumentsDigest.of(Map.of());

    private final ConcurrentHashMap<String, KeyPair> pairs = new ConcurrentHashMap<>();

    /** A fresh key pair that belongs to nobody (for a signature that the server must refuse). */
    public static KeyPair newPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("This JDK has no Ed25519 key pair generator", failure);
        }
    }

    /** The key pair of the author (made when it is asked for the first time). */
    public KeyPair pairOf(String author) {
        return pairs.computeIfAbsent(author, name -> newPair());
    }

    /** The public key of the author in the form the owner registers. */
    public String publicKeyOf(String author) {
        return PublicKeys.encode(pairOf(author).getPublic());
    }

    /** The fingerprint of the public key of the author. */
    public String fingerprintOf(String author) {
        return PublicKeys.fingerprint(pairOf(author).getPublic());
    }

    /** The text of an attestation signed with the key of the author, for {@link #DIRECT_DIGEST}. */
    public String token(String author, String tool, String operationKey, Instant issuedAt) {
        return token(author, tool, operationKey, DIRECT_DIGEST, issuedAt);
    }

    /** The text of an attestation signed with the key of the author for these arguments. */
    public String token(String author, String tool, String operationKey, String argumentsDigest, Instant issuedAt) {
        return token(pairOf(author), author, tool, operationKey, argumentsDigest, issuedAt);
    }

    /** The text of an attestation for {@code author} signed with any key pair, for {@link #DIRECT_DIGEST}. */
    public static String token(KeyPair signer, String author, String tool, String operationKey, Instant issuedAt) {
        return token(signer, author, tool, operationKey, DIRECT_DIGEST, issuedAt);
    }

    /** The text of an attestation for {@code author} signed with any key pair, for these arguments. */
    public static String token(KeyPair signer, String author, String tool, String operationKey, String argumentsDigest,
            Instant issuedAt) {
        return AttestationToken.sign(new AttestationClaims(author, tool, operationKey, argumentsDigest,
                issuedAt.getEpochSecond()), signer.getPrivate()).text();
    }
}
