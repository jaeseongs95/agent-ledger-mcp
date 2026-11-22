// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.database.schema.UtcSessionCheck;
import org.pgledger.database.time.JvmZone;

/**
 * R9-D0090: the products run in UTC whatever time zone the host has. The default zone of a JVM comes from the operating system
 * (or from {@code -Duser.timezone}); the database layer refuses to run in another one ({@code JVM_ZONE_NOT_UTC}), so a command
 * given as the README gives it ({@code java -jar ledger-cli.jar <command>}) failed on every host whose zone is not UTC. The entry
 * point of each program now makes the zone of its own JVM UTC before anything reads it ({@link JvmZone#pinUtc}).
 *
 * <p>The zone of a JVM is read once, so the test starts a child JVM in a host zone that is not UTC ({@code Asia/Seoul}, given by
 * the same property the operating system's zone becomes), which runs what an entry point runs.
 */
class JvmZoneTest {

    /** What the entry point of a program does first, and then the check of the database layer. */
    public static final class EntryPoint {

        public static void main(String[] arguments) {
            JvmZone.pinUtc();
            UtcSessionCheck.verifyJvmZone();
            System.out.println("UTC");
        }
    }

    /** What an entry point that leaves the zone to the host does (the program as it was). */
    public static final class EntryPointWithoutPin {

        public static void main(String[] arguments) {
            UtcSessionCheck.verifyJvmZone();
            System.out.println("UTC");
        }
    }

    private record Ran(int exit, String output) {
    }

    private static Ran runInHostZone(String zone, Class<?> main) throws IOException, InterruptedException {
        Path java = Path.of(ProcessHandle.current().info().command().orElseThrow());
        Process process = new ProcessBuilder(List.of(java.toString(), "-Duser.timezone=" + zone, "-cp",
                System.getProperty("java.class.path"), main.getName())).redirectErrorStream(true).start();
        String output = process.inputReader(StandardCharsets.UTF_8).lines().collect(Collectors.joining("\n"));
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Ran(process.exitValue(), output);
    }

    @Test
    @DisplayName("R9-D0090 a program whose host zone is Asia/Seoul runs in UTC after the entry point has pinned the zone: the database layer's JVM zone check passes")
    void theEntryPointPinsUtcWhateverTheHostZoneIs() throws Exception {
        Ran ran = runInHostZone("Asia/Seoul", EntryPoint.class);

        assertThat(ran.exit()).as(ran.output()).isZero();
        assertThat(ran.output()).contains("UTC");
    }

    @Test
    @DisplayName("R9-D0090 the check itself is kept: a JVM whose zone is not UTC and that did not pin it is still refused (JVM_ZONE_NOT_UTC)")
    void theCheckStillRefusesAnUnpinnedZone() throws Exception {
        Ran ran = runInHostZone("Asia/Seoul", EntryPointWithoutPin.class);

        assertThat(ran.exit()).isNotZero();
        assertThat(ran.output()).contains("JVM_ZONE_NOT_UTC");
    }
}
