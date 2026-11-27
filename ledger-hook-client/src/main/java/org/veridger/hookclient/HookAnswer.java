// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hookclient;

import java.util.Arrays;

/**
 * What the hook answers: the exit code of the process and the bytes for the standard output and the standard error. The
 * contract with the host (0 after a JSON answer or silence, 2 for a usage error, an unusable key or a blocking failure) is the
 * one the hook always had: the resident program makes this value, the light client only prints it.
 *
 * @param exit   the exit code of the process
 * @param stdout the bytes for the standard output (the host reads all of them as its protocol)
 * @param stderr the bytes for the standard error
 */
public record HookAnswer(int exit, byte[] stdout, byte[] stderr) {

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof HookAnswer other
                && exit == other.exit
                && Arrays.equals(stdout, other.stdout)
                && Arrays.equals(stderr, other.stderr));
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(exit);
        result = 31 * result + Arrays.hashCode(stdout);
        result = 31 * result + Arrays.hashCode(stderr);
        return result;
    }

    @Override
    public String toString() {
        return "HookAnswer[exit=" + exit + ", stdout=" + Arrays.toString(stdout)
                + ", stderr=" + Arrays.toString(stderr) + "]";
    }
}
