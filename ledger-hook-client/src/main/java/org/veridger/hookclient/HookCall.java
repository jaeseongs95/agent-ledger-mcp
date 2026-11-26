// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hookclient;

import java.util.List;

/**
 * What a host asked of the hook: the words of the command line and every byte of the standard input. This is the
 * request that travels from the light client to the resident program.
 *
 * @param arguments the command line words after {@code java -jar ledger-client-hook.jar}
 * @param stdin     the bytes the host wrote to the standard input, as they came
 */
public record HookCall(List<String> arguments, byte[] stdin) {}
