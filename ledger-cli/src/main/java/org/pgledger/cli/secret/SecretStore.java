package org.pgledger.cli.secret;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;

import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;

/**
 * Stores one secret per file in a directory that is created owner-only. The protection is given at creation and chosen by
 * the file system, never by the name of the operating system ({@link SecretProtection}): POSIX permissions 0700 (directory)
 * and 0600 (file), or on a file system with ACLs an ACL with an entry for the owner (everything) and one for the service account LocalService (read; the
 * services read what the installing user stores). A file system
 * with neither is refused for a creation (NON_POSIX_FILESYSTEM): nothing is stored with the default protection.
 *
 * <p>Reading and replacing what exists does not look at who may reach it. The personal edition trusts the whole PC: an
 * existing directory or file with wider access (an ACL entry for Users or Authenticated Users, which a folder on a data
 * volume inherits by default on Windows; group or other bits), another owner, or a link (it is followed) is used as it is.
 * Only a path of the wrong kind is refused: a file where the directory belongs, or a directory or another type where the
 * secret file belongs (UNEXPECTED_FILE_TYPE), and a stored secret whose file is empty (EMPTY_SECRET_FILE). There is no setting
 * that turns a check on.
 */
public class SecretStore {

    /** Temporary files start with two dots: configtree ignores those (SEC-015), and no valid secret name starts with a dot. */
    static final String TEMPORARY_PREFIX = "..secret-";
    static final String TEMPORARY_SUFFIX = ".tmp";

    private final Path directory;
    private final Path floor;
    private final ProcessUser processUser;

    /** A store of the secret directory itself: only the directory is made, its parent belongs to the installation. */
    SecretStore(SecretsProperties properties, ProcessUser processUser) {
        this(properties.directory(), properties.directory().getParent(), processUser);
    }

    /**
     * A store of one directory below {@code floor}: {@code floor} has to exist (it belongs to the installation) and the
     * directories between it and {@code directory} are made as the secret directory is (the folder of a use,
     * {@link SecretVault}, below the secret directory).
     */
    SecretStore(Path directory, Path floor, ProcessUser processUser) {
        this.processUser = processUser;
        this.directory = directory;
        this.floor = floor;
    }

    /**
     * Refuses early (before the user types anything) when a secret cannot be stored: a file system that cannot hold an
     * owner-only file, a missing parent, a path of the wrong kind; creates nothing.
     */
    public void check(SecretName name) {
        protection();
        try {
            if (Files.exists(directory)) {
                requireKinds(name);
            } else {
                requireParent();
            }
        } catch (IOException e) {
            throw new FailureException(ErrorCode.IO_FAILED, e, directory.toString(), reason(e));
        }
    }

    /** Whether the secret is stored already. Refuses like {@link #check} when the store cannot be used for a secret. */
    public boolean exists(SecretName name) {
        check(name);
        return Files.exists(directory.resolve(name.value()));
    }

    /**
     * The stored value, to store it under a second name ({@code token-generate --copy-to}); the caller wipes it. It is read
     * from a regular file (a link is followed) whoever may reach it, never printed.
     *
     * @throws FailureException EMPTY_SECRET_FILE when the file is empty; UNEXPECTED_FILE_TYPE when the path is not a regular file
     */
    public char[] load(SecretName name) {
        try {
            requireKinds(name);
            byte[] bytes = Files.readAllBytes(directory.resolve(name.value()));
            try {
                if (bytes.length == 0) {
                    throw new FailureException(ErrorCode.EMPTY_SECRET_FILE, name.value());
                }
                CharBuffer chars = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
                char[] value = new char[chars.remaining()];
                chars.get(value);
                Arrays.fill(chars.array(), '\0');
                return value;
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        } catch (CharacterCodingException e) {
            throw new FailureException(ErrorCode.INVALID_ENCODING, e);
        } catch (IOException e) {
            throw new FailureException(ErrorCode.IO_FAILED, e, directory.toString(), reason(e));
        }
    }

    /** Writes the value (UTF-8, no trailing newline) and returns the file. The caller wipes {@code value}. */
    public Path store(SecretName name, char[] value) {
        SecretProtection protection = protection();
        ByteBuffer bytes = encode(value);
        try {
            if (!Files.exists(directory)) {
                requireParent();
                createDirectories(protection);
            }
            requireKinds(name);
            Path target = directory.resolve(name.value());
            replace(target, bytes, protection);
            return target;
        } catch (IOException e) {
            throw new FailureException(ErrorCode.IO_FAILED, e, directory.toString(), reason(e));
        } finally {
            Arrays.fill(bytes.array(), (byte) 0);
        }
    }

    /** The floor (for example /etc/pg-ledger-mcp) belongs to the installation; only what is below it is created here. */
    private void requireParent() {
        if (floor != null && !Files.isDirectory(floor)) {
            throw new FailureException(ErrorCode.PARENT_MISSING, floor.toString());
        }
    }

    /** Makes the missing directories from the floor down to the directory, each with the protection (and settled before use). */
    private void createDirectories(SecretProtection protection) throws IOException {
        Path next = floor;
        for (Path segment : floor == null ? List.of(directory) : floor.relativize(directory)) {
            next = next == null ? segment : next.resolve(segment);
            if (!Files.exists(next)) {
                Files.createDirectory(next, protection.directoryAttributes());
                protection.settle(next);
            }
        }
    }

    /** The protection of this file system; a file system with neither POSIX permissions nor ACLs is refused. */
    private SecretProtection protection() {
        try {
            return SecretProtection.of(directory, processUser);
        } catch (IOException e) {
            throw new FailureException(ErrorCode.IO_FAILED, e, directory.toString(), reason(e));
        }
    }

    /**
     * The directory (when it exists) has to be a directory, and the file of the secret (when it exists) a regular file; a link
     * is followed to what it points at. Nothing else is asked of an existing path: not its owner, not who may reach it.
     */
    private void requireKinds(SecretName name) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        requireKind(directory, true);
        Path target = directory.resolve(name.value());
        if (Files.exists(target)) {
            requireKind(target, false);
        }
    }

    private static void requireKind(Path path, boolean expectDirectory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        if (expectDirectory ? !attributes.isDirectory() : !attributes.isRegularFile()) {
            throw new FailureException(ErrorCode.UNEXPECTED_FILE_TYPE, path.toString());
        }
    }

    /** Temporary owner-only file in the same directory, flushed to disk, then an atomic move over the target. */
    private void replace(Path target, ByteBuffer bytes, SecretProtection protection) throws IOException {
        Path temporary = Files.createTempFile(directory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX, protection.fileAttributes());
        // try-with-resources, no catch: unless the move happened the temporary file is deleted again, and a failure of
        // that delete is added to the original failure as a suppressed exception.
        try (TemporaryFile guard = new TemporaryFile(temporary)) {
            // the protection is certain before the first byte of the secret is written
            protection.settle(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            // ceiling: the directory itself is not fsynced; a crash right after the move can still lose the rename.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            guard.moved();
        }
    }

    /** Deletes the temporary file on close unless it was moved to its place. */
    private static final class TemporaryFile implements AutoCloseable {

        private final Path path;
        private boolean moved;

        TemporaryFile(Path path) {
            this.path = path;
        }

        void moved() {
            moved = true;
        }

        @Override
        public void close() throws IOException {
            if (!moved) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** UTF-8 into one buffer that the caller wipes; unpaired surrogates are rejected, never replaced. */
    private static ByteBuffer encode(char[] value) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer bytes = ByteBuffer.allocate((int) (value.length * (double) encoder.maxBytesPerChar()));
        try {
            CoderResult result = encoder.encode(CharBuffer.wrap(value), bytes, true);
            if (result.isUnderflow()) {
                result = encoder.flush(bytes);
            }
            if (!result.isUnderflow()) {
                result.throwException();
            }
        } catch (CharacterCodingException e) {
            Arrays.fill(bytes.array(), (byte) 0);
            throw new FailureException(ErrorCode.INVALID_ENCODING, e);
        }
        bytes.flip();
        return bytes;
    }

    private static String reason(IOException e) {
        return e.getClass().getSimpleName();
    }
}
