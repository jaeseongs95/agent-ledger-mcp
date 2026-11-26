// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.attachment;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.agentledger.domain.error.InfrastructureCode;
import org.agentledger.domain.error.InfrastructureException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * The key files of the attachments: one file of 32 random bytes (AES-256) per key, named by its key id, in the key directory
 * (outside the attachment directory, private to the service account). A file is written with the key that
 * {@code ledger.attachments.active-key-id} names and read with the key its row names, so a key is replaced by creating a new
 * key file and changing the setting; an old key stays while rows use it. A key is never logged, stored in the database or put
 * into a message: a failure names the key id only.
 */
@Component
@ConditionalOnAttachments
public class AttachmentKeys {

    /** A key and its id; the text of the record never shows the key. */
    public record Key(String id, SecretKey secret) {

        @Override
        public String toString() {
            return "Key[" + id + ", hidden]";
        }
    }

    private static final int KEY_BYTES = 32;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final String PREFIX = "key-";

    private final AttachmentProperties properties;
    private final PrivateFiles files;
    private final SecureRandom random;

    public AttachmentKeys(AttachmentProperties properties, PrivateFiles files,
            @Qualifier(AttachmentConfiguration.RANDOM) SecureRandom random) {
        this.properties = properties;
        this.files = files;
        this.random = random;
    }

    /** The key that new uploads are written with. */
    public Key active() {
        return byId(properties.activeKeyId());
    }

    /**
     * The key of a key id (the name of its file).
     *
     * @throws InfrastructureException ARTIFACT_KEY_MISSING when there is no usable key: the file is not there, is empty or has not
     *         the length of a key; ARTIFACT_STORAGE_UNSAFE when the path is not a regular file (a link is followed) or the key
     *         folder is not a directory. Who else may reach the file or its folder is not looked at.
     */
    public Key byId(String keyId) {
        Path file = fileOf(keyId);
        if (!Files.exists(file)) {
            throw new InfrastructureException(InfrastructureCode.ARTIFACT_KEY_MISSING, List.of(keyId));
        }
        files.requireDirectory(properties.keyDirectory());
        byte[] bytes = new byte[KEY_BYTES];
        try (FileChannel channel = files.openRead(file)) {
            // no usable key (edition 11 of the design): an empty file or a file that has not the length of a key is a missing key
            if (channel.size() != KEY_BYTES) {
                throw new InfrastructureException(InfrastructureCode.ARTIFACT_KEY_MISSING, List.of(keyId));
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new InfrastructureException(InfrastructureCode.ARTIFACT_KEY_MISSING, List.of(keyId));
                }
            }
            return new Key(keyId, new SecretKeySpec(bytes, "AES"));
        } catch (IOException failure) {
            throw new InfrastructureException(InfrastructureCode.ARTIFACT_STORAGE_IO_ERROR,
                    List.of(failure.getClass().getSimpleName()), failure);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * Creates a new key file (owner only, 256 random bits) and returns its key id. The setting {@code active-key-id} is not
     * changed: the owner does that, and until then the key is only kept.
     */
    public String create() {
        files.ensureDirectory(properties.keyDirectory());
        byte[] bytes = new byte[KEY_BYTES];
        random.nextBytes(bytes);
        String keyId = PREFIX + HexFormat.of().formatHex(randomBytes(8));
        Path file = fileOf(keyId);
        try {
            files.createFile(file);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            return keyId;
        } catch (IOException failure) {
            throw new InfrastructureException(InfrastructureCode.ARTIFACT_STORAGE_IO_ERROR,
                    List.of(failure.getClass().getSimpleName()), failure);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * The start check of the keys: the key directory, when it is there, is a directory (a file where it belongs is
     * ARTIFACT_STORAGE_UNSAFE), and every key id in the list has its key file (the active key too). The server does not make the key
     * folder or a key: {@code prepare-host} makes the folder and {@code setup} the key (design 4.3), so a missing key folder is "no
     * usable key" (ARTIFACT_KEY_MISSING naming the key id only), the answer of a missing key file, and a start that stops.
     */
    public void requirePresent(Collection<String> keyIds) {
        if (Files.exists(properties.keyDirectory())) {
            files.requireDirectory(properties.keyDirectory());
        }
        byId(properties.activeKeyId());
        for (String keyId : keyIds) {
            byId(keyId);
        }
    }

    private byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        random.nextBytes(bytes);
        return bytes;
    }

    private Path fileOf(String keyId) {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new InfrastructureException(InfrastructureCode.ARTIFACT_KEY_MISSING, List.of("(not a key id)"));
        }
        return properties.keyDirectory().resolve(keyId);
    }
}
