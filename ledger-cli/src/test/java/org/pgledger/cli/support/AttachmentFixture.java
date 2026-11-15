package org.pgledger.cli.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.pgledger.application.attachment.AttachmentKeys;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.AttachmentStore;
import org.pgledger.application.attachment.PrivateFiles;
import org.pgledger.application.attachment.SegmentCipher;
import org.pgledger.application.attachment.StorageKeys;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.StorageKey;
import org.springframework.util.unit.DataSize;

/**
 * The attachment folder and the key folder of a CLI test, made with the real storage classes of the application (the ones the
 * commands use), so that a test can put files and keys where a server would have put them and look at what a command did to
 * them. The folders are {@code <root>/data} and {@code <root>/keys}; the first key is created at construction and is the key of
 * the files this fixture writes.
 */
public final class AttachmentFixture {

    /** A finished file as the server would have stored it: its storage key, the size and the SHA-256 of the plain bytes. */
    public record Stored(StorageKey key, long size, Sha256 sha256) {
    }

    private final Path directory;
    private final Path keyDirectory;
    private final String keyId;
    private final AttachmentStore store;
    private final StorageKeys storageKeys;
    private final PrivateFiles files;

    private AttachmentFixture(Path directory, Path keyDirectory, String keyId, AttachmentStore store, StorageKeys storageKeys,
            PrivateFiles files) {
        this.directory = directory;
        this.keyDirectory = keyDirectory;
        this.keyId = keyId;
        this.store = store;
        this.storageKeys = storageKeys;
        this.files = files;
    }

    /** The folders below {@code root} (which exists) with the first key. */
    public static AttachmentFixture in(Path root) {
        Path directory = root.resolve("data");
        Path keyDirectory = root.resolve("keys");
        SecureRandom random = new SecureRandom();
        PrivateFiles files = new PrivateFiles();
        String first = new AttachmentKeys(properties(directory, keyDirectory, "first"), files, random).create();
        AttachmentProperties properties = properties(directory, keyDirectory, first);
        AttachmentKeys keys = new AttachmentKeys(properties, files, random);
        AttachmentStore store = new AttachmentStore(properties, files, keys, new SegmentCipher(random));
        store.prepare();
        return new AttachmentFixture(directory, keyDirectory, first, store, new StorageKeys(random), files);
    }

    private static AttachmentProperties properties(Path directory, Path keyDirectory, String activeKeyId) {
        return new AttachmentProperties(directory, keyDirectory, activeKeyId, DataSize.ofMegabytes(64),
                DataSize.ofMegabytes(512), DataSize.ofGigabytes(10), 4, Duration.ofMinutes(10), Duration.ofHours(24),
                Duration.ofMinutes(1));
    }

    public Path directory() {
        return directory;
    }

    public Path keyDirectory() {
        return keyDirectory;
    }

    /** The id of the first key, the one the files of this fixture are written with. */
    public String keyId() {
        return keyId;
    }

    /** A storage key that no file has. */
    public StorageKey newStorageKey() {
        return storageKeys.next();
    }

    /** Writes a finished file (one chunk and the end mark) with the first key, as the upload of a server leaves it. */
    public Stored write(byte[] body) {
        StorageKey key = storageKeys.next();
        Sha256 sha256 = Sha256.ofBytes(body);
        long stored = store.append(key, keyId, 0, 0, body, sha256);
        store.finish(key, keyId, stored, 1, body.length, sha256);
        return new Stored(key, body.length, sha256);
    }

    /** Writes a file that was never finished (no end mark): it does not pass the check of an attachment. */
    public StorageKey writeUnfinished(byte[] body) {
        StorageKey key = storageKeys.next();
        store.append(key, keyId, 0, 0, body, Sha256.ofBytes(body));
        return key;
    }

    /** What the whole file holds after every tag was checked (the key file of {@code withKeyId} has to be in the key folder). */
    public AttachmentStore.Content verify(StorageKey key, String withKeyId) {
        return store.verify(key, withKeyId);
    }

    /** The place of a file in the attachment folder. */
    public Path fileOf(StorageKey key) {
        return directory.resolve(key.folder()).resolve(key.hex());
    }

    /** The place of a file in the quarantine folder. */
    public Path quarantinedOf(StorageKey key) {
        return directory.resolve("orphans").resolve(key.hex());
    }

    /** Sets the time of the last write of a file in its place. */
    public void touch(StorageKey key, Instant lastModified) {
        try {
            Files.setLastModifiedTime(fileOf(key), FileTime.from(lastModified));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The number of files in the quarantine folder. */
    public int quarantined() {
        try (Stream<Path> found = Files.list(directory.resolve("orphans"))) {
            return (int) found.count();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The names (the key ids) of the key files. */
    public Set<String> keyFileNames() {
        try (Stream<Path> found = Files.list(keyDirectory)) {
            return new TreeSet<>(found.map(path -> path.getFileName().toString()).toList());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The bytes of a key file (a test of the secrecy of the output compares them with it). */
    public byte[] keyBytes(String id) {
        try {
            return Files.readAllBytes(keyDirectory.resolve(id));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Requires that only the account of the test can use the key file (the rule of the product, one place). */
    public void requirePrivateKeyFile(String id) {
        files.requireFile(keyDirectory.resolve(id));
    }

    /** The key ids that are in the key folder and are not in {@code known}. */
    public List<String> newKeys(Set<String> known) {
        return keyFileNames().stream().filter(name -> !known.contains(name)).toList();
    }
}
