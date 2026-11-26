// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hookclient;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Finds the resident program that is running for a state folder: the address file names it, and the process must be alive and be
 * the process that wrote the file (a stale file of a dead program, or of one whose pid is now another process, finds nothing).
 * The caller learns whether what runs is of its own build.
 */
public final class ResidentFinder {

    /** What a look at the state folder found. */
    public sealed interface Lookup permits Lookup.Usable, Lookup.OtherBuild, Lookup.Nothing {

        /** A live program of the build the client wants. */
        record Usable(ResidentAddress address) implements Lookup {}

        /** A live program of another build: it is to be replaced. */
        record OtherBuild(ResidentAddress address) implements Lookup {}

        /** No file, a file that is not an address, or an address whose process is gone. */
        record Nothing() implements Lookup {}
    }

    private final AddressFile addressFile;
    private final ResidentProcess processes;

    public ResidentFinder(AddressFile addressFile, ResidentProcess processes) {
        this.addressFile = addressFile;
        this.processes = processes;
    }

    /** The live program of the state folder, whatever its build. */
    public Optional<ResidentAddress> live() throws IOException {
        Optional<ResidentAddress> read = addressFile.read();
        if (read.isEmpty()) {
            return Optional.empty();
        }
        ResidentAddress address = read.orElseThrow();
        return processes.isLive(address) ? read : Optional.empty();
    }

    /** The live program of the state folder, told apart by its build. */
    public Lookup find(String wantedBuild) throws IOException {
        // Plain branches instead of Optional.map/orElseGet avoid LambdaMetafactory bootstrap on cold hook JVMs.
        Optional<ResidentAddress> found = live();
        if (found.isEmpty()) {
            return new Lookup.Nothing();
        }
        ResidentAddress address = found.orElseThrow();
        return Objects.equals(wantedBuild, address.build()) ? new Lookup.Usable(address)
                : new Lookup.OtherBuild(address);
    }
}
