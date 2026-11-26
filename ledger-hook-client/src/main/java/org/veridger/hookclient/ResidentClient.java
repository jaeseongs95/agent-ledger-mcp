// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hookclient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Talks to a running resident program: one HTTP POST over the loopback, {@code /call} with the request body of {@link Wire}, or
 * {@code /stop}. The address is always {@code 127.0.0.1} and the request never goes through a proxy. The secret of the address
 * file is the password of HTTP Basic authentication, sent with the first request (there is no challenge round); the program
 * compares it in constant time. {@code HttpURLConnection} is the JDK client that costs the least to load: the other one,
 * {@code java.net.http.HttpClient}, builds an SSL context when it is created.
 *
 * <p>There is no retry here. A connection that cannot be made, or an answer 401, means the call was not taken
 * ({@link ResidentUnreachableException}); the caller finds the program anew and asks once more.
 */
public final class ResidentClient {

    static final String HOST = "127.0.0.1";
    private static final String USER = "hook";
    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int UNAUTHORIZED = 401;
    private static final int OK = 200;

    private final Wire wire;
    private final int readTimeoutMillis;

    /**
     * @param readTimeoutMillis how long a call waits for its answer; shorter than the 10 seconds the hosts allow a hook, so
     *                          that the client can say what failed before the host ends it without a word
     */
    public ResidentClient(Wire wire, int readTimeoutMillis) {
        this.wire = wire;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /** The answer of the program to this call. */
    public HookAnswer call(ResidentAddress address, HookCall call) throws ResidentException {
        byte[] body;
        try {
            body = wire.encode(call);
        } catch (IOException tooLarge) {
            throw new ResidentException("the call cannot be encoded", tooLarge);
        }
        byte[] answer = post(address, "/call", body);
        try {
            return wire.decodeAnswer(new ByteArrayInputStream(answer));
        } catch (IOException malformed) {
            throw new ResidentException("the resident hook program sent an answer that is not understood", malformed);
        }
    }

    /** Asks the program to end; it answers before it ends. */
    public void stop(ResidentAddress address) throws ResidentException {
        post(address, "/stop", new byte[0]);
    }

    private byte[] post(ResidentAddress address, String path, byte[] body) throws ResidentException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create("http://" + HOST + ":" + address.port() + path).toURL()
                    .openConnection(Proxy.NO_PROXY);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((USER + ":" + address.secret()).getBytes(StandardCharsets.UTF_8)));
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int status = connection.getResponseCode();
            if (status == UNAUTHORIZED) {
                throw new ResidentUnreachableException("the resident hook program at the address does not know this client");
            }
            if (status != OK) {
                throw new ResidentException("the resident hook program answered with the status " + status);
            }
            try (InputStream in = connection.getInputStream()) {
                return in.readNBytes(Wire.MAX_ANSWER_BYTES + 64);
            }
        } catch (ConnectException | HttpRetryException unreachable) {
            // a refused connection, or the 401 of the streaming mode: the call was not taken
            throw new ResidentUnreachableException("the resident hook program cannot be reached", unreachable);
        } catch (SocketTimeoutException slow) {
            throw new ResidentException("the resident hook program did not answer in time", slow);
        } catch (IOException failure) {
            throw new ResidentException("the call to the resident hook program failed: " + failure.getClass().getSimpleName(),
                    failure);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
