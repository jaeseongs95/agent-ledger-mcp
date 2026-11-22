// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;

/**
 * The light client jar of the staged plugin folder (the resident program lies next to it), started the way a host starts it:
 * {@code java <the JVM options of the command of hooks.json> -jar ledger-client-hook.jar <words>} of the JDK that runs the
 * tests, from an empty working directory with the class path variables removed. Files of one fixture live in its own directory.
 * The key folder of the test is handed to the process through {@code LEDGER_HOOK_KEY_DIR} and its home folder is the {@code home}
 * folder of the workspace ({@code -Duser.home}), so the real home folder is never touched and the resident program that the
 * first call starts is the test's own (it is ended with the workspace, {@link TestWorkspace}); the resident program uses the
 * real system clock, so the issue time of a token is checked against a window around the run ({@link #stamp}).
 */
final class HookJar {

    static final Path JAR = Path.of(System.getProperty("ledger.hook.jar"));
    static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    /** The environment variable that names the key folder (the hook's contract). */
    static final String KEY_DIR_VARIABLE = "LEDGER_HOOK_KEY_DIR";

    /** One start: what the process wrote and how long it took from start to exit. */
    record Timed(Run run, long millis) {}

    /** How long a resident program of a test waits for a call before it ends by itself, if the test does not end it first. */
    static final String SAFETY_IDLE_TIMEOUT = "PT2M";

    private final Path work;
    private final Path home;
    private final Path keyDirectory;
    private final Path clientJar;
    private final Clock clock = Clock.systemUTC();
    private long startedAt;
    private long finishedAt;

    HookJar(Path work, Path home, Path keyDirectory) {
        this(work, home, keyDirectory, JAR);
    }

    private HookJar(Path work, Path home, Path keyDirectory, Path clientJar) {
        this.work = work;
        this.home = home;
        this.keyDirectory = keyDirectory;
        this.clientJar = clientJar;
    }

    /** The same workspace, started from another copy of the client jar (a test of a plugin folder of its own). */
    HookJar using(Path otherClientJar) {
        return new HookJar(work, home, keyDirectory, otherClientJar);
    }

    /** The home folder of the processes this jar starts. */
    Path home() {
        return home;
    }

    Path keyDirectory() {
        return keyDirectory;
    }

    Timed start(Path javaExecutable, List<String> jvmArgs, Map<String, String> env, byte[] stdin, String... args) throws IOException {
        return start(javaExecutable, jvmArgs, env, Map.of(), stdin, args);
    }

    /**
     * Like the start with an empty working directory, but the working directory holds the files given (relative path to
     * text): the way a host starts the hook in the directory of a project that has files of its own.
     */
    Timed start(Path javaExecutable, List<String> jvmArgs, Map<String, String> env, Map<String, String> workingFiles,
            byte[] stdin, String... args) throws IOException {
        Path in = Files.createTempFile(work, "stdin", ".json");
        Path out = Files.createTempFile(work, "stdout", ".out");
        Path err = Files.createTempFile(work, "stderr", ".out");
        Files.write(in, stdin);
        Path cwd = Files.createTempDirectory(work, "cwd");
        for (Map.Entry<String, String> file : workingFiles.entrySet()) {
            Path target = cwd.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        ProcessBuilder builder = builder(javaExecutable, jvmArgs, env, cwd, args).redirectInput(in.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        long start = System.nanoTime();
        startedAt = clock.instant().getEpochSecond();
        Process process = builder.start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("hook jar did not finish in 60 s");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
        finishedAt = clock.instant().getEpochSecond();
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new Timed(new Run(process.exitValue(), Files.readAllBytes(out), Files.readAllBytes(err)), millis);
    }

    /**
     * The process builder of a start, with the command of the host (the JVM options of hooks.json), the home folder of the
     * workspace, the key folder and the safety idle timeout; the caller chooses the streams.
     */
    ProcessBuilder builder(Path javaExecutable, List<String> jvmArgs, Map<String, String> env, Path cwd, String... args) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.addAll(HostCommand.jvmOptions());
        command.add("-Duser.home=" + home);
        command.addAll(jvmArgs);
        command.add("-jar");
        command.add(clientJar.toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
        List.of("CLASSPATH", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").forEach(builder.environment()::remove);
        builder.environment().put(KEY_DIR_VARIABLE, keyDirectory.toString());
        builder.environment().put("LEDGER_HOOK_RESIDENT_IDLE_TIMEOUT", SAFETY_IDLE_TIMEOUT);
        // the start line of every resident program (INFO) is how the tests count the starts (Residents.startsOf)
        builder.environment().put("LOGGING_LEVEL_ORG_PGLEDGER_HOOK_RESIDENT", "INFO");
        builder.environment().putAll(env);
        return builder;
    }

    /** The plain start with this JDK and the default environment (plus the key folder). */
    Timed start(byte[] stdin, String... args) throws IOException {
        return start(JAVA, List.of(), Map.of(), stdin, args);
    }

    Run run(byte[] stdin, String... args) throws IOException {
        return start(stdin, args).run();
    }

    /** The working directories of the processes started so far (each one was empty when its process started). */
    List<Path> workingDirectories() throws IOException {
        try (var entries = Files.list(work)) {
            return entries.filter(path -> path.getFileName().toString().startsWith("cwd")).toList();
        }
    }

    /**
     * Checks the attestation of the last start of a direct call with the standard host id: the token is issued between
     * the start and the end of that process, and verified with the public key in the key folder.
     */
    Attestations.Stamp stamp(HostScenario host, String op, JsonNode sent, Run run) {
        return Attestations.verify(keyDirectory, new Attestations.Window(startedAt, finishedAt + 1), host.server(),
                host.author(HookFixture.HOST_ID), op, sent, host.stampedPart(run));
    }
}
