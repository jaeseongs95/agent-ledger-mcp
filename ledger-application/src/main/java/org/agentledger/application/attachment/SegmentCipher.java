// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.attachment;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import org.agentledger.domain.error.InfrastructureCode;
import org.agentledger.domain.error.InfrastructureException;
import org.agentledger.domain.value.StorageKey;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Seals and opens one segment of a stored file with AES-256-GCM ({@code AES/GCM/NoPadding} of the JDK, no vendor library). A
 * chunk is one segment, because the chunks arrive in separate requests. A segment on disk is
 *
 * <pre>
 *   4 bytes   length of what follows (IV + ciphertext + tag), big endian
 *  12 bytes   IV, new for every segment
 *   n bytes   ciphertext
 *  16 bytes   tag
 * </pre>
 *
 * so the boundaries are found by the lengths, whatever the sizes of the chunks were. The additional authenticated data is the
 * storage key, the number of the segment and the mark "last segment": a file put under the name of another one, segments in
 * another order, a tail that is cut off, or a length that was changed all fail the tag. The last segment is an empty one that
 * the finish appends, marked "last"; a file without it is a truncated file.
 */
@Component
@ConditionalOnAttachments
public class SegmentCipher {

    public static final int LENGTH_BYTES = 4;
    public static final int IV_BYTES = 12;
    public static final int TAG_BYTES = 16;
    /** What a segment adds to its plain bytes: the length, the IV and the tag. */
    public static final int OVERHEAD = LENGTH_BYTES + IV_BYTES + TAG_BYTES;

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_BITS = TAG_BYTES * 8;

    private final SecureRandom random;

    public SegmentCipher(@Qualifier(AttachmentConfiguration.RANDOM) SecureRandom random) {
        this.random = random;
    }

    /** The bytes of the whole segment (length, IV, ciphertext, tag) for {@code plain}. */
    public byte[] seal(AttachmentKeys.Key key, StorageKey storage, long segment, boolean last, byte[] plain) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key.secret(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(storage, segment, last));
            byte[] sealed = cipher.doFinal(plain);
            ByteBuffer out = ByteBuffer.allocate(LENGTH_BYTES + IV_BYTES + sealed.length);
            out.putInt(IV_BYTES + sealed.length).put(iv).put(sealed);
            return out.array();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("AES-GCM is mandatory on every JVM and the key is valid", failure);
        }
    }

    /**
     * The plain bytes of a segment.
     *
     * @param body the bytes after the length: IV, ciphertext and tag
     * @throws InfrastructureException ARTIFACT_CORRUPTED when the tag does not match (a changed byte, another file, another
     *         position, a cut-off tail) or the body is too short to be a segment
     */
    public byte[] open(AttachmentKeys.Key key, StorageKey storage, long segment, boolean last, byte[] body) {
        if (body.length < IV_BYTES + TAG_BYTES) {
            throw corrupted();
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key.secret(), new GCMParameterSpec(TAG_BITS, body, 0, IV_BYTES));
            cipher.updateAAD(aad(storage, segment, last));
            return cipher.doFinal(body, IV_BYTES, body.length - IV_BYTES);
        } catch (GeneralSecurityException failure) {
            throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of(), failure);
        }
    }

    private static byte[] aad(StorageKey storage, long segment, boolean last) {
        byte[] name = storage.hex().getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(name.length + Long.BYTES + 1).put(name).putLong(segment).put((byte) (last ? 1 : 0)).array();
    }

    private static InfrastructureException corrupted() {
        return new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
    }
}
