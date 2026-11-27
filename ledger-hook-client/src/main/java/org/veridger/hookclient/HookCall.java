// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hookclient;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * What a host asked of the hook: the words of the command line and every byte of the standard input. This is the
 * request that travels from the light client to the resident program.
 *
 * @param arguments the command line words after {@code java -jar ledger-client-hook.jar}
 * @param stdin     the bytes the host wrote to the standard input, as they came
 */
public record HookCall(List<String> arguments, byte[] stdin) {

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof HookCall other
                && Objects.equals(arguments, other.arguments)
                && Arrays.equals(stdin, other.stdin));
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hashCode(arguments) + Arrays.hashCode(stdin);
    }

    @Override
    public String toString() {
        return "HookCall[arguments=" + arguments + ", stdin=" + Arrays.toString(stdin) + "]";
    }
}
