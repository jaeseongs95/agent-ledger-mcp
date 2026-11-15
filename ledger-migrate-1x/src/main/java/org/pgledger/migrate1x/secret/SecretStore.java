package org.pgledger.migrate1x.secret;

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

import org.pgledger.migrate1x.tool.ErrorCode;
import org.pgledger.migrate1x.tool.FailureException;
import org.springframework.stereotype.Component;

/**
 * Stores one secret per file in the secret directory of the 2.0 installation, created owner-only: the same arrangement as the
 * secret store of {@code ledger-cli} (the tool has no dependency on that module, so the few lines are repeated, as the secret
 * store of the client hook does). The protection is given at creation and chosen by the file system, never by the name of the
 * operating system ({@link SecretProtection}): POSIX permissions 0700 (directory) and 0600 (file), or on a file system with ACLs
 * an ACL with one entry for the owner and nothing inherited. A file system with neither is refused for a creation
 * (NON_POSIX_FILESYSTEM): nothing is stored with the default protection.
 *
 * <p>An existing directory or file is used as it is, whoever may reach it (the personal edition trusts the whole PC); only a
 * path of the wrong kind is refused (UNEXPECTED_FILE_TYPE).
 */
@Component
public class SecretStore {

    /** Temporary files start with two dots: configtree ignores those, and no valid secret name starts with a dot. */
    static final String TEMPORARY_PREFIX = "..secret-";
    static final String TEMPORARY_SUFFIX = ".tmp";

    private final Path directory;
    private final ProcessUser processUser;

    SecretStore(SecretsProperties properties, ProcessUser processUser) {
        this.processUser = processUser;
        this.directory = properties.directory();
    }

    /**
     * Refuses early (before anything is stored) when a secret cannot be stored: a file system that cannot hold an owner-only
     * file, a missing parent, a path of the wrong kind; creates nothing.
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

    /** Writes the value (UTF-8, no trailing newline) and returns the file. The caller wipes {@code value}. */
    public Path store(SecretName name, char[] value) {
        SecretProtection protection = protection();
        ByteBuffer bytes = encode(value);
        try {
            if (!Files.exists(directory)) {
                requireParent();
                Files.createDirectory(directory, protection.directoryAttributes());
                protection.settle(directory);
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

    /** The parent (for example /etc/pg-ledger-mcp) belongs to the installation; only the secrets directory is created here. */
    private void requireParent() {
        Path parent = directory.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            throw new FailureException(ErrorCode.PARENT_MISSING, parent.toString());
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
