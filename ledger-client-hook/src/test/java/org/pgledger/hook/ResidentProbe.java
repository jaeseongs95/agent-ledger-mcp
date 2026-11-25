// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.pgledger.hookclient.AddressFile;
import org.pgledger.hookclient.ResidentAddress;
import org.pgledger.hookclient.ResidentClient;
import org.pgledger.hookclient.ResidentException;
import org.pgledger.hookclient.Wire;

/**
 * The resident program started directly by a test, with the JVM options and the environment the test chooses, the way the light
 * client starts it ({@code java <options> -jar ledger-hook-resident.jar start <state-folder> <build>}), so that a test can look at
 * the program itself (the classes it loads, its log, its idle end) without a light client in front of it. {@link #close()} ends it.
 */
final class ResidentProbe implements AutoCloseable {

    static final Path RESIDENT_JAR = Path.of(System.getProperty("ledger.hook.resident.jar"));

    private final Process process;
    private final Path state;
    private final ResidentClient client = new ResidentClient(new Wire(), 10_000);

    private ResidentProbe(Process process, Path state) {
        this.process = process;
        this.state = state;
    }

    /**
     * Starts the program for this state folder.
     *
     * @param jvmArgs the options before {@code -jar}
     * @param env     environment variables of the program, over the key folder of the workspace
     */
    static ResidentProbe start(TestWorkspace workspace, Path state, List<String> jvmArgs, Map<String, String> env)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(HookJar.JAVA.toString());
        command.addAll(jvmArgs);
        command.add("-Duser.home=" + workspace.home());
        command.add("-jar");
        command.add(RESIDENT_JAR.toString());
        command.add("start");
        command.add(state.toString());
        command.add("probe-build");
        Path log = workspace.work().resolve("probe-" + System.nanoTime() + ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.work().toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        List.of("CLASSPATH", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").forEach(builder.environment()::remove);
        builder.environment().put(HookJar.KEY_DIR_VARIABLE, workspace.keys().toString());
        builder.environment().putAll(env);
        Process process = builder.start();
        process.getOutputStream().close();
        return new ResidentProbe(process, state);
    }

    /** The address of the program once it is ready, waiting up to the time given; empty if it ended or is not ready. */
    Optional<ResidentAddress> awaitAddress(Duration wait) throws IOException, InterruptedException {
        long end = System.nanoTime() + wait.toNanos();
        AddressFile file = new AddressFile(state.resolve("address"));
        while (System.nanoTime() < end && process.isAlive()) {
            Optional<ResidentAddress> address = file.read();
            if (address.isPresent()) {
                return address;
            }
            Thread.sleep(20);
        }
        return file.read();
    }

    ResidentClient client() {
        return client;
    }

    Process process() {
        return process;
    }

    /** Whether the program ended within the time, by itself or because it was asked to. */
    boolean awaitExit(Duration wait) throws InterruptedException {
        return process.waitFor(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Asks the program to end, and ends it if it does not. */
    @Override
    public void close() throws IOException {
        if (process.isAlive()) {
            Optional<ResidentAddress> address = new AddressFile(state.resolve("address")).read();
            if (address.isPresent()) {
                try {
                    client.stop(address.get());
                } catch (ResidentException refused) {
                    process.destroyForcibly();
                }
            }
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
