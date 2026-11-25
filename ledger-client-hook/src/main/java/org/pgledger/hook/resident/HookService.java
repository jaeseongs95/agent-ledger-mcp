// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook.resident;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.pgledger.hook.HookCli;
import org.pgledger.hookclient.HookAnswer;
import org.pgledger.hookclient.HookCall;
import org.springframework.stereotype.Component;

/**
 * One hook call of the resident program: the call that a client sent, answered by {@link HookCli} exactly as the program that
 * was started for every call answered it (the same decisions, the same bytes on the two output streams, the same exit code).
 * This is the connection between the transport and the hook; it knows no HTTP and no state folder.
 */
@Component
public final class HookService {

    private static final int EXIT_FAILED = 2;
    private static final byte[] TOO_LARGE =
            "ledger: the call is too large for the resident hook program\n".getBytes(StandardCharsets.UTF_8);

    private final HookCli cli;

    HookService(HookCli cli) {
        this.cli = cli;
    }

    /** The answer to this call. */
    public HookAnswer answer(HookCall call) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            int exit = cli.run(call.arguments().toArray(String[]::new), new ByteArrayInputStream(call.stdin()), out, err);
            return new HookAnswer(exit, out.toByteArray(), err.toByteArray());
        } catch (OutOfMemoryError tooLarge) {
            // what the call needs does not fit in the memory of the program: this call fails, the program goes on
            return new HookAnswer(EXIT_FAILED, new byte[0], TOO_LARGE);
        }
    }
}
