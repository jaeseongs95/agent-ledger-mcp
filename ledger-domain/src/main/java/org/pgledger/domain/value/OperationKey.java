// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain.value;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/**
 * The idempotency key of a write: the {@code operationKey} the caller sends with every write tool. The format is the one of
 * {@code artifact-begin}: 1 to 128 characters, not blank. The server never makes a key up; a missing key is refused.
 */
public record OperationKey(String value) {

    public static final int MIN_LENGTH = 1;
    public static final int MAX_LENGTH = 128;
    /** The name of the key in the arguments of a tool, and the path of a refusal. */
    public static final String WIRE_NAME = "operationKey";

    public OperationKey {
        if (value == null) {
            throw ValidationException.invalid(WIRE_NAME, ValidationReason.REQUIRED);
        }
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH || value.isBlank()) {
            throw ValidationException.invalid(WIRE_NAME, ValidationReason.MUST_BE_CHARS_RANGE,
                    Integer.toString(MIN_LENGTH), Integer.toString(MAX_LENGTH));
        }
    }

    public static OperationKey parse(String text) {
        return new OperationKey(text);
    }

    /** The key of operation {@code index} of a batch: deterministic, different per index and per batch key. */
    public OperationKey derive(int index) {
        return new OperationKey(
                UUID.nameUUIDFromBytes((value + ":" + index).getBytes(StandardCharsets.UTF_8)).toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
