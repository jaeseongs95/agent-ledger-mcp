// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.attachment;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.pgledger.attestation.Sha256Hex;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.StorageKey;
import org.springframework.stereotype.Component;

/**
 * The files of the bodies: the one implementation of storing, appending, finishing, reading, checking, quarantining and removing
 * (common design 4.1 to 4.4). Everything here is file I/O. It is called by the entry points, never from inside a transaction
 * (principle 4.4): the use case first does its file work and then hands the result to one service method.
 *
 * <p>A file is {@code <directory>/<first two characters of the storage key>/<storage key>}: no extension, no original name. It is a
 * run of segments ({@link SegmentCipher}); a chunk is one segment; the finish appends the empty segment marked "last".
 *
 * <p>Appending (design 4.4): the one byte past the end of the file is locked with {@code tryLock(Long.MAX_VALUE - 1, 1, false)} and
 * never waited for; inside the lock the length
 * of the file is compared with the bytes the database knows of. Equal: the segment is appended and forced. Longer: the tail is a
 * segment that was appended and not recorded (a request that lost the race, or one whose transaction failed); it is opened with the
 * number of this chunk and kept when it holds the same bytes, and the chunk is a conflict when it holds others. Shorter: the
 * file is damaged. The tail is never cut off, because it may be the segment of a request that is still running.
 */
@Component
@ConditionalOnAttachments
public class AttachmentStore {

    /** One page of a body: the bytes, where they start and the size of the whole body. */
    public record Page(byte[] data, long offset, long totalSize) {
    }

    /** What the whole file holds when every segment has been opened: the size and the SHA-256 of the plain bytes. */
    public record Content(long sizeBytes, Sha256 sha256) {
    }

    /** A file of the folder: its storage key and when it was last written. */
    public record StoredFile(StorageKey key, Instant lastModified) {
    }

    private static final String QUARANTINE = "orphans";
    private static final int MAX_SEGMENT = AttachmentProperties.CHUNK_BYTES + SegmentCipher.OVERHEAD;
    /**
     * The one byte that appenders lock: past the end of any file. A lock of the data would make the reads of the file wait on
     * Windows (its locks are mandatory); a lock past the end keeps the appenders of one upload apart and blocks no read.
     */
    private static final long LOCK_POSITION = Long.MAX_VALUE - 1;
    private static final String READ_ONLY_MODE = "r--------";
    private static final String WRITABLE_MODE = "rw-------";

    private final AttachmentProperties properties;
    private final PrivateFiles files;
    private final AttachmentKeys keys;
    private final SegmentCipher cipher;

    public AttachmentStore(AttachmentProperties properties, PrivateFiles files, AttachmentKeys keys, SegmentCipher cipher) {
        this.properties = properties;
        this.files = files;
        this.keys = keys;
        this.cipher = cipher;
    }

    // ------------------------------------------------------------------------------------------------ start

    /** Makes the attachment directory and the quarantine folder, owner-only, when they are missing and requires both to be directories. */
    public void prepare() {
        files.ensureDirectory(properties.directory());
        files.ensureDirectory(properties.directory().resolve(QUARANTINE));
    }

    // ------------------------------------------------------------------------------------------------ disk space

    /**
     * Refuses the write when the usable space of the disk of the attachment directory, less the bytes to write, is below
     * {@code disk-margin-bytes} (the comparison is {@code usable - requested < margin}). This is not a limit of the product: the owner
     * has to free the disk (ARTIFACT_STORAGE_FULL, not retryable). A disk that cannot be read is no "full" disk but a failure of the
     * storage (ARTIFACT_STORAGE_IO_ERROR).
     *
     * <p>The directory is made first when it is missing (owner-only), the same rule as every write ({@code prepareFolder}):
     * a begin may come before the first write has made the folder, and the file store of a path that does not exist cannot be read. A
     * folder that cannot be made (its parent is missing, no right) or is a file is still the failure it is
     * (ARTIFACT_STORAGE_IO_ERROR, ARTIFACT_STORAGE_UNSAFE); nothing is measured on another folder instead. Who else may reach an
     * existing folder is not looked at.
     */
    public void requireDiskSpace(long requestedBytes) {
        files.ensureDirectory(properties.directory());
        long usable;
        try {
            usable = Files.getFileStore(properties.directory()).getUsableSpace();
        } catch (IOException failure) {
            throw failed(failure);
        }
        long margin = properties.diskMarginBytes().toBytes();
        if (usable - requestedBytes < margin) {
            throw new ConflictException(ConflictCode.ARTIFACT_STORAGE_FULL,
                    List.of(Long.toString(requestedBytes), Long.toString(usable), Long.toString(margin)))
                    .withDetails(Map.of("requestedBytes", List.of(Long.toString(requestedBytes)),
                            "usableBytes", List.of(Long.toString(usable)), "marginBytes", List.of(Long.toString(margin))));
        }
    }

    // ------------------------------------------------------------------------------------------------ append

    /**
     * Appends the chunk as segment number {@code sequence} (design 4.4 steps 2 and 3).
     *
     * @param storedBytes the length of the file as the database records it
     * @return the length of the file after the chunk: what the database is to record
     * @throws ConflictException ARTIFACT_BUSY when the lock is held (nothing waits); ARTIFACT_CHUNK_CONFLICT when the tail holds
     *         another chunk
     * @throws InfrastructureException ARTIFACT_CORRUPTED when the file is shorter than the database says
     */
    public long append(StorageKey key, String keyId, long storedBytes, long sequence, byte[] plain, Sha256 chunkSha256) {
        if (plain == null || plain.length < 1 || plain.length > AttachmentProperties.CHUNK_BYTES) {
            throw ValidationException.invalid("data", ValidationReason.OUT_OF_RANGE, "1",
                    Integer.toString(AttachmentProperties.CHUNK_BYTES));
        }
        AttachmentKeys.Key aes = keys.byId(keyId);
        Path file = pathOf(key);
        try {
            prepareFolder(key);
            // A file that is closed is closed by its data (the valid end segment right after the recorded bytes), never by its
            // permission bit: the finish made it read only, so opening it for writing would fail with an I/O error where the design
            // says "conflict" (4.4 step 3: no overwrite). The end segment is checked, so a file that is merely read only is not taken
            // for a closed one.
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && endSegmentFollows(file, key, aes, storedBytes, sequence)) {
                throw conflict();
            }
            try (FileChannel channel = files.openReadWrite(file); FileLock lock = channel.tryLock(LOCK_POSITION, 1, false)) {
                if (lock == null) {
                    throw busy(null);
                }
                long size = channel.size();
                if (size == storedBytes) {
                    requireDiskSpace(plain.length + (long) SegmentCipher.OVERHEAD);
                    byte[] segment = cipher.seal(aes, key, sequence, false, plain);
                    writeFully(channel, ByteBuffer.wrap(segment), size);
                    channel.force(true);
                    return size + segment.length;
                }
                if (size > storedBytes) {
                    requireSameTail(channel, storedBytes, size, aes, key, sequence, chunkSha256);
                    return size;
                }
                throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
            }
        } catch (OverlappingFileLockException failure) {
            throw busy(failure);
        } catch (IOException failure) {
            throw failed(failure);
        }
    }

    /** True when exactly the end segment of number {@code sequence} follows {@code storedBytes}: the file is closed. Reads only. */
    private boolean endSegmentFollows(Path file, StorageKey key, AttachmentKeys.Key aes, long storedBytes, long sequence)
            throws IOException {
        try (FileChannel probe = files.openRead(file)) {
            return probe.size() - storedBytes == SegmentCipher.OVERHEAD && isEndMark(probe, storedBytes, aes, key, sequence);
        }
    }

    private void requireSameTail(FileChannel channel, long from, long size, AttachmentKeys.Key aes, StorageKey key,
            long sequence, Sha256 chunkSha256) throws IOException {
        long tailLength = size - from;
        if (tailLength > MAX_SEGMENT) {
            throw conflict();
        }
        ByteBuffer tail = ByteBuffer.allocate((int) tailLength);
        readFully(channel, tail, from);
        tail.flip();
        int length = tail.getInt();
        if (length != tail.remaining()) {
            throw conflict();
        }
        byte[] body = new byte[length];
        tail.get(body);
        byte[] plain;
        try {
            plain = cipher.open(aes, key, sequence, false, body);
        } catch (InfrastructureException notThisChunk) {
            // a tail that does not open with the number of this chunk is the segment of another chunk (or damaged): a conflict
            throw new ConflictException(ConflictCode.ARTIFACT_CHUNK_CONFLICT, List.of(), notThisChunk);
        }
        if (!Objects.equals(Sha256.ofBytes(plain), chunkSha256)) {
            throw conflict();
        }
    }

    // ------------------------------------------------------------------------------------------------ finish

    /**
     * Closes the file (design 4.4, finish): inside the lock, with the file as long as the database says, every segment is opened and
     * the size and the SHA-256 of the plain bytes are checked against the declared ones; only then the end mark (the empty segment marked
     * "last") is appended and the file is made read only. A finish that fails before that leaves the bytes as they were, so a retry or
     * the chunks that are missing can still follow. A finish that is repeated after it succeeded (its transaction failed) finds the
     * valid end segment, checks again and changes nothing. Whether the file is closed is read from its data and never from its permission
     * bit; making the file read only after the finish is a second guard.
     *
     * @return the length of the finished file
     * @throws ConflictException ARTIFACT_INCOMPLETE when a chunk was appended and is not recorded or bytes are missing;
     *         ARTIFACT_HASH_MISMATCH when the size or the SHA-256 is not the declared one; ARTIFACT_BUSY when the lock is held
     */
    public long finish(StorageKey key, String keyId, long storedBytes, long nextSequence, long declaredSize,
            Sha256 declaredSha256) {
        AttachmentKeys.Key aes = keys.byId(keyId);
        Path file = pathOf(key);
        try {
            prepareFolder(key);
            // Whether the file is closed is decided from its data (is its last segment the valid end segment?), never from its
            // permission bit: a process that runs as root can write a read-only file, and the bit is only a second guard.
            boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
            boolean sealed = exists && endSegmentPresent(file, key, aes, storedBytes, nextSequence);
            if (exists && !sealed) {
                // the file is open for more bytes whatever its bit says: this class owns it and makes it writable
                makeWritable(file);
            }
            try (FileChannel channel = sealed ? files.openRead(file) : files.openReadWrite(file);
                    FileLock lock = channel.tryLock(LOCK_POSITION, 1, sealed)) {
                if (lock == null) {
                    throw busy(null);
                }
                long size = channel.size();
                if (size < storedBytes) {
                    throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
                }
                boolean marked = sealed;
                if (!sealed && size > storedBytes) {
                    // a finish that ran at the same moment may have closed the file after the first look: decide again, inside the lock
                    marked = size - storedBytes == SegmentCipher.OVERHEAD
                            && isEndMark(channel, storedBytes, aes, key, nextSequence);
                    if (!marked) {
                        throw new ConflictException(ConflictCode.ARTIFACT_INCOMPLETE, List.of("a chunk is not recorded"));
                    }
                }
                Sha256Hex.Running digest = Sha256Hex.running();
                long plainBytes = walk(channel, storedBytes, nextSequence, aes, key, digest);
                if (plainBytes < declaredSize) {
                    throw new ConflictException(ConflictCode.ARTIFACT_INCOMPLETE,
                            List.of("received " + plainBytes + " of " + declaredSize + " bytes"));
                }
                if (plainBytes != declaredSize) {
                    throw new ConflictException(ConflictCode.ARTIFACT_HASH_MISMATCH,
                            List.of("size " + plainBytes + ", declared " + declaredSize));
                }
                if (!Objects.equals(digest.finish(), declaredSha256.hex())) {
                    throw new ConflictException(ConflictCode.ARTIFACT_HASH_MISMATCH, List.of("SHA-256"));
                }
                if (marked) {
                    makeReadOnly(file);
                    return size;
                }
                byte[] endMark = cipher.seal(aes, key, nextSequence, true, new byte[0]);
                writeFully(channel, ByteBuffer.wrap(endMark), size);
                channel.force(true);
                makeReadOnly(file);
                return size + endMark.length;
            }
        } catch (OverlappingFileLockException failure) {
            throw busy(failure);
        } catch (IOException failure) {
            throw failed(failure);
        }
    }

    /**
     * Whether the file ends with the valid end segment (empty plain bytes, the "last" mark, the number {@code nextSequence}, the tag of
     * this storage key) right after the {@code storedBytes} the database knows of. A file that is exactly as long as the database says has
     * none; a longer tail that is not the end segment is a chunk that was not recorded (ARTIFACT_INCOMPLETE); a shorter file is damaged.
     */
    private boolean endSegmentPresent(Path file, StorageKey key, AttachmentKeys.Key aes, long storedBytes, long nextSequence)
            throws IOException {
        try (FileChannel probe = files.openRead(file)) {
            long size = probe.size();
            if (size < storedBytes) {
                throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
            }
            if (size == storedBytes) {
                return false;
            }
            if (size - storedBytes == SegmentCipher.OVERHEAD && isEndMark(probe, storedBytes, aes, key, nextSequence)) {
                return true;
            }
            throw new ConflictException(ConflictCode.ARTIFACT_INCOMPLETE, List.of("a chunk is not recorded"));
        }
    }

    private boolean isEndMark(FileChannel channel, long from, AttachmentKeys.Key aes, StorageKey key, long sequence)
            throws IOException {
        ByteBuffer tail = ByteBuffer.allocate(SegmentCipher.OVERHEAD);
        readFully(channel, tail, from);
        tail.flip();
        if (tail.getInt() != SegmentCipher.IV_BYTES + SegmentCipher.TAG_BYTES) {
            return false;
        }
        byte[] body = new byte[tail.remaining()];
        tail.get(body);
        try {
            return cipher.open(aes, key, sequence, true, body).length == 0;
        } catch (InfrastructureException notTheEndMark) {
            // a tail of the size of the end mark that does not open as the end mark is a chunk that was not recorded
            return false;
        }
    }

    /**
     * Opens the segments {@code 0 .. count - 1} that fill {@code [0, limit)}, feeds the plain bytes to the digest and returns their
     * number. The file must hold exactly those segments: another count, a length that does not fit or a tag that does not match is
     * damage.
     */
    private long walk(FileChannel channel, long limit, long count, AttachmentKeys.Key aes, StorageKey key,
            Sha256Hex.Running digest) throws IOException {
        long position = 0;
        long plainBytes = 0;
        for (long index = 0; index < count; index++) {
            byte[] body = readBody(channel, position, limit);
            byte[] plain = cipher.open(aes, key, index, false, body);
            digest.update(plain, 0, plain.length);
            plainBytes += plain.length;
            position += SegmentCipher.LENGTH_BYTES + body.length;
        }
        if (position != limit) {
            throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
        }
        return plainBytes;
    }

    // ------------------------------------------------------------------------------------------------ read and check

    /**
     * A page of the plain body: the segments that cover {@code [offset, offset + maxBytes)} are opened and their tags checked; the
     * lengths are followed to the end of the file, where the end mark has to be the last segment, so a cut-off tail, an end mark that
     * was removed and a swapped segment are found.
     *
     * @throws InfrastructureException ARTIFACT_CORRUPTED when the file is missing, its structure or a tag does not hold
     */
    public Page read(StorageKey key, String keyId, long offset, int maxBytes) {
        if (offset < 0) {
            throw ValidationException.invalid("offsetBytes", ValidationReason.MUST_NOT_BE_NEGATIVE);
        }
        if (maxBytes < 1 || maxBytes > AttachmentProperties.CHUNK_BYTES) {
            throw ValidationException.invalid("maxBytes", ValidationReason.OUT_OF_RANGE, "1",
                    Integer.toString(AttachmentProperties.CHUNK_BYTES));
        }
        AttachmentKeys.Key aes = keys.byId(keyId);
        Path file = pathOf(key);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
        }
        try (FileChannel channel = files.openRead(file)) {
            long size = channel.size();
            long position = 0;
            long plainStart = 0;
            long index = 0;
            long wantedEnd = offset + maxBytes;
            ByteBuffer collected = ByteBuffer.allocate(maxBytes);
            while (position < size) {
                byte[] body = readBody(channel, position, size);
                long next = position + SegmentCipher.LENGTH_BYTES + body.length;
                boolean last = next == size;
                long plainLength = body.length - SegmentCipher.IV_BYTES - SegmentCipher.TAG_BYTES;
                boolean overlaps = !last && plainStart + plainLength > offset && plainStart < wantedEnd;
                if (last || overlaps) {
                    byte[] plain = cipher.open(aes, key, index, last, body);
                    if (last) {
                        if (plain.length != 0) {
                            throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
                        }
                    } else {
                        int from = (int) Math.max(0, offset - plainStart);
                        int to = (int) Math.min(plain.length, wantedEnd - plainStart);
                        collected.put(plain, from, to - from);
                    }
                }
                plainStart += plainLength;
                position = next;
                index++;
            }
            if (size == 0) {
                throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
            }
            byte[] data = new byte[collected.position()];
            collected.flip();
            collected.get(data);
            return new Page(data, offset, plainStart);
        } catch (IOException failure) {
            throw failed(failure);
        }
    }

    /** Opens every segment of the file and returns the size and SHA-256 of the plain bytes; any damage is ARTIFACT_CORRUPTED. */
    public Content verify(StorageKey key, String keyId) {
        AttachmentKeys.Key aes = keys.byId(keyId);
        Path file = pathOf(key);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
        }
        try (FileChannel channel = files.openRead(file)) {
            long size = channel.size();
            long position = 0;
            long index = 0;
            long plainBytes = 0;
            Sha256Hex.Running digest = Sha256Hex.running();
            while (position < size) {
                byte[] body = readBody(channel, position, size);
                long next = position + SegmentCipher.LENGTH_BYTES + body.length;
                boolean last = next == size;
                byte[] plain = cipher.open(aes, key, index, last, body);
                if (last) {
                    if (plain.length != 0) {
                        throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
                    }
                } else {
                    digest.update(plain, 0, plain.length);
                    plainBytes += plain.length;
                }
                position = next;
                index++;
            }
            if (size == 0) {
                throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
            }
            return new Content(plainBytes, Sha256.of(digest.finish()));
        } catch (IOException failure) {
            throw failed(failure);
        }
    }

    // ------------------------------------------------------------------------------------------------ rekey

    /**
     * Writes the body again under another key and another storage key (design 4.3, rekey): every segment of the old file is opened
     * (tag, number, end mark), sealed again for the new storage key with the new key, and written to a new file that is forced and
     * made read only. The old file is not touched. The row that makes the new file the current one is added by a service method
     * afterwards; a failure before that leaves the old row valid and the new file as a file without a row (the partial file is removed
     * when it can be).
     *
     * @throws InfrastructureException ARTIFACT_CORRUPTED when the old file does not pass; ARTIFACT_STORAGE_IO_ERROR when the new
     *         file cannot be written (it is never overwritten)
     */
    public void rekey(StorageKey from, String fromKeyId, StorageKey to, String toKeyId) {
        AttachmentKeys.Key oldKey = keys.byId(fromKeyId);
        AttachmentKeys.Key newKey = keys.byId(toKeyId);
        Path source = pathOf(from);
        Path target = pathOf(to);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
        }
        try {
            prepareFolder(to);
            files.createFile(target);
        } catch (IOException failure) {
            throw failed(failure);
        }
        try (FileChannel in = files.openRead(source); FileChannel out = files.openReadWrite(target)) {
            long size = in.size();
            long position = 0;
            long written = 0;
            long index = 0;
            while (position < size) {
                byte[] body = readBody(in, position, size);
                long next = position + SegmentCipher.LENGTH_BYTES + body.length;
                boolean last = next == size;
                byte[] plain = cipher.open(oldKey, from, index, last, body);
                if (last && plain.length != 0) {
                    throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
                }
                byte[] sealed = cipher.seal(newKey, to, index, last, plain);
                writeFully(out, ByteBuffer.wrap(sealed), written);
                written += sealed.length;
                position = next;
                index++;
            }
            if (size == 0) {
                throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
            }
            out.force(true);
            makeReadOnly(target);
        } catch (IOException | InfrastructureException failure) {
            try {
                remove(to);
            } catch (InfrastructureException cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof IOException io) {
                throw failed(io);
            }
            throw (InfrastructureException) failure;
        }
    }

    // ------------------------------------------------------------------------------------------------ the folder

    /** True when the file of the key is in its place (not in the quarantine). */
    public boolean exists(StorageKey key) {
        return Files.isRegularFile(pathOf(key), LinkOption.NOFOLLOW_LINKS);
    }

    /** Every file of the attachment folders (the quarantine is not listed) with its time of last write. */
    public List<StoredFile> list() {
        List<StoredFile> found = new ArrayList<>();
        Path root = properties.directory();
        try (Stream<Path> folders = Files.list(root)) {
            for (Path folder : folders.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                if (QUARANTINE.equals(folder.getFileName().toString())) {
                    continue;
                }
                found.addAll(filesOf(folder));
            }
        } catch (IOException failure) {
            throw failed(failure);
        }
        return found;
    }

    /** Every file of the quarantine with its time of last write. */
    public List<StoredFile> listQuarantined() {
        try {
            return filesOf(properties.directory().resolve(QUARANTINE));
        } catch (IOException failure) {
            throw failed(failure);
        }
    }

    private List<StoredFile> filesOf(Path folder) throws IOException {
        List<StoredFile> found = new ArrayList<>();
        try (Stream<Path> entries = Files.list(folder)) {
            for (Path entry : entries.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name = entry.getFileName().toString();
                if (StorageKey.isFormat(name)) {
                    found.add(new StoredFile(StorageKey.of(name),
                            Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toInstant()));
                }
            }
        }
        return found;
    }

    /** Moves a file that has no row to the quarantine; it is never deleted by the check. */
    public void quarantine(StorageKey key) {
        move(pathOf(key), quarantined(key));
    }

    /** Moves a quarantined file back to its place (its row exists again, for instance after a restore). */
    public void restore(StorageKey key) {
        try {
            prepareFolder(key);
        } catch (IOException failure) {
            throw failed(failure);
        }
        move(quarantined(key), pathOf(key));
    }

    /** Empties the quarantine (the explicit command only) and returns how many files were deleted. */
    public int purgeQuarantine() {
        int deleted = 0;
        for (StoredFile stored : listQuarantined()) {
            try {
                Path file = quarantined(stored.key());
                makeWritable(file);
                if (Files.deleteIfExists(file)) {
                    deleted++;
                }
            } catch (IOException failure) {
                throw failed(failure);
            }
        }
        return deleted;
    }

    /** Deletes the file of an upload whose row is gone; true when there was one. */
    public boolean remove(StorageKey key) {
        Path file = pathOf(key);
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            makeWritable(file);
            return Files.deleteIfExists(file);
        } catch (IOException failure) {
            throw failed(failure);
        }
    }

    // ------------------------------------------------------------------------------------------------ details

    private void move(Path from, Path to) {
        try {
            if (Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
                // a name that exists is never overwritten: the move fails instead
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException failure) {
            throw failed(failure);
        }
    }

    private Path pathOf(StorageKey key) {
        return properties.directory().resolve(key.folder()).resolve(key.hex());
    }

    private Path quarantined(StorageKey key) {
        return properties.directory().resolve(QUARANTINE).resolve(key.hex());
    }

    private void prepareFolder(StorageKey key) throws IOException {
        files.ensureDirectory(properties.directory());
        files.ensureDirectory(properties.directory().resolve(key.folder()));
    }

    /** The bytes after the length of the segment that starts at {@code position}; the segment has to end inside {@code limit}. */
    private static byte[] readBody(FileChannel channel, long position, long limit) throws IOException {
        if (limit - position < SegmentCipher.LENGTH_BYTES) {
            throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
        }
        ByteBuffer prefix = ByteBuffer.allocate(SegmentCipher.LENGTH_BYTES);
        readFully(channel, prefix, position);
        prefix.flip();
        int length = prefix.getInt();
        if (length < SegmentCipher.IV_BYTES + SegmentCipher.TAG_BYTES || length > MAX_SEGMENT - SegmentCipher.LENGTH_BYTES
                || position + SegmentCipher.LENGTH_BYTES + length > limit) {
            throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
        }
        ByteBuffer body = ByteBuffer.allocate(length);
        readFully(channel, body, position + SegmentCipher.LENGTH_BYTES);
        return body.array();
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long at = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, at);
            if (read < 0) {
                throw new InfrastructureException(InfrastructureCode.ARTIFACT_CORRUPTED, List.of());
            }
            at += read;
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long at = position;
        while (buffer.hasRemaining()) {
            at += channel.write(buffer, at);
        }
    }

    private static void makeReadOnly(Path file) throws IOException {
        Set<String> views = file.getFileSystem().supportedFileAttributeViews();
        if (views.contains("posix")) {
            PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                posix.setPermissions(PosixFilePermissions.fromString(READ_ONLY_MODE));
            }
        } else if (views.contains("dos")) {
            DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (dos != null) {
                dos.setReadOnly(true);
            }
        }
    }

    private static void makeWritable(Path file) throws IOException {
        Set<String> views = file.getFileSystem().supportedFileAttributeViews();
        if (views.contains("posix")) {
            PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                posix.setPermissions(PosixFilePermissions.fromString(WRITABLE_MODE));
            }
        } else if (views.contains("dos")) {
            DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (dos != null) {
                dos.setReadOnly(false);
            }
        }
    }

    private static ConflictException busy(Throwable cause) {
        return new ConflictException(ConflictCode.ARTIFACT_BUSY, List.of(), cause);
    }

    private static ConflictException conflict() {
        return new ConflictException(ConflictCode.ARTIFACT_CHUNK_CONFLICT, List.of());
    }

    private static InfrastructureException failed(IOException failure) {
        return new InfrastructureException(InfrastructureCode.ARTIFACT_STORAGE_IO_ERROR,
                List.of(failure.getClass().getSimpleName()), failure);
    }
}
