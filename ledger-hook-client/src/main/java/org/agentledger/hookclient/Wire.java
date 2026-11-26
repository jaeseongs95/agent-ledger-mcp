// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hookclient;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The bytes that travel between the light client and the resident program: the body of the request ({@link HookCall}) and
 * the body of the answer ({@link HookAnswer}), each a plain sequence of length-prefixed values. Text is UTF-8, whatever the
 * default character set of either JVM is. Every length is checked before the bytes are read, so a body that lies about its
 * size is refused and not allocated.
 */
public final class Wire {

    /** The most words of a command line the resident program accepts (the hook takes one to three). */
    static final int MAX_ARGUMENTS = 16;

    /** The longest word of a command line, in bytes. */
    static final int MAX_ARGUMENT_BYTES = 4096;

    /**
     * The most bytes of a standard input the resident program accepts: far above the largest attachment chunk of the tool
     * (349528 base64 characters), and a bound for what the resident program keeps in memory for one call.
     */
    public static final int MAX_STDIN_BYTES = 32 * 1024 * 1024;

    /** The most bytes of the request body: the words, the standard input and their lengths. */
    public static final int MAX_REQUEST_BYTES = MAX_STDIN_BYTES + MAX_ARGUMENTS * (MAX_ARGUMENT_BYTES + 4) + 8;

    /** The most bytes the light client accepts of an answer body (what the hook prints is far smaller than its input). */
    public static final int MAX_ANSWER_BYTES = 2 * MAX_STDIN_BYTES;

    /** The request body of this call. */
    public byte[] encode(HookCall call) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(call.stdin().length + 128);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(call.arguments().size());
        for (String argument : call.arguments()) {
            writeBytes(out, argument.getBytes(StandardCharsets.UTF_8));
        }
        writeBytes(out, call.stdin());
        out.flush();
        return bytes.toByteArray();
    }

    /** The call in a request body; the sizes are checked against the limits first. */
    public HookCall decodeCall(InputStream body) throws IOException {
        DataInputStream in = new DataInputStream(body);
        int count = in.readInt();
        if (count < 0 || count > MAX_ARGUMENTS) {
            throw new IOException("the call has an unusable number of words");
        }
        List<String> arguments = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            arguments.add(StandardCharsets.UTF_8.decode(ByteBuffer.wrap(readBytes(in, MAX_ARGUMENT_BYTES))).toString());
        }
        byte[] stdin = readBytes(in, MAX_STDIN_BYTES);
        if (in.read() != -1) {
            throw new IOException("the call has bytes after its end");
        }
        return new HookCall(List.copyOf(arguments), stdin);
    }

    /** The answer body of this answer. */
    public byte[] encode(HookAnswer answer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(answer.stdout().length + answer.stderr().length + 16);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(answer.exit());
        writeBytes(out, answer.stdout());
        writeBytes(out, answer.stderr());
        out.flush();
        return bytes.toByteArray();
    }

    /** The answer in an answer body. */
    public HookAnswer decodeAnswer(InputStream body) throws IOException {
        DataInputStream in = new DataInputStream(body);
        int exit = in.readInt();
        byte[] stdout = readBytes(in, MAX_ANSWER_BYTES);
        byte[] stderr = readBytes(in, MAX_ANSWER_BYTES);
        if (in.read() != -1) {
            throw new IOException("the answer has bytes after its end");
        }
        return new HookAnswer(exit, stdout, stderr);
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInputStream in, int limit) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > limit) {
            throw new IOException("a length in the message is beyond its limit");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("the message ends before its content");
        }
        return bytes;
    }
}
