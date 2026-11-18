// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import org.pgledger.attestation.ArgumentsDigest;
import org.pgledger.attestation.AttestationClaims;
import org.pgledger.attestation.AttestationToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The one place of the tests that reads a stamped {@code _hostAttestation}: it parses the token, compares every claim
 * and verifies the signature with the public key of the author, taken from the key folder of the test.
 */
final class Attestations {

    /** The checked token and its claims; the hook adds nothing to the arguments but the token (OPK-009). */
    record Stamp(String token, AttestationClaims claims) {}

    /** The accepted values of {@code issuedAt}, in epoch seconds, both ends included. */
    record Window(long from, long to) {

        /** Exactly this instant, for a run with the fixed clock of {@link HookFixture}. */
        static Window at(Instant instant) {
            return new Window(instant.getEpochSecond(), instant.getEpochSecond());
        }
    }

    private Attestations() {}

    /**
     * Verifies the attestation that the hook put into {@code carrier} (the object of the answer that holds the stamped
     * arguments, or the stamped arguments themselves) for a call whose own arguments were {@code sent}.
     *
     * @param server the MCP server of the call: the hook signs with the key of this server and this author
     * @param op the operation after {@code ledger_} (save, whoami ...), or the name of an attachment tool (artifact-chunk ...)
     */
    static Stamp verify(Path keyDirectory, Window window, String server, String author, String op, JsonNode sent,
            JsonNode carrier) {
        JsonNode tokenNode = carrier.path(Json.RESERVED_KEY);
        assertThat(tokenNode.isString()).as("_hostAttestation is a text").isTrue();
        String token = tokenNode.stringValue();
        AttestationToken parsed = AttestationToken.parse(token);
        assertThat(parsed.text()).as("canonical token text").isEqualTo(token);
        AttestationClaims claims = parsed.claims();
        assertThat(claims.author()).as("author").isEqualTo(author);
        assertThat(claims.tool()).as("tool").isEqualTo(Hosts.canonical(op));
        assertThat(claims.issuedAt()).as("issuedAt").isBetween(window.from(), window.to());
        // throws AttestationException (SIGNATURE_INVALID) if the key of the author did not sign these claims
        parsed.verify(publicKey(keyDirectory, server, author));

        // OPK-009: the hook never makes a key up; the claim's key is the operationKey argument exactly as given (a text,
        // any content, also blank), and empty where the call has none (absent or JSON null) or the tool carries none
        JsonNode given = sent.get("operationKey");
        boolean signsTheArgument = Hosts.carriesOperationKey(op) && given != null && given.isString();
        assertThat(claims.operationKey()).as("the operation key of the claim for " + op)
                .isEqualTo(signsTheArgument ? given.stringValue() : "");
        if (!sent.has("operationKey")) {
            assertThat(carrier.has("operationKey")).as("no operationKey added for " + op).isFalse();
        }
        // the digest covers the arguments the host hands the server (exactly the model's, the attestation out),
        // recomputed here with the shared class from the tests' own reading of the JSON
        Map<?, ?> plain = (Map<?, ?>) Json.MAPPER.treeToValue(((ObjectNode) sent).deepCopy(), Object.class);
        assertThat(claims.argumentsDigest()).as("arguments digest").isEqualTo(ArgumentsDigest.of(plain));
        return new Stamp(token, claims);
    }

    /** The public key of the author for the server from the key folder (the key is read, or created if there is none yet). */
    static PublicKey publicKey(Path keyDirectory, String server, String author) {
        return TestStores.load(TestStores.open(keyDirectory), server, author).publicKey();
    }

    /** The name of the key file of a server and an author: the lower-case hex SHA-256 of {@code server LF author} plus {@code .key}. */
    static String keyFileName(String server, String author) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((server + "\n" + author).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest) + ".key";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
