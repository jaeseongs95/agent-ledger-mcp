// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hookclient;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Optional;

/**
 * The address file of the state folder ({@link StateFolder#addressFile}): the resident program writes it when it is ready to
 * answer and removes it when it ends; the light client reads it for every call. Five lines, {@code name=value}. A file that is
 * missing, cut or not in this form is no address at all: the reader treats it as a stale file and never fails on it.
 *
 * <p>The write is atomic: the content goes to a file of its own in the same folder (created owner-only, before any byte of the
 * secret is written) and is moved over the address file in one step, so a reader sees the old file or the new one and never half
 * of a file.
 */
public final class AddressFile {

    private static final String PORT = "port";
    private static final String PID = "pid";
    private static final String PROCESS_START = "processStart";
    private static final String BUILD = "build";
    private static final String SECRET = "secret";
    private static final int SECRET_BYTES = 32;
    private static final int MOVE_ATTEMPTS = 10;
    private static final long MOVE_RETRY_MILLIS = 10;

    private final Path file;

    public AddressFile(Path file) {
        this.file = file;
    }

    /** The address in the file, or empty if there is none or the file is not an address file. */
    public Optional<ResidentAddress> read() throws IOException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException | CharacterCodingException unusable) {
            // no file, or bytes that are no text: no address
            return Optional.empty();
        }
        return parse(text);
    }

    /** Writes the address, replacing the file that was there; the new file is made with the protection of {@code files}. */
    public void write(ResidentAddress address, OwnerOnlyFiles files) throws IOException {
        String text = PORT + "=" + address.port() + "\n" + PID + "=" + address.pid() + "\n" + PROCESS_START + "="
                + address.processStart() + "\n" + BUILD + "=" + address.build() + "\n" + SECRET + "=" + address.secret()
                + "\n";
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-"
                + System.nanoTime());
        files.writeNew(temporary, text.getBytes(StandardCharsets.UTF_8));
        try {
            moveAtomic(temporary, file);
        } catch (IOException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }

    private static void moveAtomic(Path temporary, Path target) throws IOException {
        for (int attempt = 1;; attempt++) {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException transientBusy) {
                if (attempt >= MOVE_ATTEMPTS || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw transientBusy;
                }
                pauseBeforeRetry(transientBusy);
            }
        }
    }

    private static void pauseBeforeRetry(AccessDeniedException cause) throws IOException {
        try {
            Thread.sleep(MOVE_RETRY_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cause.addSuppressed(interrupted);
            throw cause;
        }
    }

    /** Removes the file if it still names this process (a newer resident program may have replaced it). */
    public void removeIfOwnedBy(long pid) throws IOException {
        boolean ours = read().map(address -> address.pid() == pid).orElse(false);
        if (ours) {
            Files.deleteIfExists(file);
        }
    }

    /** Removes the file whatever it holds. */
    public void remove() throws IOException {
        Files.deleteIfExists(file);
    }

    static Optional<ResidentAddress> parse(String text) {
        String port = null;
        String pid = null;
        String start = null;
        String build = null;
        String secret = null;
        // Scan lines without String.split so warm hook JVM invocations avoid regex Pattern class loading.
        int len = text.length();
        int lineStart = 0;
        while (lineStart <= len) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? len : nl;
            int at = text.indexOf('=', lineStart);
            if (at >= lineStart && at < lineEnd) {
                String name = text.substring(lineStart, at);
                String value = text.substring(at + 1, lineEnd).strip();
                // a line of a later version that is not named here is ignored
                if (PORT.equals(name)) {
                    port = value;
                } else if (PID.equals(name)) {
                    pid = value;
                } else if (PROCESS_START.equals(name)) {
                    start = value;
                } else if (BUILD.equals(name)) {
                    build = value;
                } else if (SECRET.equals(name)) {
                    secret = value;
                }
            }
            if (nl < 0) {
                break;
            }
            lineStart = nl + 1;
        }
        try {
            if (port == null || pid == null || start == null || build == null || secret == null || build.isEmpty()) {
                return Optional.empty();
            }
            int portNumber = Integer.parseInt(port);
            if (portNumber < 1 || portNumber > 65535 || Base64.getUrlDecoder().decode(secret).length != SECRET_BYTES) {
                return Optional.empty();
            }
            return Optional.of(new ResidentAddress(portNumber, Long.parseLong(pid), Long.parseLong(start), build, secret));
        } catch (IllegalArgumentException malformed) {
            // a number or the secret that is not what the writer makes: a stale or foreign file
            return Optional.empty();
        }
    }
}
