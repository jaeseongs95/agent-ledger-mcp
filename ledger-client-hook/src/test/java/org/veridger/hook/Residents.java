// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.veridger.hookclient.AddressFile;
import org.veridger.hookclient.ResidentAddress;
import org.veridger.hookclient.ResidentClient;
import org.veridger.hookclient.ResidentException;
import org.veridger.hookclient.ResidentProcess;
import org.veridger.hookclient.Wire;

/**
 * The resident programs of a test workspace, found from its state folders ({@code <home>/.veridger/resident/*}) and from
 * the processes of the machine: the tests look at them and always end them, the way a test never leaves a process behind.
 */
final class Residents {

    private static final ResidentProcess PROCESSES = new ResidentProcess();

    private Residents() {}

    /** The state folders that exist under this home folder. */
    static List<Path> stateFolders(Path home) throws IOException {
        Path states = home.resolve(".veridger").resolve("resident");
        if (!Files.isDirectory(states)) {
            return List.of();
        }
        try (Stream<Path> folders = Files.list(states)) {
            return folders.filter(Files::isDirectory).toList();
        }
    }

    /** The addresses of the live resident programs under this home folder. */
    static List<ResidentAddress> running(Path home) throws IOException {
        List<ResidentAddress> live = new ArrayList<>();
        for (Path state : stateFolders(home)) {
            Optional<ResidentAddress> address = new AddressFile(state.resolve("address")).read();
            if (address.isPresent() && PROCESSES.isLive(address.get())) {
                live.add(address.get());
            }
        }
        return live;
    }

    /** The text of the start line of {@code ResidentServer}. */
    static final String START_LINE = "the resident hook program starts";

    /**
     * How many resident programs were started for the state folders under this home folder: the start lines of their logs
     * ({@code ResidentServer} logs one when a program starts, at INFO, and the process tests ask for that level). A program that
     * is started and finds the state folder taken ends at once but has logged its start line, so it is counted: a second start of
     * a program is never hidden. (The command line of a process is not available on every platform, a log line is.)
     */
    static long startsOf(Path home) throws IOException {
        long starts = 0;
        for (Path state : stateFolders(home)) {
            Path log = state.resolve("resident.log");
            if (Files.isRegularFile(log)) {
                starts += Files.readAllLines(log).stream().filter(line -> line.contains(START_LINE)).count();
            }
        }
        return starts;
    }

    /** Whether the process of this address is live. */
    static boolean isLive(ResidentAddress address) {
        return PROCESSES.isLive(address);
    }

    /** Waits until the process of this address is gone; false if it is still there after the time. */
    static boolean awaitGone(ResidentAddress address, long millis) throws InterruptedException {
        long end = System.nanoTime() + millis * 1_000_000L;
        while (PROCESSES.isLive(address)) {
            if (System.nanoTime() > end) {
                return false;
            }
            Thread.sleep(25);
        }
        return true;
    }

    /** Ends every resident program under this home folder: asked to stop, ended if it does not, and waited for. */
    static void stopAll(Path home) throws IOException {
        ResidentClient client = new ResidentClient(new Wire(), 5_000);
        for (ResidentAddress address : running(home)) {
            try {
                client.stop(address);
            } catch (ResidentException refused) {
                PROCESSES.destroy(address);
            }
            try {
                if (!awaitGone(address, 5_000)) {
                    PROCESSES.destroy(address);
                    awaitGone(address, 5_000);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                PROCESSES.destroy(address);
            }
        }
    }
}
