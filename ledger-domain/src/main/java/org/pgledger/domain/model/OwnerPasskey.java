package org.pgledger.domain.model;

import java.time.Instant;

/**
 * A passkey of the owner as it is stored: the credential id and the public key (COSE, as the authenticator wrote it) and the
 * attestation object (which the WebAuthn verification reads the key from), every byte string as base64url text. The
 * three flags are 0 or 1 (the schema has no boolean column). The row is written once and never changed; closing a passkey is a
 * row of its own ({@code owner_passkey_revocation}). It holds no secret: the private key never leaves the authenticator.
 *
 * @param credentialId      the credential id (base64url, at most {@value #CREDENTIAL_ID_MAX_LENGTH} characters)
 * @param userId            the user handle of the owner (base64url)
 * @param publicKey         the COSE public key (base64url)
 * @param signatureCount    the counter at registration
 * @param uvInitialized     1 when the authenticator verified the user at registration
 * @param backupEligible    1 when the credential may be synced
 * @param backupState       1 when it is synced now
 * @param transports        the transports the authenticator reported, comma separated (may be empty)
 * @param attestationObject the attestation object (base64url)
 * @param label             what the owner called it (at most {@value #LABEL_MAX_LENGTH} characters)
 * @param fingerprint       four groups of four upper-case hex digits (the first 8 bytes of the SHA-256 of the public key), which
 *                          the owner compares on screen
 * @param registeredBy      the owner identity that registered it
 * @param registeredAt      when
 */
public record OwnerPasskey(String credentialId, String userId, String publicKey, long signatureCount, int uvInitialized,
        int backupEligible, int backupState, String transports, String attestationObject, String label,
        String fingerprint, String registeredBy, Instant registeredAt) {

    /** The longest credential id that is stored. */
    public static final int CREDENTIAL_ID_MAX_LENGTH = 512;
    /** The longest label. */
    public static final int LABEL_MAX_LENGTH = 60;
    /** The longest public key text. */
    public static final int PUBLIC_KEY_MAX_LENGTH = 1024;
    /** The longest attestation object text. */
    public static final int ATTESTATION_MAX_LENGTH = 4096;
}
