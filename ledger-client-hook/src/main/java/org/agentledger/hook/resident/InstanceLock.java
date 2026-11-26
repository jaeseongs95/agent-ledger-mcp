// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook.resident;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import org.agentledger.hookclient.OwnerOnlyFiles;

/**
 * The lock that the resident program holds for its whole life, on a file of the state folder: the second guard against two
 * programs on one state folder (the first is the startup lock of the client that starts a program). The operating system
 * releases it when the process ends, however it ends, so a program that was killed leaves no lock behind.
 */
final class InstanceLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Takes the lock on this file if nobody holds it; {@link #held} tells whether it did. */
    static InstanceLock take(Path file, OwnerOnlyFiles files) throws IOException {
        FileChannel channel = files.openLockFile(file);
        FileLock lock = null;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException sameProcess) {
            // this process holds the lock already: the same as another program holding it
            lock = null;
        } catch (IOException failure) {
            channel.close();
            throw failure;
        }
        return new InstanceLock(channel, lock);
    }

    /** Whether this call took the lock: false if another program holds it. */
    boolean held() {
        return lock != null;
    }

    @Override
    public void close() throws IOException {
        try {
            if (lock != null) {
                lock.release();
            }
        } finally {
            channel.close();
        }
    }
}
