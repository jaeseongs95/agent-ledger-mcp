package org.pgledger.application.support;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import org.pgledger.application.attachment.AttachmentKeys;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.AttachmentStore;
import org.pgledger.application.attachment.PrivateFiles;
import org.pgledger.application.attachment.SegmentCipher;
import org.pgledger.application.attachment.StorageKeys;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.StorageKey;

/**
 * The attachment storage built by hand on a temporary folder, without Spring and without a database (the unit tests of the storage,
 * the cipher and the keys): the real components, wired the way the context wires them, with one key already made.
 */
public record StoreFixture(Path root, AttachmentProperties properties, PrivateFiles files, AttachmentKeys keys,
        SegmentCipher cipher, AttachmentStore store, StorageKeys storageKeys, String keyId) {

    /** A file that was written and finished: its name, the key it was written with and the plain bytes. */
    public record Written(StorageKey key, String keyId, byte[] plain) {
    }

    public static StoreFixture create() {
        Path root = TestTrees.create("ledger-store-test-");
        Path directory = root.resolve("attachments");
        Path keyDirectory = root.resolve("keys");
        SecureRandom random = new SecureRandom();
        PrivateFiles files = new PrivateFiles();
        String keyId = new AttachmentKeys(AttachmentSettings.properties(directory, keyDirectory, "to-be-created"), files, random)
                .create();
        AttachmentProperties properties = AttachmentSettings.properties(directory, keyDirectory, keyId);
        AttachmentKeys keys = new AttachmentKeys(properties, files, random);
        SegmentCipher cipher = new SegmentCipher(random);
        AttachmentStore store = new AttachmentStore(properties, files, keys, cipher);
        store.prepare();
        return new StoreFixture(root, properties, files, keys, cipher, store, new StorageKeys(random), keyId);
    }

    /** Makes one more key file (the active key does not change). */
    public String newKey() {
        return keys.create();
    }

    /** Appends every chunk as one segment, then finishes the file (end mark, read only), the way the upload flow does. */
    public Written write(String writtenWith, byte[]... chunks) {
        StorageKey key = storageKeys.next();
        long stored = 0;
        long sequence = 0;
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            stored = store.append(key, writtenWith, stored, sequence, chunk, Sha256.ofBytes(chunk));
            sequence++;
            all.writeBytes(chunk);
        }
        byte[] plain = all.toByteArray();
        store.finish(key, writtenWith, stored, sequence, plain.length, Sha256.ofBytes(plain));
        return new Written(key, writtenWith, plain);
    }

    /** Where the file of the key is on the disk. */
    public Path fileOf(StorageKey key) {
        return properties.directory().resolve(key.folder()).resolve(key.hex());
    }

    /** Removes the temporary folder. */
    public void close() {
        TestTrees.delete(root);
    }
}
