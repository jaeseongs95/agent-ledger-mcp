// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hookclient;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * The copy of the resident program that actually runs. The running program keeps its jar open, and an open jar cannot be
 * replaced or deleted on every file system the product supports; run from the plugin folder it would block the update or the
 * removal of the plugin. So the client copies the file to a folder of its own,
 * {@code <home>/.agent-ledger-mcp/resident-runtime/<build>/}, and starts the program from the copy. There is one copy per build,
 * shared by the programs of every key folder; a copy is made once, into a file of its own that is moved into place in one step,
 * so a copy that exists is complete.
 */
public final class ResidentRuntime {

    private static final String FOLDER = "resident-runtime";

    private final Path root;
    private final OwnerOnlyFiles files;

    public ResidentRuntime(Path home, OwnerOnlyFiles files) {
        this.root = home.resolve(".agent-ledger-mcp").resolve(FOLDER);
        this.files = files;
    }

    /** The copy of the program of this build; made now if it does not exist. */
    public Path install(ResidentJar jar) throws IOException {
        Path folder = root.resolve(jar.build());
        Path copy = folder.resolve(ResidentJar.FILE_NAME);
        if (Files.isRegularFile(copy)) {
            return copy;
        }
        files.createDirectories(folder);
        Path partial = folder
                .resolve(ResidentJar.FILE_NAME + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        try {
            Files.copy(jar.file(), partial);
            Files.move(partial, copy, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            Files.deleteIfExists(partial);
            if (Files.isRegularFile(copy)) {
                // another client made the copy first: it is complete, because it was moved into place in one step
                return copy;
            }
            throw failure;
        }
        return copy;
    }

    /**
     * Removes the copies of other builds, as far as nobody holds them (a running program of an older build keeps its copy; that
     * one stays until the next start). Returns how many copies could not be removed.
     */
    public int pruneOtherBuilds(String keepBuild) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int kept = 0;
        try (DirectoryStream<Path> builds = Files.newDirectoryStream(root)) {
            for (Path build : builds) {
                if (!Objects.equals(keepBuild, build.getFileName().toString()) && !remove(build)) {
                    kept++;
                }
            }
        }
        return kept;
    }

    private static boolean remove(Path build) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(build)) {
            for (Path entry : entries) {
                Files.delete(entry);
            }
            Files.delete(build);
            return true;
        } catch (IOException inUse) {
            // a running program of that build holds its file: it stays until it ends
            return false;
        }
    }
}
