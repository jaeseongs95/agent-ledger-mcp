// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hookclient;

/**
 * What the hook answers: the exit code of the process and the bytes for the standard output and the standard error. The
 * contract with the host (0 after a JSON answer or silence, 2 for a usage error, an unusable key or a blocking failure) is the
 * one the hook always had: the resident program makes this value, the light client only prints it.
 *
 * @param exit   the exit code of the process
 * @param stdout the bytes for the standard output (the host reads all of them as its protocol)
 * @param stderr the bytes for the standard error
 */
public record HookAnswer(int exit, byte[] stdout, byte[] stderr) {}
