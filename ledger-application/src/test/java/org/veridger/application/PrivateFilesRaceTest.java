// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.attachment.PrivateFiles;
import org.veridger.application.support.TestTrees;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;

/**
 * R9-D0075: two requests that make the same missing folder at the same moment both succeed. The other request makes the folder between
 * the check that it is missing and the creation; the test double makes it at exactly that point, the moment the attributes of the new
 * folder are asked for, so no timing is involved.
 */
class PrivateFilesRaceTest {

    private Path root;

    @BeforeEach
    void open() {
        root = TestTrees.create("ledger-private-race-");
    }

    @AfterEach
    void close() {
        TestTrees.delete(root);
    }

    /** A {@link PrivateFiles} in which another request makes {@code target} (a folder, or a file) just before this one creates it. */
    private static PrivateFiles otherRequestFirst(Path target, boolean asFile) {
        AtomicBoolean done = new AtomicBoolean();
        return new PrivateFiles() {
            @Override
            public FileAttribute<?>[] attributesFor(Path near, boolean directory) {
                FileAttribute<?>[] attributes = super.attributesFor(near, directory);
                if (directory && near.equals(target) && done.compareAndSet(false, true)) {
                    try {
                        if (asFile) {
                            Files.createFile(target);
                        } else {
                            Files.createDirectory(target, attributes);
                        }
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                }
                return attributes;
            }
        };
    }

    @Test
    @DisplayName("R9-D0075: a folder that another request made between the check and the creation is used, not an ARTIFACT_STORAGE_IO_ERROR")
    void r9D0075_aFolderMadeByAnotherRequestIsUsed() {
        Path folder = root.resolve("attachments").resolve("ab");

        otherRequestFirst(folder, false).ensureDirectory(folder);

        assertThat(folder).isDirectory();
    }

    @Test
    @DisplayName("R9-D0075: what another request made in its place and is no folder is still ARTIFACT_STORAGE_UNSAFE")
    void r9D0075_aFileInThePlaceOfTheFolderIsStillUnsafe() {
        Path folder = root.resolve("attachments").resolve("cd");

        assertThatThrownBy(() -> otherRequestFirst(folder, true).ensureDirectory(folder)).isInstanceOfSatisfying(
                InfrastructureException.class, failure -> assertThat(failure.code()).isEqualTo(InfrastructureCode.ARTIFACT_STORAGE_UNSAFE));
    }
}
