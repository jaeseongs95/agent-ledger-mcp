// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enclosing test relay that starts a child command using {@link ProcessBuilder} with piped stdout and stderr (not inherited
 * I/O). While running, any inheritable handles passed into this relay remain open in its handle table and test whether a
 * grandchild resident process accidentally inherits them.
 */
public final class RelayHook {

    private RelayHook() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length == 0) {
            System.err.println("RelayHook requires a child command");
            System.exit(2);
        }
        Process child = new ProcessBuilder(List.of(args))
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start();

        AtomicReference<IOException> streamError = new AtomicReference<>();
        Thread stdinPump = pump(System.in, child.getOutputStream(), true, streamError);
        Thread stdoutPump = pump(child.getInputStream(), System.out, false, streamError);
        Thread stderrPump = pump(child.getErrorStream(), System.err, false, streamError);

        int exit = child.waitFor();
        stdinPump.join();
        stdoutPump.join();
        stderrPump.join();
        System.out.flush();
        System.err.flush();
        IOException failure = streamError.get();
        if (failure != null) {
            throw failure;
        }
        System.exit(exit);
    }

    private static Thread pump(InputStream source, OutputStream target, boolean closeTarget,
            AtomicReference<IOException> streamError) {
        return Thread.ofPlatform().start(() -> {
            try {
                source.transferTo(target);
                if (closeTarget) {
                    target.close();
                }
            } catch (IOException failure) {
                streamError.compareAndSet(null, failure);
            }
        });
    }
}
