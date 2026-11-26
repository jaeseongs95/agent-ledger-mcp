// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook.resident;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.agentledger.hookclient.HookAnswer;
import org.agentledger.hookclient.HookCall;
import org.agentledger.hookclient.Wire;

/**
 * {@code POST /call}: a hook call in the body, its answer in the body of the response. Only the form of the request is checked
 * here (the method, a declared length within the limit, a body that is a call); the secret was checked before the request got
 * this far ({@link SecretAuthenticator}). The size is checked from the declared length before a byte of the body is read.
 */
final class CallHandler implements HttpHandler {

    private static final int OK = 200;
    private static final int BAD_REQUEST = 400;
    private static final int METHOD_NOT_ALLOWED = 405;
    private static final int LENGTH_REQUIRED = 411;
    private static final int TOO_LARGE = 413;

    private final HookService service;
    private final Wire wire;
    private final IdleWatch idle;

    CallHandler(HookService service, Wire wire, IdleWatch idle) {
        this.service = service;
        this.wire = wire;
        this.idle = idle;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        idle.begin();
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                reply(exchange, METHOD_NOT_ALLOWED, new byte[0]);
                return;
            }
            String declared = exchange.getRequestHeaders().getFirst("Content-Length");
            if (declared == null) {
                reply(exchange, LENGTH_REQUIRED, new byte[0]);
                return;
            }
            if (!fits(declared)) {
                reply(exchange, TOO_LARGE, new byte[0]);
                return;
            }
            HookCall call;
            try (InputStream body = exchange.getRequestBody()) {
                call = wire.decodeCall(body);
            } catch (IOException malformed) {
                reply(exchange, BAD_REQUEST, new byte[0]);
                return;
            }
            HookAnswer answer = service.answer(call);
            reply(exchange, OK, wire.encode(answer));
        } finally {
            idle.end();
        }
    }

    private static boolean fits(String declaredLength) {
        try {
            long length = Long.parseLong(declaredLength.strip());
            return length >= 0 && length <= Wire.MAX_REQUEST_BYTES;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
