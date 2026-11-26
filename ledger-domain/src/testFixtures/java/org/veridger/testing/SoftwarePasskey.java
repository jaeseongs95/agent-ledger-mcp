// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.testing;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * A WebAuthn authenticator in software (ES256, EC P-256), for the tests: it writes what a browser passkey returns, every part
 * as the browser sends it (base64url). The attestation object of a registration is made with a small CBOR writer (the format
 * "none", the authenticator data with the credential and its COSE key), so a test needs no browser, no authenticator and no
 * library of WebAuthn. The assertion of a sign-in or of an approval is signed over the authenticator data and the SHA-256 of the
 * client data, as the specification says; the parts a test bends (client data, flags, relying party) are arguments.
 */
public final class SoftwarePasskey {

    /** The flags of the authenticator data: user present and user verified; with the attested credential data. */
    public static final int PRESENT_AND_VERIFIED = 0x05;
    public static final int ATTESTED = 0x45;

    /** The four parts of an assertion, as the browser sends them. */
    public record Assertion(String credentialId, String authenticatorData, String clientDataJson, String signature) {
    }

    /** The two parts of a registration, as the browser sends them. */
    public record Registration(String credentialId, String attestationObject, String clientDataJson) {
    }

    private final byte[] credentialId = new byte[32];
    private final KeyPair pair;

    /** A new credential with a key of its own. */
    public SoftwarePasskey() {
        new SecureRandom().nextBytes(credentialId);
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            this.pair = generator.generateKeyPair();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String base64url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The credential id (base64url). */
    public String credentialId() {
        return base64url(credentialId);
    }

    /** The COSE key (a CBOR map: EC2, ES256, P-256, x and y), base64url. */
    public String publicKey() {
        return base64url(coseKey());
    }

    private byte[] coseKey() {
        ECPublicKey key = (ECPublicKey) pair.getPublic();
        ByteArrayOutputStream cbor = new ByteArrayOutputStream();
        head(cbor, 5, 5);
        head(cbor, 0, 1);
        head(cbor, 0, 2);
        head(cbor, 0, 3);
        head(cbor, 1, 6);
        head(cbor, 1, 0);
        head(cbor, 0, 1);
        head(cbor, 1, 1);
        bytes(cbor, coordinate(key.getW().getAffineX()));
        head(cbor, 1, 2);
        bytes(cbor, coordinate(key.getW().getAffineY()));
        return cbor.toByteArray();
    }

    private static byte[] coordinate(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, out, 32 - length, length);
        return out;
    }

    /** CBOR: a major type with a value of up to 65535. */
    private static void head(ByteArrayOutputStream out, int major, int value) {
        int type = major << 5;
        if (value < 24) {
            out.write(type | value);
        } else if (value < 256) {
            out.write(type | 24);
            out.write(value);
        } else {
            out.write(type | 25);
            out.write(value >> 8);
            out.write(value);
        }
    }

    private static void bytes(ByteArrayOutputStream out, byte[] value) {
        head(out, 2, value.length);
        out.writeBytes(value);
    }

    private static void text(ByteArrayOutputStream out, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        head(out, 3, utf8.length);
        out.writeBytes(utf8);
    }

    /** The authenticator data: the hash of the relying party id, the flags and a counter. */
    public static byte[] authenticatorData(String rpId, int flags) {
        return ByteBuffer.allocate(37).put(sha256(rpId.getBytes(StandardCharsets.UTF_8))).put((byte) flags).putInt(1)
                .array();
    }

    /** The client data JSON a browser writes for a ceremony of this type, challenge and origin. */
    public static String clientData(String type, String challenge, String origin) {
        return "{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin
                + "\",\"crossOrigin\":false}";
    }

    /** The attestation object (format none) of this credential for a relying party, base64url. */
    public String attestationObject(String rpId) {
        ByteBuffer authenticator = ByteBuffer.allocate(37 + 16 + 2 + credentialId.length + coseKey().length);
        authenticator.put(sha256(rpId.getBytes(StandardCharsets.UTF_8))).put((byte) ATTESTED).putInt(0)
                .put(new byte[16]).putShort((short) credentialId.length).put(credentialId).put(coseKey());
        ByteArrayOutputStream cbor = new ByteArrayOutputStream();
        head(cbor, 5, 3);
        text(cbor, "fmt");
        text(cbor, "none");
        text(cbor, "attStmt");
        head(cbor, 5, 0);
        text(cbor, "authData");
        bytes(cbor, Arrays.copyOf(authenticator.array(), authenticator.position()));
        return base64url(cbor.toByteArray());
    }

    /** What a browser sends to register this credential, for the challenge the server issued. */
    public Registration registration(String challenge, String rpId, String origin) {
        return new Registration(credentialId(), attestationObject(rpId),
                base64url(clientData("webauthn.create", challenge, origin).getBytes(StandardCharsets.UTF_8)));
    }

    /** A good assertion over the challenge for the relying party and the origin (user present and verified). */
    public Assertion assertion(String challenge, String rpId, String origin) {
        return assertion(clientData("webauthn.get", challenge, origin), authenticatorData(rpId, PRESENT_AND_VERIFIED));
    }

    /** The assertion over exactly this client data and this authenticator data (a test bends them). */
    public Assertion assertion(String clientDataJson, byte[] authenticatorData) {
        byte[] client = clientDataJson.getBytes(StandardCharsets.UTF_8);
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(pair.getPrivate());
            signer.update(authenticatorData);
            signer.update(sha256(client));
            return new Assertion(credentialId(), base64url(authenticatorData), base64url(client),
                    base64url(signer.sign()));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
