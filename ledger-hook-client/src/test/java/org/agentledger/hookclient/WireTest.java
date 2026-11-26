// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hookclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R9-D0096-E (wire): what travels between the light client and the resident program. */
class WireTest {

    private final Wire wire = new Wire();

    @Test
    @DisplayName("R9-D0096-E a call and an answer survive the wire byte for byte: Korean words, an empty input, every byte value, three output streams")
    void roundTrip() throws IOException {
        byte[] everyByte = new byte[256];
        for (int i = 0; i < everyByte.length; i++) {
            everyByte[i] = (byte) i;
        }
        HookCall call = new HookCall(List.of("codex", "한글 서버", "codex:é"), everyByte);

        HookCall decoded = wire.decodeCall(new ByteArrayInputStream(wire.encode(call)));
        assertThat(decoded.arguments()).containsExactlyElementsOf(call.arguments());
        assertThat(decoded.stdin()).isEqualTo(everyByte);
        assertThat(wire.decodeCall(new ByteArrayInputStream(wire.encode(new HookCall(List.of(), new byte[0])))).stdin())
                .isEmpty();

        HookAnswer answer = new HookAnswer(2, "{\"a\":\"한글\"}\n".getBytes(StandardCharsets.UTF_8), everyByte);
        HookAnswer back = wire.decodeAnswer(new ByteArrayInputStream(wire.encode(answer)));
        assertThat(back.exit()).isEqualTo(2);
        assertThat(back.stdout()).isEqualTo(answer.stdout());
        assertThat(back.stderr()).isEqualTo(everyByte);
    }

    private static byte[] call(int arguments, int firstArgumentLength, int stdinLength, int actualStdin) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(arguments);
        for (int i = 0; i < Math.min(arguments, 1); i++) {
            out.writeInt(firstArgumentLength);
            out.write(new byte[Math.max(0, Math.min(firstArgumentLength, 100))]);
        }
        out.writeInt(stdinLength);
        out.write(new byte[actualStdin]);
        return bytes.toByteArray();
    }

    @Test
    @DisplayName("R9-D0096-E a request that lies about its sizes is refused before anything is allocated: too many words, a negative or huge length, a body that ends early, bytes after the end")
    void refusesWhatIsOutsideTheLimits() throws IOException {
        assertThatThrownBy(() -> wire.decodeCall(new ByteArrayInputStream(call(Wire.MAX_ARGUMENTS + 1, 0, 0, 0))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> wire.decodeCall(new ByteArrayInputStream(call(-1, 0, 0, 0)))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> wire.decodeCall(new ByteArrayInputStream(call(1, Wire.MAX_ARGUMENT_BYTES + 1, 0, 0))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> wire.decodeCall(new ByteArrayInputStream(call(0, 0, Integer.MAX_VALUE, 0))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> wire.decodeCall(new ByteArrayInputStream(call(0, 0, Wire.MAX_STDIN_BYTES + 1, 0))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> wire.decodeCall(new ByteArrayInputStream(call(0, 0, -5, 0)))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> wire.decodeCall(new ByteArrayInputStream(call(0, 0, 10, 4)))).isInstanceOf(IOException.class);
        byte[] withTail = wire.encode(new HookCall(List.of("codex"), new byte[3]));
        byte[] longer = java.util.Arrays.copyOf(withTail, withTail.length + 1);
        assertThatThrownBy(() -> wire.decodeCall(new ByteArrayInputStream(longer))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> wire.decodeCall(new ByteArrayInputStream(new byte[2]))).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("R9-D0096-E the limit of the standard input is far above the largest attachment chunk (349528 characters) and the limit of the request holds the words and the lengths")
    void limitsAreSane() {
        assertThat(Wire.MAX_STDIN_BYTES).isGreaterThan(50 * 349_528);
        assertThat(Wire.MAX_REQUEST_BYTES).isGreaterThan(Wire.MAX_STDIN_BYTES);
        assertThat(Wire.MAX_ANSWER_BYTES).isGreaterThanOrEqualTo(Wire.MAX_STDIN_BYTES);
    }
}
