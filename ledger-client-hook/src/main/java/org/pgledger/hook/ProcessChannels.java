// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import java.io.InputStream;
import java.io.PrintStream;
import org.springframework.stereotype.Component;

/**
 * The standard streams of the process: the host JSON in on stdin, the answer out on stdout (nothing else is ever written
 * there: the host reads all of it as its protocol), a message for the user on stderr. The one class of the hook, with
 * {@link Main}, that touches {@link System}; the code that decides gets the streams passed in.
 */
@Component
final class ProcessChannels {

    InputStream in() {
        return System.in;
    }

    PrintStream out() {
        return System.out;
    }

    PrintStream err() {
        return System.err;
    }

    /**
     * Whether a write to stdout failed. A {@link PrintStream} never throws: it remembers the failure, which this asks
     * for after flushing what is still buffered.
     */
    boolean outputFailed() {
        PrintStream stdout = out();
        stdout.flush();
        return stdout.checkError();
    }
}
