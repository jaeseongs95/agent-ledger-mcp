// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One hook invocation: {@code <client>} on the command line, host JSON on {@code in}, host JSON on {@code out}. The
 * streams are passed in and the codec and attestation rule ({@link LedgerHook}) are injected, so no class of the hook
 * touches the standard streams: the resident program gives it the bytes of a call and takes the bytes of the answer.
 * Every outcome, a failed read of stdin included, is a {@link Response}; the one complete {@code switch} in
 * {@link #render} turns it into the output and the exit code.
 */
@Component
public final class HookCli {

    private static final Logger LOG = LoggerFactory.getLogger(HookCli.class);
    /** Upper bound on stdin bytes accepted from the host process to prevent unbounded heap allocation. */
    static final int MAX_STDIN_BYTES = 32 * 1024 * 1024;
    private static final int EXIT_OK = 0;
    private static final int EXIT_BLOCK_OR_USAGE = 2;
    private static final String USAGE = "usage: java -jar ledger-client-hook.jar <" + Client.choices() + ">";

    private final JsonCodec codec;
    private final LedgerHook hook;
    private final int maxStdinBytes;

    @org.springframework.beans.factory.annotation.Autowired
    public HookCli(JsonCodec codec, LedgerHook hook) {
        this(codec, hook, MAX_STDIN_BYTES);
    }

    HookCli(JsonCodec codec, LedgerHook hook, int maxStdinBytes) {
        this.codec = codec;
        this.hook = hook;
        this.maxStdinBytes = maxStdinBytes;
    }

    /**
     * Runs the hook and returns the process exit code: 0 after a JSON answer or silence, 2 for a usage error, an I/O
     * failure, or when the host's contract asks for a blocking failure (Claude Code with malformed input).
     */
    public int run(String[] args, InputStream in, OutputStream out, OutputStream err) {
        Response response = answer(args, in);
        try {
            return render(response, out, err);
        } catch (IOException e) {
            return ioFailure(e);
        }
    }

    private Response answer(String[] args, InputStream in) {
        return Client.fromArgument(args.length == 1 ? args[0] : null)
                .map(client -> handle(client, in))
                .orElseGet(() -> new Response.Failure(USAGE));
    }

    private Response handle(Client client, InputStream in) {
        try {
            byte[] bytes = in.readNBytes(maxStdinBytes + 1);
            if (bytes.length > maxStdinBytes) {
                return new Response.InputFailure(
                        new IOException("stdin exceeds maximum allowed size of " + maxStdinBytes + " bytes"));
            }
            return hook.handle(client, bytes);
        } catch (IOException e) {
            return new Response.InputFailure(e);
        }
    }

    private int render(Response response, OutputStream out, OutputStream err) throws IOException {
        return switch (response) {
            case Response.Silent silent -> EXIT_OK;
            case Response.Document document -> {
                out.write(codec.write(document.body()));
                out.write('\n');
                out.flush();
                yield EXIT_OK;
            }
            case Response.Failure failure -> {
                writeLine(err, failure.message());
                yield EXIT_BLOCK_OR_USAGE;
            }
            case Response.InputFailure failure -> ioFailure(failure.cause());
        };
    }

    private static int ioFailure(IOException cause) {
        LOG.error("ledger: hook I/O failure", cause);
        return EXIT_BLOCK_OR_USAGE;
    }

    private static void writeLine(OutputStream stream, String text) throws IOException {
        stream.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        stream.flush();
    }
}
