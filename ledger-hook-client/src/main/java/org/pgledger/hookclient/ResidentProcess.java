// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hookclient;

import java.time.Instant;

/**
 * Whether the process that an address file names is still the process that wrote it. A pid alone is not enough: the number is
 * reused after a process ends, so the address file also holds the start time of the process, and a process that has that pid
 * and another start time is a stranger. Both come from {@link ProcessHandle}, which every supported platform answers.
 */
public final class ResidentProcess {

    /** The start time of this process in epoch milliseconds, 0 where the platform does not tell it. */
    public long startOfCurrent() {
        return startOf(ProcessHandle.current());
    }

    /** Whether the process of this address is alive and is the one that started at the time the address names. */
    public boolean isLive(ResidentAddress address) {
        return ProcessHandle.of(address.pid()).map(handle -> sameProcess(handle, address.processStart())).orElse(false);
    }

    /** Ends the process of this address if it is live (it is the program that was started, see {@link #isLive}). */
    public void destroy(ResidentAddress address) {
        ProcessHandle.of(address.pid()).filter(handle -> sameProcess(handle, address.processStart()))
                .ifPresent(ProcessHandle::destroyForcibly);
    }

    private static boolean sameProcess(ProcessHandle handle, long processStart) {
        if (!handle.isAlive()) {
            return false;
        }
        // where the platform does not tell the start time, the pid is all there is to compare
        return processStart <= 0 || handle.info().startInstant().map(started -> started.toEpochMilli() == processStart)
                .orElse(true);
    }

    private static long startOf(ProcessHandle handle) {
        return handle.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
    }
}
