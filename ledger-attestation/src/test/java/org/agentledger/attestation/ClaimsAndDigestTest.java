// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.attestation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ATT-050 .. ATT-052: the claims answer which bound field differs; one SHA-256 hex for the digest and the fingerprint. */
class ClaimsAndDigestTest {

    private static final String DIGEST = ArgumentsDigest.of(Map.of("a", 1));
    private static final String OTHER_DIGEST = ArgumentsDigest.of(Map.of("a", 2));

    private static final AttestationClaims CLAIMS =
            new AttestationClaims("claude:s1", "ledger_save", "r1", DIGEST, 1_700_000_000L);

    @Test
    @DisplayName("ATT-050 the claims name the first bound field that differs from the call, in the order tool, operation key, arguments (renamed from request id by OPK-015)")
    void firstMismatch() {
        assertThat(CLAIMS.firstMismatch("ledger_save", "r1", DIGEST)).isEmpty();
        assertThat(CLAIMS.firstMismatch("ledger_link", "r1", DIGEST)).hasValue(AttestationClaims.Field.TOOL);
        assertThat(CLAIMS.firstMismatch("ledger_save", "r2", DIGEST)).hasValue(AttestationClaims.Field.OPERATION_KEY);
        assertThat(CLAIMS.firstMismatch("ledger_save", "r1", OTHER_DIGEST)).hasValue(AttestationClaims.Field.ARGUMENTS);
        // the order of the checks is part of the answer: a call that differs in all three is a tool mismatch
        assertThat(CLAIMS.firstMismatch("ledger_link", "r2", OTHER_DIGEST)).hasValue(AttestationClaims.Field.TOOL);
        assertThat(CLAIMS.firstMismatch("ledger_save", "r2", OTHER_DIGEST)).hasValue(AttestationClaims.Field.OPERATION_KEY);
        // a tool that is absent (outside a tool call) never matches
        assertThat(CLAIMS.firstMismatch(null, "r1", DIGEST)).hasValue(AttestationClaims.Field.TOOL);
        assertThat(CLAIMS.firstMismatch("ledger_save", "r1", null)).hasValue(AttestationClaims.Field.ARGUMENTS);
    }

    @Test
    @DisplayName("ATT-050 a call without an operation key matches claims whose operation key is empty, and only those")
    void callWithoutOperationKey() {
        AttestationClaims whoami = new AttestationClaims("claude:s1", "ledger_whoami", "", DIGEST, 1L);
        assertThat(whoami.firstMismatch("ledger_whoami", null, DIGEST)).isEmpty();
        assertThat(whoami.firstMismatch("ledger_whoami", "", DIGEST)).isEmpty();
        assertThat(whoami.firstMismatch("ledger_whoami", "r1", DIGEST)).hasValue(AttestationClaims.Field.OPERATION_KEY);
        assertThat(CLAIMS.firstMismatch("ledger_save", null, DIGEST)).hasValue(AttestationClaims.Field.OPERATION_KEY);
    }

    @Test
    @DisplayName("ATT-050 ATA-021 a claim for an attachment tool carries its hyphenated name and an empty operation key, and matches only that tool")
    void attachmentToolClaim() {
        AttestationClaims chunk = new AttestationClaims("claude:s1", "artifact-chunk", "", DIGEST, 1L);
        assertThat(chunk.firstMismatch("artifact-chunk", null, DIGEST)).isEmpty();
        assertThat(chunk.firstMismatch("artifact-chunk", "", DIGEST)).isEmpty();
        assertThat(chunk.firstMismatch("artifact-chunk", "r1", DIGEST)).hasValue(AttestationClaims.Field.OPERATION_KEY);
        assertThat(chunk.firstMismatch("artifact-finish", null, DIGEST)).hasValue(AttestationClaims.Field.TOOL);
        assertThat(chunk.firstMismatch("ledger_artifact-chunk", null, DIGEST)).hasValue(AttestationClaims.Field.TOOL);
        assertThat(chunk.firstMismatch("artifact-chunk", null, OTHER_DIGEST)).hasValue(AttestationClaims.Field.ARGUMENTS);
    }

    @Test
    @DisplayName("ATT-051 OPK-015 the field answers its contract token: tool, operationKey (was request_id), arguments")
    void fieldTokens() {
        assertThat(AttestationClaims.Field.TOOL.token()).isEqualTo("tool");
        assertThat(AttestationClaims.Field.OPERATION_KEY.token()).isEqualTo("operationKey");
        assertThat(AttestationClaims.Field.ARGUMENTS.token()).isEqualTo("arguments");
        assertThat(AttestationClaims.Field.values()).hasSize(3);
        assertThat(AttestationClaims.Field.values())
                .containsExactly(AttestationClaims.Field.TOOL, AttestationClaims.Field.OPERATION_KEY,
                        AttestationClaims.Field.ARGUMENTS);
    }

    @Test
    @DisplayName("OPK-015 the claims carry the operation key as given (any text, empty included) and answer it by operationKey(); the order of the mismatch checks is tool, operation key, arguments")
    void claimsCarryTheOperationKey() {
        String key = "op-key 한글/with.dots and spaces";
        AttestationClaims claims = new AttestationClaims("claude:s1", "ledger_save", key, DIGEST, 1L);
        assertThat(claims.operationKey()).isEqualTo(key);
        assertThat(new AttestationClaims("claude:s1", "ledger_save", "", DIGEST, 1L).operationKey()).isEmpty();
        assertThat(claims.firstMismatch("ledger_save", key, DIGEST)).isEmpty();
        // a key that differs only in case or by one character is another key
        assertThat(claims.firstMismatch("ledger_save", key.toUpperCase(), DIGEST))
                .hasValue(AttestationClaims.Field.OPERATION_KEY);
        assertThat(claims.firstMismatch("ledger_save", key + "x", OTHER_DIGEST))
                .hasValue(AttestationClaims.Field.OPERATION_KEY);
        assertThat(claims.firstMismatch("ledger_link", key + "x", OTHER_DIGEST)).hasValue(AttestationClaims.Field.TOOL);
        assertThat(claims.firstMismatch("ledger_save", key, OTHER_DIGEST)).hasValue(AttestationClaims.Field.ARGUMENTS);
        assertThatThrownBy(() -> new AttestationClaims("claude:s1", "ledger_save", null, DIGEST, 1L))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("operationKey");
    }

    @Test
    @DisplayName("ATT-052 the one SHA-256 hex: a known vector, 64 lower-case characters, and the digest and the fingerprint use it")
    void sha256Hex() throws NoSuchAlgorithmException {
        // FIPS 180-4 example: SHA-256("abc")
        assertThat(Sha256Hex.of("abc".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        // SHA-256 of the empty input
        assertThat(Sha256Hex.of(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(ArgumentsDigest.isWellFormed(Sha256Hex.of(new byte[] {1, 2, 3}))).isTrue();
        // the digest of the arguments is the hex of the canonical form
        assertThat(ArgumentsDigest.of(Map.of())).isEqualTo(Sha256Hex.of("{}".getBytes(StandardCharsets.UTF_8)));
        // the fingerprint of a key is the hex of its DER bytes
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThat(PublicKeys.fingerprint(pair.getPublic())).isEqualTo(Sha256Hex.of(pair.getPublic().getEncoded()));
    }
}
