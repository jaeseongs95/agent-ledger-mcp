// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hookclient;

import java.nio.file.Path;

/**
 * What the light client takes from its process: the account and its home folder, the key folder text of the environment, the JDK
 * that runs it and the jar it runs from. {@link Main} reads them once; everything else gets them here, so the rest of the client
 * is plain code that a test can run with any values.
 *
 * @param userHome      the home folder of the account ({@code user.home})
 * @param userName      the name of the account ({@code user.name}), for the owner-only entry on a file system with ACLs
 * @param keyFolderText the value of {@code LEDGER_HOOK_KEY_DIR}, {@code null} if it is not set
 * @param java          the {@code java} executable of the JDK that runs the client: the resident program runs on the same JDK
 * @param temporary     the folder the resident program starts in: a folder of the system that is never removed, so that the
 *                      program never holds a folder that somebody wants to delete (a started process holds its working folder)
 * @param clientJar     the jar the client runs from; the resident program lies next to it
 */
public record ClientEnvironment(String userHome, String userName, String keyFolderText, Path java, Path temporary,
        Path clientJar) {}
