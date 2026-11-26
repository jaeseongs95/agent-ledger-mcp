// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The temporary folders of the attachment tests: made with a name of their own and removed with every file in them. A finished file
 * of the attachments is read only, which a file system that has only the DOS attribute keeps from deleting it, so the attribute is
 * cleared before each file is deleted.
 */
public final class TestTrees {

    private static final Logger LOG = LoggerFactory.getLogger(TestTrees.class);

    private TestTrees() {
    }

    /** A new empty folder in the temporary folder of the machine (its real path, so that two spellings of it never differ). */
    public static Path create(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toRealPath();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Removes the folder and everything in it; a failure is logged, never thrown (the test is over). */
    public static void delete(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (file.getFileSystem().supportedFileAttributeViews().contains("dos")) {
                        Files.getFileAttributeView(file, DosFileAttributeView.class).setReadOnly(false);
                    }
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path folder, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(folder);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            LOG.warn("the temporary folder {} was not removed", root, failure);
        }
    }
}
