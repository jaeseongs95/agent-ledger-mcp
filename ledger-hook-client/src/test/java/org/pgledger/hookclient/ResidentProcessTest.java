// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hookclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R9-D0096-H (pid): a process is the program that wrote the address file only if its pid and its start time are the ones in the file. */
class ResidentProcessTest {

    private final ResidentProcess processes = new ResidentProcess();
    private final String secret = ResidentAddress.newSecret();

    private ResidentAddress address(long pid, long start) {
        return new ResidentAddress(1, pid, start, "b", secret);
    }

    @Test
    @DisplayName("R9-D0096-H this process is live with its own start time, and not live with the start time of another moment (a reused pid)")
    void aReusedPidIsNotTheProgram() {
        long pid = ProcessHandle.current().pid();
        long start = processes.startOfCurrent();
        assertThat(start).as("the platform tells the start time of the process").isPositive();

        assertThat(processes.isLive(address(pid, start))).isTrue();
        assertThat(processes.isLive(address(pid, start + 1))).as("the number is the same, the process is another").isFalse();
        assertThat(processes.isLive(address(pid, start - 60_000))).isFalse();
        assertThat(processes.isLive(address(pid, 0))).as("no start time in the file: the pid is all there is").isTrue();
    }

    @Test
    @DisplayName("R9-D0096-H a process that has ended is not live, and destroy leaves a process with another start time alone")
    void aDeadPidIsNotLiveAndAStrangerIsNotDestroyed() throws IOException, InterruptedException {
        Process child = new ProcessBuilder(java(), "-version").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        ResidentAddress ofChild = address(child.pid(), child.info().startInstant().map(Instant::toEpochMilli).orElse(0L));
        assertThat(child.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(processes.isLive(ofChild)).isFalse();

        long pid = ProcessHandle.current().pid();
        processes.destroy(address(pid, processes.startOfCurrent() + 1));
        assertThat(ProcessHandle.current().isAlive()).as("this process was not destroyed").isTrue();
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
