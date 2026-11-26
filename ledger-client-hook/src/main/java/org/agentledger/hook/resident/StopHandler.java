// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook.resident;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * {@code POST /stop}: the program ends. It answers first and then lets the main thread end the program, so that the client that
 * asked gets its answer. A newer client of another build asks this to replace the program; the owner can ask it with
 * {@code java -jar ledger-client-hook.jar stop}.
 */
final class StopHandler implements HttpHandler {

    private static final int OK = 200;
    private static final int METHOD_NOT_ALLOWED = 405;

    private final CountDownLatch stopRequested;

    StopHandler(CountDownLatch stopRequested) {
        this.stopRequested = stopRequested;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        boolean post = "POST".equals(exchange.getRequestMethod());
        try (exchange) {
            exchange.sendResponseHeaders(post ? OK : METHOD_NOT_ALLOWED, -1);
        }
        if (post) {
            stopRequested.countDown();
        }
    }
}
