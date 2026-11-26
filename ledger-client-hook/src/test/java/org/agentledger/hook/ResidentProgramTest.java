// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.agentledger.files.FileKind;
import org.agentledger.files.FileProtection;
import org.agentledger.files.ProcessAccount;
import org.agentledger.hookclient.AddressFile;
import org.agentledger.hookclient.ResidentAddress;
import org.agentledger.hookclient.StateFolder;
import tools.jackson.databind.JsonNode;

/**
 * The resident program and its light client as processes (D-0096, user decision of 2026-11-22): what the hosts get when they run
 * {@code ledger-client-hook.jar}. Every test has its own home folder, key folder and resident program, and ends the program with
 * the workspace.
 */
@ExtendWith(HookExtension.class)
class ResidentProgramTest {

    private static final JsonNode INPUT = Json.tree(Map.of("note", "한글 😀 é"));
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final CodexHost codex = new CodexHost();
    private final byte[] stdin = codex.event(codex.toolName("issue_save"), INPUT);

    /** One call, which must be answered with a valid attestation; returns the answer. */
    private Run answered(HookJar jar) throws IOException {
        Run run = jar.run(stdin, "codex");
        assertThat(run.err()).as("stderr").isEmpty();
        assertThat(run.exit()).as("exit code").isZero();
        Attestations.Stamp stamp = jar.stamp(codex, "issue_save", INPUT, run);
        assertThat(run.document()).isEqualTo(codex.allowed(INPUT, stamp.token()));
        return run;
    }

    private static ResidentAddress theResident(HookJar jar) throws IOException {
        List<ResidentAddress> running = Residents.running(jar.home());
        assertThat(running).as("exactly one resident program is running").hasSize(1);
        return running.getFirst();
    }

    private static StateFolder stateOf(HookJar jar) {
        return StateFolder.locate(jar.home().toString(), jar.keyDirectory().toString());
    }

    /** A copy of the staged plugin folder inside the workspace: both jars next to each other, free to change and delete. */
    private static Path pluginCopy(TestWorkspace workspace) throws IOException {
        Path lib = workspace.root().resolve("plugin").resolve("lib");
        Files.createDirectories(lib);
        Files.copy(HookJar.JAR, lib.resolve("ledger-client-hook.jar"));
        Files.copy(ResidentProbe.RESIDENT_JAR, lib.resolve("ledger-hook-resident.jar"));
        return lib;
    }

    // ---- R9-D0096-D: the light client loads java.base only ----

    private static final Pattern CLASS_LOAD = Pattern.compile("^\\[[^\\]]*\\]\\[[^\\]]*\\]\\[[^\\]]*\\] (\\S+) source: (.*)$");
    private static final Pattern FORBIDDEN_CLASS = Pattern.compile("org\\.springframework\\.|tools\\.jackson\\.|com\\.fasterxml\\."
            + "|org\\.slf4j\\.|ch\\.qos\\.logback\\.|jakarta\\.|org\\.apache\\.|java\\.util\\.logging\\.");
    /**
     * The JDK modules besides java.base that the JDK itself loads for a TCP connection through HttpURLConnection: jdk.net (the
     * socket options of every java.net socket) and java.logging (the platform logger of HttpURLConnection: one provider class and
     * no java.util.logging class). The code of the client is java.base only (ArchitectureTest of the client module).
     */
    private static final Set<String> JDK_MODULES = Set.of("jrt:/java.base", "jrt:/jdk.net", "jrt:/java.logging");

    @Test
    @DisplayName("R9-D0096-D the light client of a call loads no class of Spring, Jackson, SLF4J or Logback, no class of a JDK module beyond java.base, and from its jar only its own classes and the file protection of ledger-files (fails the test, not a time)")
    void theLightClientLoadsOnlyJavaBase(HookJar jar, TestWorkspace workspace) throws IOException {
        answered(jar);
        Path classLog = workspace.work().resolve("client-class-load.log");
        // -Xshare:off: every class names the module it comes from (jrt:/java.base), where the shared archive would hide it
        Run run = jar.start(HookJar.JAVA, List.of("-Xshare:off", "-Xlog:class+load:file=" + classLog), Map.of(), stdin, "codex")
                .run();
        assertThat(run.exit()).isZero();
        assertThat(run.err()).isEmpty();

        List<String> loaded = Files.readAllLines(classLog, StandardCharsets.UTF_8);
        assertThat(loaded).anyMatch(line -> line.contains(" org.agentledger.hookclient.Main source:"));
        List<String> problems = new java.util.ArrayList<>();
        int classes = 0;
        for (String line : loaded) {
            Matcher matcher = CLASS_LOAD.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            classes++;
            String name = matcher.group(1);
            String source = matcher.group(2);
            if (FORBIDDEN_CLASS.matcher(name).find()) {
                problems.add("forbidden class " + name);
            } else if (source.startsWith("jrt:/")) {
                if (!JDK_MODULES.contains(source)) {
                    problems.add("a JDK module beyond java.base, jdk.net and java.logging: " + name + " from " + source);
                }
            } else if (source.startsWith("file:")) {
                if (!name.startsWith("org.agentledger.hookclient.") && !name.startsWith("org.agentledger.files.")) {
                    problems.add("a class of the jar that is not the client's: " + name);
                }
            } else if (!source.startsWith("__") && !name.contains("$$Lambda")) {
                // a hidden class of a lambda has the class that made it as its source, one of a method handle a name with two
                // underscores; nothing else is expected
                problems.add("unexpected source: " + name + " from " + source);
            }
        }
        assertThat(classes).as("the log lists the loaded classes").isGreaterThan(300);
        assertThat(problems).isEmpty();
    }

    // ---- R9-D0096-I: the resident program outlives the call ----

    @Test
    @DisplayName("R9-D0096-I the call that starts the resident program returns when the answer is printed (its standard output pipe is closed, the program does not hold it), and the program is still running afterwards and answers the next call")
    void theResidentProgramOutlivesTheCallAndDoesNotHoldItsPipes(HookJar jar, TestWorkspace workspace) throws Exception {
        // real pipes, not files: a program that kept a handle of the pipe would keep the host waiting for the end of the stream
        Path cwd = Files.createTempDirectory(workspace.work(), "pipes");
        ProcessBuilder builder = jar.builder(HookJar.JAVA, List.of(), Map.of(), cwd, "codex");
        Process client = builder.start();
        client.getOutputStream().write(stdin);
        client.getOutputStream().close();
        byte[] out;
        long begin = System.nanoTime();
        try (InputStream stdout = client.getInputStream()) {
            out = stdout.readAllBytes();
        }
        byte[] err = client.getErrorStream().readAllBytes();
        assertThat(client.waitFor(30, TimeUnit.SECONDS)).isTrue();
        long millis = (System.nanoTime() - begin) / 1_000_000;
        assertThat(client.exitValue()).isZero();
        assertThat(err).isEmpty();
        assertThat(Json.MAPPER.readTree(out).path("hookSpecificOutput").path("updatedInput").path(Json.RESERVED_KEY).isString())
                .isTrue();
        assertThat(millis).as("the end of the pipe came with the answer, not with the end of the program").isLessThan(20_000);

        ResidentAddress resident = theResident(jar);
        Thread.sleep(1_500);
        assertThat(Residents.isLive(resident)).as("the program outlives the call that started it").isTrue();
        answered(jar);
        assertThat(theResident(jar)).as("the same program answered the next call").isEqualTo(resident);
    }

    @Test
    @DisplayName("R9-D0096-I when an enclosing caller invokes the light client via pipes, the started resident program does not inherit and hold the enclosing caller's stdout/stderr pipes open")
    void theResidentProgramDoesNotHoldAnEnclosingCallersPipe(HookJar jar, TestWorkspace workspace) throws Exception {
        Path cwd = Files.createTempDirectory(workspace.work(), "relay-pipes");
        ProcessBuilder target = jar.builder(HookJar.JAVA, List.of(), Map.of(), cwd, "codex");
        List<String> command = enclosingCommand(target.command());
        ProcessBuilder relayBuilder = new ProcessBuilder(command).directory(cwd.toFile());
        relayBuilder.environment().clear();
        relayBuilder.environment().putAll(target.environment());

        ExecutorService readers = Executors.newFixedThreadPool(2);
        Process relay = null;
        try {
            relay = relayBuilder.start();
            Process startedRelay = relay;
            Future<byte[]> stdoutFuture = readers.submit(() -> {
                try (InputStream stdout = startedRelay.getInputStream()) {
                    return stdout.readAllBytes();
                }
            });
            Future<byte[]> stderrFuture = readers.submit(() -> {
                try (InputStream stderr = startedRelay.getErrorStream()) {
                    return stderr.readAllBytes();
                }
            });
            try (OutputStream toRelay = relay.getOutputStream()) {
                toRelay.write(stdin);
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            long remainingMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            assertThat(relay.waitFor(remainingMs, TimeUnit.MILLISECONDS))
                    .as("relay hook exited within 10s").isTrue();
            assertThat(relay.exitValue()).as("relay hook exit code").isZero();

            remainingMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            byte[] out = stdoutFuture.get(remainingMs, TimeUnit.MILLISECONDS);
            remainingMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            byte[] err = stderrFuture.get(remainingMs, TimeUnit.MILLISECONDS);

            assertThat(err).as("relay stderr").isEmpty();
            JsonNode updated = Json.MAPPER.readTree(out).path("hookSpecificOutput").path("updatedInput");
            assertThat(updated.path(Json.RESERVED_KEY).isString()).as("relay stdout attested JSON").isTrue();
            assertThat(updated.path("note").stringValue()).as("UTF-8 input preserved through relay")
                    .isEqualTo("한글 😀 é");

            ResidentAddress resident = theResident(jar);
            Thread.sleep(1_500);
            assertThat(Residents.isLive(resident)).as("the resident program outlives the enclosing relay").isTrue();
            answered(jar);
            assertThat(theResident(jar)).as("the same resident program answered the second warm call")
                    .isEqualTo(resident);
        } finally {
            if (relay != null) {
                relay.destroyForcibly();
            }
            Residents.stopAll(jar.home());
            readers.shutdownNow();
            readers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static List<String> enclosingCommand(List<String> targetCommand) {
        if (FileKind.of(FileSystems.getDefault()) == FileKind.ACL) {
            // PowerShell wraps the client in a native command invocation with inheritable stdout/stderr pipe handles, matching
            // real host execution on ACL hosts without Git Bash.
            String invocation = targetCommand.stream()
                    .map(arg -> "'" + arg.replace("'", "''") + "'")
                    .collect(Collectors.joining(" "));
            String script = "[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false); "
                    + "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); "
                    + "& " + invocation + "; exit $LASTEXITCODE";
            return List.of("powershell.exe", "-NoProfile", "-Command", script);
        }
        List<String> command = new ArrayList<>();
        command.add(HookJar.JAVA.toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(RelayHook.class.getName());
        command.addAll(targetCommand);
        return command;
    }

    // ---- R9-D0096-J: stale and foreign address files ----

    /** (name, the text of the address file) written before the first call, given the home and the key folder of the test. */
    interface StaleFile {
        String text(HookJar jar) throws Exception;
    }

    static Stream<Arguments> staleFiles() {
        String secret = ResidentAddress.newSecret();
        return Stream.of(
                Arguments.argumentSet("garbage", (StaleFile) jar -> "this is not an address file\n"),
                Arguments.argumentSet("empty", (StaleFile) jar -> ""),
                Arguments.argumentSet("a port without the other lines", (StaleFile) jar -> "port=1234\n"),
                Arguments.argumentSet("the pid of a process that has ended", (StaleFile) jar -> {
                    Process ended = new ProcessBuilder(HookJar.JAVA.toString(), "-version").redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                    long start = ended.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
                    ended.waitFor(60, TimeUnit.SECONDS);
                    return "port=1\npid=" + ended.pid() + "\nprocessStart=" + start + "\nbuild=1-1-1\nsecret=" + secret + "\n";
                }),
                Arguments.argumentSet("a pid that is alive but is another process than the one that wrote the file (a reused pid)",
                        (StaleFile) jar -> "port=1\npid=" + ProcessHandle.current().pid() + "\nprocessStart="
                                + (ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli() + 1234)
                                + "\nbuild=1-1-1\nsecret=" + secret + "\n"));
    }

    @ParameterizedTest
    @MethodSource("staleFiles")
    @DisplayName("R9-D0096-J a stale address file (garbage, cut, a dead pid, a reused pid) is no address: the call starts a resident program, is answered, the file is replaced, and the process of a reused pid is left alone")
    void staleAddressFilesAreIgnored(StaleFile stale, HookJar jar) throws Exception {
        StateFolder state = stateOf(jar);
        Files.createDirectories(state.directory());
        Files.writeString(state.addressFile(), stale.text(jar));

        answered(jar);

        ResidentAddress resident = theResident(jar);
        assertThat(resident.pid()).as("the program that answers is not the process of the stale file").isNotEqualTo(ProcessHandle.current().pid());
        assertThat(new AddressFile(state.addressFile()).read()).contains(resident);
        assertThat(ProcessHandle.current().isAlive()).isTrue();
    }

    @Test
    @DisplayName("R9-D0096-J a resident program that is killed (no chance to remove its address file) leaves a stale file: the next call finds the pid dead, starts a new program and is answered by it")
    void aKilledResidentProgramIsReplaced(HookJar jar) throws Exception {
        answered(jar);
        ResidentAddress killed = theResident(jar);
        ProcessHandle.of(killed.pid()).orElseThrow().destroyForcibly();
        assertThat(Residents.awaitGone(killed, 10_000)).isTrue();
        assertThat(stateOf(jar).addressFile()).as("the killed program could not remove its file").exists();

        answered(jar);

        assertThat(theResident(jar).pid()).isNotEqualTo(killed.pid());
    }

    // ---- R9-D0096-K: another build is replaced ----

    @Test
    @DisplayName("R9-D0096-K a client whose resident program file is another build (a plugin that was updated) replaces the running program: the old one ends, a new one answers, only one runs, and the copy of the old build is removed")
    void anotherBuildReplacesTheRunningProgram(HookJar original, TestWorkspace workspace) throws Exception {
        Path lib = pluginCopy(workspace);
        HookJar jar = original.using(lib.resolve("ledger-client-hook.jar"));
        answered(jar);
        ResidentAddress old = theResident(jar);

        Path program = lib.resolve("ledger-hook-resident.jar");
        Files.setLastModifiedTime(program, FileTime.fromMillis(Files.getLastModifiedTime(program).toMillis() + 3_600_000L));
        answered(jar);

        ResidentAddress replacement = theResident(jar);
        assertThat(replacement.pid()).isNotEqualTo(old.pid());
        assertThat(replacement.build()).isNotEqualTo(old.build());
        assertThat(Residents.awaitGone(old, 10_000)).as("the old program has ended").isTrue();
        assertThat(Residents.startsOf(jar.home())).as("one start for the old build and one for the new").isEqualTo(2);
        try (Stream<Path> builds = Files.list(workspace.home().resolve(".agent-ledger-mcp").resolve("resident-runtime"))) {
            assertThat(builds.map(path -> path.getFileName().toString())).as("the copy of the old build is removed")
                    .containsExactly(replacement.build());
        }
    }

    @Test
    @DisplayName("R9-D0096-K a program of another build that does not answer (a stale file that names a live process) is ended by the client that replaces it, and only that process")
    void aProgramOfAnotherBuildThatDoesNotAnswerIsEnded(HookJar jar) throws Exception {
        Process impostor = new ProcessBuilder(HookJar.JAVA.toString(), "-XX:+UseSerialGC", "-cp", System.getProperty("java.class.path"),
                "org.agentledger.hook.Sleeper").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            Thread.sleep(300);
            long start = impostor.info().startInstant().orElseThrow().toEpochMilli();
            StateFolder state = stateOf(jar);
            Files.createDirectories(state.directory());
            Files.writeString(state.addressFile(), "port=1\npid=" + impostor.pid() + "\nprocessStart=" + start
                    + "\nbuild=0-0-0\nsecret=" + ResidentAddress.newSecret() + "\n");

            answered(jar);

            assertThat(impostor.waitFor(15, TimeUnit.SECONDS)).as("the process of the other build was ended").isTrue();
            assertThat(theResident(jar).pid()).isNotEqualTo(impostor.pid());
        } finally {
            impostor.destroyForcibly();
        }
    }

    @Test
    @DisplayName("R9-D0096-K a live program of this build that takes no call (nothing listens at its port, it does not end) is broken, not ending: the client ends that process after the wait and starts a new program, which answers")
    void aLiveProgramThatRefusesCallsIsReplaced(HookJar jar) throws Exception {
        Process broken = new ProcessBuilder(HookJar.JAVA.toString(), "-XX:+UseSerialGC", "-cp", System.getProperty("java.class.path"),
                "org.agentledger.hook.Sleeper").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            Thread.sleep(300);
            long start = broken.info().startInstant().orElseThrow().toEpochMilli();
            String build = org.agentledger.hookclient.ResidentJar.besideClient(HookJar.JAR).build();
            StateFolder state = stateOf(jar);
            Files.createDirectories(state.directory());
            Files.writeString(state.addressFile(), "port=1\npid=" + broken.pid() + "\nprocessStart=" + start + "\nbuild=" + build
                    + "\nsecret=" + ResidentAddress.newSecret() + "\n");

            answered(jar);

            assertThat(broken.waitFor(15, TimeUnit.SECONDS)).as("the broken program was ended").isTrue();
            assertThat(theResident(jar).pid()).isNotEqualTo(broken.pid());
        } finally {
            broken.destroyForcibly();
        }
    }

    // ---- R9-D0096-L: the plugin folder is not locked ----

    @Test
    @DisplayName("R9-D0096-L the running resident program does not hold the jars of the plugin folder: both can be overwritten and deleted, with the folder, while it runs (an update or an uninstall is never blocked)")
    void thePluginFolderIsNotLocked(HookJar original, TestWorkspace workspace) throws Exception {
        Path lib = pluginCopy(workspace);
        HookJar jar = original.using(lib.resolve("ledger-client-hook.jar"));
        answered(jar);
        ResidentAddress resident = theResident(jar);

        Files.write(lib.resolve("ledger-hook-resident.jar"), new byte[] {1, 2, 3});
        Files.move(lib.resolve("ledger-client-hook.jar"), lib.resolve("moved.jar"), StandardCopyOption.REPLACE_EXISTING);
        Files.delete(lib.resolve("ledger-hook-resident.jar"));
        Files.delete(lib.resolve("moved.jar"));
        Files.delete(lib);
        Files.delete(lib.getParent());

        assertThat(Residents.isLive(resident)).as("the program runs from its own copy").isTrue();
    }

    // ---- R9-D0096-M: only the owner can call the program ----

    private static HttpResponse<byte[]> post(ResidentAddress address, String path, String user, String secret, byte[] body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + address.port() + path))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (secret != null) {
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8)));
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    @DisplayName("R9-D0096-M a request without the secret, with a wrong one, with a secret of another start, as another user, or to /stop is refused with 401 and reaches the hook (no key is made, the program keeps running); the secret never appears in the log file or in any output")
    void onlyTheOwnerCanCallTheProgram(HookJar jar) throws Exception {
        Run first = jar.run(new byte[0], "codex");
        assertThat(first.exit()).isZero();
        ResidentAddress resident = theResident(jar);
        byte[] body = new org.agentledger.hookclient.Wire().encode(
                new org.agentledger.hookclient.HookCall(List.of("codex"), stdin));
        String sameLength = "x".repeat(resident.secret().length());
        String almost = resident.secret().substring(0, resident.secret().length() - 1) + (resident.secret().endsWith("A") ? "B" : "A");

        assertThat(post(resident, "/call", "hook", null, body).statusCode()).as("no credentials").isEqualTo(401);
        assertThat(post(resident, "/call", "hook", "", body).statusCode()).as("empty secret").isEqualTo(401);
        assertThat(post(resident, "/call", "hook", sameLength, body).statusCode()).as("wrong secret").isEqualTo(401);
        assertThat(post(resident, "/call", "hook", almost, body).statusCode()).as("one character off").isEqualTo(401);
        assertThat(post(resident, "/call", "hook", resident.secret() + "x", body).statusCode()).as("longer").isEqualTo(401);
        assertThat(post(resident, "/call", "owner", resident.secret(), body).statusCode()).as("right secret, another user").isEqualTo(401);
        assertThat(post(resident, "/call", "hook", ResidentAddress.newSecret(), body).statusCode()).as("a secret of another start").isEqualTo(401);
        assertThat(post(resident, "/stop", "hook", sameLength, new byte[0]).statusCode()).as("stop without the secret").isEqualTo(401);
        assertThat(Residents.isLive(resident)).as("the refused /stop did not stop it").isTrue();
        assertThat(jar.keyDirectory()).as("a refused call never reaches the hook: no key was made").doesNotExist();

        // the control: with the secret the same request is answered (so the 401 above is the secret and nothing else)
        HttpResponse<byte[]> accepted = post(resident, "/call", "hook", resident.secret(), body);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(new org.agentledger.hookclient.Wire().decodeAnswer(new java.io.ByteArrayInputStream(accepted.body())).exit()).isZero();
        assertThat(jar.keyDirectory()).as("the accepted call made the key").exists();
        assertThat(HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + resident.port() + "/call"))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(("hook:" + resident.secret()).getBytes(StandardCharsets.UTF_8)))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode()).as("GET").isEqualTo(405);

        String log = Files.readString(stateOf(jar).logFile());
        for (String output : List.of(log, first.out(), first.err())) {
            assertThat(output).doesNotContain(resident.secret());
        }
        assertThat(resident.toString()).doesNotContain(resident.secret());
    }

    @Test
    @DisplayName("R9-D0096-M a request that declares a body beyond the limit is refused with 413 before the body is read, and a call with a body that is no call is refused with 400; the program goes on")
    void oversizedAndMalformedRequestsAreRefused(HookJar jar) throws Exception {
        jar.run(new byte[0], "codex");
        ResidentAddress resident = theResident(jar);
        String credentials = Base64.getEncoder().encodeToString(("hook:" + resident.secret()).getBytes(StandardCharsets.UTF_8));

        try (Socket socket = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), resident.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("POST /call HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Basic " + credentials
                    + "\r\nContent-Length: 99999999999\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String status = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(socket.getInputStream().readNBytes(12))).toString();
            assertThat(status).isEqualTo("HTTP/1.1 413");
        }
        assertThat(post(resident, "/call", "hook", resident.secret(), new byte[] {1, 2, 3}).statusCode()).isEqualTo(400);
        assertThat(post(resident, "/call", "hook", resident.secret(), new byte[0]).statusCode()).isEqualTo(400);
        assertThat(Residents.isLive(resident)).isTrue();
        answered(jar);
    }

    // ---- R9-D0096-N: the loopback only ----

    @Test
    @DisplayName("R9-D0096-N the resident program accepts connections on 127.0.0.1 only: a connection to the port on any other address of this machine is refused")
    void listensOnTheLoopbackOnly(HookJar jar) throws Exception {
        answered(jar);
        ResidentAddress resident = theResident(jar);
        List<InetAddress> others = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                .filter(nic -> {
                    try {
                        return nic.isUp() && !nic.isLoopback();
                    } catch (java.net.SocketException e) {
                        return false;
                    }
                }).flatMap(nic -> Collections.list(nic.getInetAddresses()).stream())
                .filter(address -> address instanceof java.net.Inet4Address && !address.isLoopbackAddress()).toList();
        Assumptions.assumeFalse(others.isEmpty(), "NOT_RUN: this machine has no network address besides the loopback");

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), resident.port()), 5_000);
            assertThat(socket.isConnected()).isTrue();
        }
        for (InetAddress other : others) {
            try (Socket socket = new Socket()) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> socket.connect(new InetSocketAddress(other, resident.port()), 3_000))
                        .as("a connection to " + other.getHostAddress()).isInstanceOf(java.io.IOException.class);
            }
        }
    }

    // ---- R9-D0096-G: owner-only state, capability not operating system name ----

    private static FileProtection protectionOfThisFileSystem() throws IOException {
        var views = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews();
        if (views.contains("posix")) {
            return new FileProtection.Posix();
        }
        if (views.contains("acl")) {
            return new FileProtection.Acl(java.nio.file.FileSystems.getDefault().getUserPrincipalLookupService()
                    .lookupPrincipalByName(ProcessAccount.name().orElse("")));
        }
        return new FileProtection.Unprotected();
    }

    @Test
    @DisplayName("R9-D0096-G the state folder, its address file, its lock files, its log and the folder of the program copy are open to no other account (POSIX rwx------ / rw-------, or an ACL with the owner only on a file system that has ACLs)")
    void theStateIsOwnerOnly(HookJar jar, TestWorkspace workspace) throws Exception {
        answered(jar);
        StateFolder state = stateOf(jar);
        Path runtime = workspace.home().resolve(".agent-ledger-mcp").resolve("resident-runtime");
        List<Path> paths = List.of(workspace.home().resolve(".agent-ledger-mcp"), state.directory().getParent(), state.directory(),
                state.addressFile(), state.startupLock(), state.instanceLock(), state.logFile(), runtime,
                runtime.resolve(theResident(jar).build()));
        FileProtection protection = protectionOfThisFileSystem();
        for (Path path : paths) {
            assertThat(path).exists();
            assertThat(protection.openExposure(path)).as("exposure of " + path).isEmpty();
        }
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(state.addressFile())).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            assertThat(Files.getPosixFilePermissions(state.directory())).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        }
    }

    // ---- R9-D0096-O: the idle end ----

    @Test
    @DisplayName("R9-D0096-O a resident program that nobody calls for the idle timeout ends by itself and removes its address file, and the next call starts a new one that answers; a program that is called more often than the timeout keeps running")
    void anIdleResidentProgramEnds(HookJar jar) throws Exception {
        Map<String, String> shortIdle = Map.of("LEDGER_HOOK_RESIDENT_IDLE_TIMEOUT", "PT3S");
        assertThat(jar.start(HookJar.JAVA, List.of(), shortIdle, stdin, "codex").run().exit()).isZero();
        ResidentAddress first = theResident(jar);

        for (int i = 0; i < 4; i++) {
            Thread.sleep(1_500);
            assertThat(jar.start(HookJar.JAVA, List.of(), shortIdle, stdin, "codex").run().exit()).isZero();
        }
        assertThat(theResident(jar)).as("calls more often than the timeout keep it running").isEqualTo(first);

        assertThat(Residents.awaitGone(first, 20_000)).as("nobody calls: the program ends by itself").isTrue();
        assertThat(stateOf(jar).addressFile()).as("and removes its address file").doesNotExist();
        assertThat(Residents.running(jar.home())).isEmpty();

        answered(jar);
        assertThat(theResident(jar).pid()).isNotEqualTo(first.pid());
    }

    // ---- R9-D0096-P: no program, no start ----

    @Test
    @DisplayName("R9-D0096-P with no resident program file next to the client the call fails the way every unanswerable call does (exit 2, a line on stderr, nothing on stdout) and starts nothing")
    void noProgramFileIsAFailedCall(HookJar original, TestWorkspace workspace) throws Exception {
        Path lib = pluginCopy(workspace);
        Files.delete(lib.resolve("ledger-hook-resident.jar"));
        Run run = original.using(lib.resolve("ledger-client-hook.jar")).run(stdin, "codex");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.stdout()).isEmpty();
        assertThat(run.err()).startsWith("ledger: the hook program is not available").endsWith("\n");
        assertThat(Residents.startsOf(original.home())).as("nothing was started").isZero();
    }

    @Test
    @DisplayName("R9-D0096-P a resident program file that cannot run (not a jar) makes the call fail at once, not after the time limit: exit 2, the message names the log of the state folder, nothing on stdout")
    void aProgramThatCannotRunIsAFailedCall(HookJar original, TestWorkspace workspace) throws Exception {
        Path lib = pluginCopy(workspace);
        Files.writeString(lib.resolve("ledger-hook-resident.jar"), "this is not a jar");
        HookJar.Timed timed = original.using(lib.resolve("ledger-client-hook.jar")).start(stdin, "codex");

        assertThat(timed.run().exit()).isEqualTo(2);
        assertThat(timed.run().stdout()).isEmpty();
        assertThat(timed.run().err()).contains("ended while starting").contains("resident.log");
        assertThat(timed.millis()).as("the client saw the program end and did not wait out its deadline").isLessThan(8_000);
        assertThat(Residents.running(original.home())).isEmpty();
    }

    // ---- R9-D0096-Q: the stop command ----

    @Test
    @DisplayName("R9-D0096-Q the word stop ends the running resident program (exit 0, no output) and does nothing, with exit 0, when none runs")
    void theStopCommand(HookJar jar) throws Exception {
        Run nothing = jar.run(new byte[0], "stop");
        assertThat(nothing.exit()).isZero();
        assertThat(nothing.stdout()).isEmpty();
        assertThat(nothing.stderr()).isEmpty();

        answered(jar);
        ResidentAddress resident = theResident(jar);
        Run stopped = jar.run(new byte[0], "stop");
        assertThat(stopped.exit()).isZero();
        assertThat(stopped.stdout()).isEmpty();
        assertThat(stopped.stderr()).isEmpty();
        assertThat(Residents.isLive(resident)).isFalse();
        assertThat(stateOf(jar).addressFile()).doesNotExist();

        answered(jar);
        assertThat(theResident(jar).pid()).isNotEqualTo(resident.pid());
    }

    // ---- R9-D0096-R: the guard of the instance lock ----

    @Test
    @DisplayName("R9-D0096-R a second resident program started on the same state folder ends at once (the instance lock), and leaves the address file and the first program as they were")
    void aSecondProgramOnTheSameStateFolderEndsAtOnce(HookJar jar, TestWorkspace workspace) throws Exception {
        answered(jar);
        ResidentAddress first = theResident(jar);
        try (ResidentProbe second = ResidentProbe.start(workspace, stateOf(jar).directory(), List.of("-XX:+UseSerialGC"),
                Map.of())) {
            assertThat(second.awaitExit(Duration.ofSeconds(60))).as("the second program ended").isTrue();
            assertThat(second.process().exitValue()).isZero();
        }
        assertThat(theResident(jar)).isEqualTo(first);
        answered(jar);
    }

    // ---- R9-D0096-S: JVM warnings never reach stdout ----

    @Test
    @DisplayName("R9-D0096-S a JVM that would print a log line of its own (a shared archive that cannot be used) prints none with the options of the host command: stdout holds only the answer; without -Xlog:disable the same JVM puts that line on stdout, which is why the option is there")
    void jvmLogLinesNeverReachStdout(HookJar jar, TestWorkspace workspace) throws Exception {
        Path missingArchive = workspace.root().resolve("no-such-archive.jsa");
        Run run = jar.start(HookJar.JAVA, List.of("-XX:SharedArchiveFile=" + missingArchive, "-Xshare:auto"), Map.of(), stdin, "codex")
                .run();
        assertThat(run.exit()).isZero();
        assertThat(run.err()).isEmpty();
        assertThat(run.out()).startsWith("{").doesNotContain("[error]").doesNotContain("[warning]");

        // the control: the same option without -Xlog:disable (what a command without it would do)
        Process control = new ProcessBuilder(HookJar.JAVA.toString(), "-XX:SharedArchiveFile=" + missingArchive, "-Xshare:auto", "-version")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String controlOut = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(control.getInputStream().readAllBytes())).toString();
        control.waitFor(60, TimeUnit.SECONDS);
        assertThat(controlOut).as("without -Xlog:disable a JVM log line lands on stdout").contains("[error][cds]");
    }

    // ---- R9-D0096-T: the key folder decides the program ----

    @Test
    @DisplayName("R9-D0096-T a client with another key folder gets another resident program (and another state folder): the program that holds the keys of one folder never signs with another")
    void oneResidentProgramPerKeyFolder(HookJar jar, TestWorkspace workspace) throws Exception {
        answered(jar);
        Path elsewhere = workspace.root().resolve("elsewhere");
        Run other = jar.start(HookJar.JAVA, List.of(), Map.of(HookJar.KEY_DIR_VARIABLE, elsewhere.toString()), stdin, "codex").run();
        assertThat(other.exit()).isZero();

        assertThat(Residents.running(jar.home())).hasSize(2);
        assertThat(Residents.stateFolders(jar.home())).hasSize(2);
        assertThat(Residents.startsOf(jar.home())).isEqualTo(2);
        try (Stream<Path> keys = Files.list(elsewhere)) {
            assertThat(keys.count()).isEqualTo(1);
        }
        assertThat(stateOf(jar).directory()).isNotEqualTo(StateFolder.locate(jar.home().toString(), elsewhere.toString()).directory());
    }
}
