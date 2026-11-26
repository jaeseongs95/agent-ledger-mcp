// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hookclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * One run of the light client: the words of the command line, the standard input of the host and the two output streams in,
 * the exit code out. It reads the input, has {@link HookClient} carry the call to the resident program, and prints what that
 * answers as it is: the bytes on the standard output are the answer of the hook, nothing is added to them and no log line can
 * reach them (the client logs nothing). The meaning of a call (the usage, the attestation, the refusals) is the resident
 * program's; the client knows one word of its own, {@code stop}.
 *
 * <p>The contract with the host stays as it always was: exit 0 after a JSON answer or silence, 2 when the call cannot be
 * answered. A resident program that cannot be reached or started is such a case, with a line on the standard error that names it.
 */
public final class ClientCommand {

    /** The word that ends the resident program of the key folder. */
    static final String STOP = "stop";

    private static final int EXIT_FAILED = 2;
    private static final byte[] OUTPUT_FAILED = "ledger: could not write the hook output\n".getBytes(StandardCharsets.UTF_8);

    private final HookClient hook;

    public ClientCommand(HookClient hook) {
        this.hook = hook;
    }

    /** Runs the command and returns the exit code of the process. */
    public int run(String[] arguments, InputStream in, PrintStream out, PrintStream err) {
        try {
            if (arguments.length == 1 && STOP.equals(arguments[0])) {
                hook.stop();
                return 0;
            }
            HookAnswer answer = hook.call(new HookCall(List.of(arguments), in.readAllBytes()));
            out.write(answer.stdout(), 0, answer.stdout().length);
            out.flush();
            err.write(answer.stderr(), 0, answer.stderr().length);
            err.flush();
            if (answer.exit() == 0 && out.checkError()) {
                // a write that failed (a closed pipe) turns a good answer into a failure, as it always did
                err.write(OUTPUT_FAILED, 0, OUTPUT_FAILED.length);
                err.flush();
                return EXIT_FAILED;
            }
            return answer.exit();
        } catch (ResidentException unavailable) {
            return fail(err, "ledger: the hook program is not available: " + unavailable.getMessage());
        } catch (IOException failure) {
            return fail(err, "ledger: hook I/O failure: " + failure.getClass().getSimpleName());
        }
    }

    private static int fail(PrintStream err, String message) {
        byte[] bytes = (message + "\n").getBytes(StandardCharsets.UTF_8);
        err.write(bytes, 0, bytes.length);
        err.flush();
        return EXIT_FAILED;
    }
}
