// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hookclient;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the resident program when there is none, and replaces it when it is of another build.
 *
 * <p>Many clients can find no program at the same moment (a host runs several hooks at once); exactly one starts it. The state
 * folder has the startup lock, a lock on a file that {@link FileChannel#tryLock} takes: the client that holds it starts the
 * program and waits until its address file shows it ready; the others poll the address file. Nobody starts a program before
 * looking again under the lock, so clients that come after the winner is done find it ready and start nothing. A process that
 * ends releases its lock, so a client that was killed in the middle leaves no lock behind. The program itself holds the
 * instance lock for its whole life, a second guard that makes a second program on the same state folder end at once.
 *
 * <p>The program is started from a copy ({@link ResidentRuntime}), with its standard streams away from the client: no input,
 * its output and error in the log file of the state folder. It outlives the client and the host call that started it.
 * Nothing kills a program because it is slow to start: a client that is out of time gives up waiting and the program goes on.
 */
public final class ResidentLauncher {

    /** The options of the JVM of the resident program (see the measurements in the release notes of the change). */
    static final List<String> JVM_OPTIONS = List.of("-XX:+UseSerialGC", "-Xmx512m", "-XX:TieredStopAtLevel=1",
            "-XX:-UsePerfData", "-Xlog:disable");

    private static final long POLL_MILLIS = 20;
    private static final long GONE_WAIT_MILLIS = 5_000;

    private final StateFolder state;
    private final ClientEnvironment environment;
    private final ResidentJar jar;
    private final ResidentFinder finder;
    private final ResidentClient client;
    private final ResidentProcess processes;
    private final AddressFile addressFile;

    public ResidentLauncher(StateFolder state, ClientEnvironment environment, ResidentJar jar, ResidentFinder finder,
            ResidentClient client, ResidentProcess processes, AddressFile addressFile) {
        this.state = state;
        this.environment = environment;
        this.jar = jar;
        this.finder = finder;
        this.client = client;
        this.processes = processes;
        this.addressFile = addressFile;
    }

    /**
     * The address of a live program of this build: the one that runs, or one that this call starts (or waits for).
     *
     * @param deadlineMillis how long to wait for the start, from now
     */
    public ResidentAddress ensureRunning(long deadlineMillis) throws ResidentException {
        long end = System.nanoTime() + deadlineMillis * 1_000_000L;
        try {
            OwnerOnlyFiles files = OwnerOnlyFiles.forFileSystem(FileSystems.getDefault(), environment.userName());
            files.createDirectories(state.directory());
            try (FileChannel channel = files.openLockFile(state.startupLock())) {
                while (true) {
                    if (finder.find(jar.build()) instanceof ResidentFinder.Lookup.Usable usable) {
                        return usable.address();
                    }
                    FileLock lock = tryLock(channel);
                    if (lock != null) {
                        try {
                            return startUnderLock(files, end);
                        } finally {
                            lock.release();
                        }
                    }
                    if (System.nanoTime() >= end) {
                        throw new ResidentException("the resident hook program is being started by another call and was not "
                                + "ready in time; try the call again");
                    }
                    pause();
                }
            }
        } catch (IOException failure) {
            throw new ResidentException("the resident hook program cannot be started: " + failure.getMessage(), failure);
        }
    }

    /** Waits until the program of this address is gone (it was refused because it is ending); gives up after five seconds. */
    public void awaitGone(ResidentAddress address) throws ResidentException {
        long end = System.nanoTime() + GONE_WAIT_MILLIS * 1_000_000L;
        while (processes.isLive(address) && System.nanoTime() < end) {
            pause();
        }
    }

    private ResidentAddress startUnderLock(OwnerOnlyFiles files, long end) throws IOException, ResidentException {
        ResidentFinder.Lookup found = finder.find(jar.build());
        if (found instanceof ResidentFinder.Lookup.Usable usable) {
            return usable.address();
        }
        if (found instanceof ResidentFinder.Lookup.OtherBuild other) {
            retire(other.address());
        }
        ResidentRuntime runtime = new ResidentRuntime(Path.of(environment.userHome()), files);
        Path copy = runtime.install(jar);
        runtime.pruneOtherBuilds(jar.build());
        files.createIfMissing(state.logFile());
        try (StartedResident started = spawn(copy)) {
            while (System.nanoTime() < end) {
                if (finder.find(jar.build()) instanceof ResidentFinder.Lookup.Usable usable) {
                    return usable.address();
                }
                if (!started.isAlive()) {
                    throw new ResidentException("the resident hook program ended while starting (exit code " + started.exitValue()
                            + "); its log is " + state.logFile());
                }
                pause();
            }
            throw new ResidentException("the resident hook program did not get ready in time; it goes on starting, "
                    + "try the call again");
        }
    }

    /** Replaces a program of another build: it is asked to end, and ended if it does not. */
    private void retire(ResidentAddress old) throws IOException, ResidentException {
        try {
            client.stop(old);
        } catch (ResidentException refused) {
            // it did not take the request (it is ending, or hung): the process itself is ended below
            processes.destroy(old);
        }
        awaitGone(old);
        if (processes.isLive(old)) {
            processes.destroy(old);
            awaitGone(old);
        }
        addressFile.remove();
    }

    private StartedResident spawn(Path copy) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(environment.java().toString());
        command.addAll(JVM_OPTIONS);
        command.add("-Duser.home=" + environment.userHome());
        command.add("-jar");
        command.add(copy.toString());
        command.add("start");
        command.add(state.directory().toString());
        command.add(jar.build());
        if (WindowsIsolatedProcess.isSupported()) {
            return WindowsIsolatedProcess.spawn(command, environment.temporary(), state.logFile());
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(environment.temporary().toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(state.logFile().toFile()));
        Process process = builder.start();
        // no input: the program reads nothing from the client, and an open pipe would tie it to this process
        process.getOutputStream().close();
        return StartedResident.ofProcess(process);
    }

    /** Minimal handle to a spawned resident process for startup liveness and exit-code checks. */
    interface StartedResident extends AutoCloseable {

        boolean isAlive();

        int exitValue();

        @Override
        void close();

        static StartedResident ofProcess(Process process) {
            return new StartedResident() {
                @Override
                public boolean isAlive() {
                    return process.isAlive();
                }

                @Override
                public int exitValue() {
                    return process.exitValue();
                }

                @Override
                public void close() {
                    // POSIX Process carries no external native handle that close() must release here
                }
            };
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException sameProcess) {
            // this process holds the lock already (another thread of it is the starter): treat it as held by another
            return null;
        }
    }

    private static void pause() throws ResidentException {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResidentException("the client was interrupted while waiting for the resident hook program", interrupted);
        }
    }
}
