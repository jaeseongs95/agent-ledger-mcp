// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.support;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.pgledger.application.attachment.PrivateFiles;

/** Test double of {@link PrivateFiles} whose read channels count the bytes that are read from the disk. */
public class CountingFiles extends PrivateFiles {

    private final AtomicLong bytesRead = new AtomicLong();

    /** The bytes read through the channels this object opened for reading since the last {@link #reset()}. */
    public long bytesRead() {
        return bytesRead.get();
    }

    public void reset() {
        bytesRead.set(0);
    }

    @Override
    public FileChannel openRead(Path file) throws IOException {
        return new Counting(super.openRead(file), bytesRead);
    }

    private static final class Counting extends FileChannel {

        private final FileChannel inner;
        private final AtomicLong counter;

        Counting(FileChannel inner, AtomicLong counter) {
            this.inner = inner;
            this.counter = counter;
        }

        private int counted(int read) {
            if (read > 0) {
                counter.addAndGet(read);
            }
            return read;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return counted(inner.read(dst));
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            return counted(inner.read(dst, position));
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            long read = inner.read(dsts, offset, length);
            if (read > 0) {
                counter.addAndGet(read);
            }
            return read;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return inner.write(src);
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            return inner.write(srcs, offset, length);
        }

        @Override
        public long position() throws IOException {
            return inner.position();
        }

        @Override
        public FileChannel position(long newPosition) throws IOException {
            inner.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return inner.size();
        }

        @Override
        public FileChannel truncate(long size) throws IOException {
            inner.truncate(size);
            return this;
        }

        @Override
        public void force(boolean metaData) throws IOException {
            inner.force(metaData);
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            return inner.transferTo(position, count, target);
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
            return inner.transferFrom(src, position, count);
        }

        @Override
        public int write(ByteBuffer src, long position) throws IOException {
            return inner.write(src, position);
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            return inner.map(mode, position, size);
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            return inner.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return inner.tryLock(position, size, shared);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            inner.close();
        }
    }
}
