// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook.keys;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import org.pgledger.hook.keys.SessionKeyException.Reason;

/**
 * Opens an existing key file and reads its bytes (F8): the file is opened once and read from that opened channel; its
 * attributes are read right after the open, before a byte is read. There is no second open of the path
 * ({@link Files#newByteChannel(Path, java.util.Set, java.nio.file.attribute.FileAttribute[])}, the JDK API for exactly
 * this). The channel cannot give its own attributes in the JDK, so the attributes are read by path just after the open.
 *
 * <p>Who may reach the file, and who owns it, is not looked at, and a link is followed to the file it points at: the
 * personal edition trusts the whole PC (see {@link Protection}). Only a path that is not a regular file, a file that is
 * empty and a file that is too large are refused.
 */
final class KeyFileReader {

    private static final int MAX_FILE_BYTES = 16 * 1024;

    /**
     * The bytes of the key file; the caller zeroes them when it has parsed them.
     *
     * @throws SessionKeyException {@link Reason#NOT_A_REGULAR_FILE}, or {@link Reason#CORRUPT} (empty or too large)
     * @throws IOException         if the file cannot be opened or read
     */
    byte[] read(Path file) throws IOException {
        try (SeekableByteChannel channel = open(file)) {
            // the attributes of the file that was just opened (see the class comment), before a byte is read
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw new SessionKeyException(Reason.NOT_A_REGULAR_FILE);
            }
            if (attributes.size() == 0 || attributes.size() > MAX_FILE_BYTES) {
                throw new SessionKeyException(Reason.CORRUPT);
            }
            return readAll(channel, (int) attributes.size());
        }
    }

    /** The one open of the key file; a directory that cannot be opened as a file is a path of the wrong kind. */
    private static SeekableByteChannel open(Path file) throws IOException {
        try {
            return Files.newByteChannel(file, StandardOpenOption.READ);
        } catch (FileSystemException failure) {
            if (Files.isDirectory(file)) {
                throw new SessionKeyException(Reason.NOT_A_REGULAR_FILE, failure);
            }
            throw failure;
        }
    }

    private static byte[] readAll(SeekableByteChannel channel, int size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        int read = 0;
        // a read may return fewer bytes than asked for; -1 is the end of the file
        while (buffer.hasRemaining() && read >= 0) {
            read = channel.read(buffer);
        }
        byte[] content = Arrays.copyOf(buffer.array(), buffer.position());
        Arrays.fill(buffer.array(), (byte) 0);
        return content;
    }
}
