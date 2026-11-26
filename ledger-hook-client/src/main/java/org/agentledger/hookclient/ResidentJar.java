// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hookclient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * The program file of the resident hook program, {@code ledger-hook-resident.jar}, which lies next to the light client in the
 * plugin folder, and its build: the identity that tells a resident program of one release from another. The build is the
 * protocol number, the size and the modification time of the file, so an updated plugin (another file) is another build; the
 * light client reads it for every call from the file system (one stat), with no hashing.
 *
 * @param file  the program file in the plugin folder
 * @param build the identity of the file
 */
public record ResidentJar(Path file, String build) {

    /** The file name of the resident program next to the client. */
    public static final String FILE_NAME = "ledger-hook-resident.jar";

    /** The number of the wire protocol: a client and a program that differ in it must not talk, so it is part of the build. */
    static final int PROTOCOL = 1;

    /** The program file that lies in the folder of the client jar. */
    public static ResidentJar besideClient(Path clientJar) throws IOException {
        Path file = clientJar.resolveSibling(FILE_NAME);
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        return new ResidentJar(file, PROTOCOL + "-" + attributes.size() + "-" + attributes.lastModifiedTime().toMillis());
    }
}
