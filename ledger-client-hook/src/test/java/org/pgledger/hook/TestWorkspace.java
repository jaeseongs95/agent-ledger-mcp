// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The files of one test: a temporary root with an empty {@code work} folder (stdin, stdout and working directories of a
 * process test) and the path of the {@code keys} folder, which is NOT created: the hook creates it with its first key. The
 * {@code home} folder is the home folder of the processes the test starts ({@code -Duser.home}): the resident program, its state
 * folder and its program copy live there. Nothing of the real home folder is involved; {@link #close()} ends every resident
 * program of the test and then deletes the whole root, so a test never leaves a process or a file behind.
 */
final class TestWorkspace implements AutoCloseable {

    private static final long DELETE_SETTLE_MILLIS = 2_000;
    private static final long DELETE_RETRY_MILLIS = 50;

    private final Path root;

    TestWorkspace() {
        try {
            root = Files.createTempDirectory("ledger-hook-test");
            Files.createDirectory(root.resolve("work"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path root() {
        return root;
    }

    Path work() {
        return root.resolve("work");
    }

    /** The key folder (LEDGER_HOOK_KEY_DIR of a process test); absent until the first key is created. */
    Path keys() {
        return root.resolve("keys");
    }

    /** The home folder of the processes of the test (it is not created until a resident program makes its state folder). */
    Path home() {
        return root.resolve("home");
    }

    @Override
    public void close() throws IOException {
        Residents.stopAll(home());
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                deleteSettlingTransientLock(file);
            }
        }
    }

    /**
     * Deletes {@code file}, retrying briefly when an exited resident's jar or directory still carries a transient kernel or
     * scanner sharing lock after {@link Residents#stopAll} confirmed the process ended. A genuinely leaked live resident holds
     * its jar far longer than {@link #DELETE_SETTLE_MILLIS} and still fails loudly.
     */
    private static void deleteSettlingTransientLock(Path file) throws IOException {
        long deadline = System.nanoTime() + DELETE_SETTLE_MILLIS * 1_000_000L;
        while (true) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (FileSystemException locked) {
                if (System.nanoTime() >= deadline) {
                    throw locked;
                }
                try {
                    Thread.sleep(DELETE_RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw locked;
                }
            }
        }
    }
}
