// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.json;

import org.veridger.domain.value.Patch;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a field of an edit as a {@link Patch} of text: a text is {@link Patch#set set} to that text (an empty text clears an
 * optional field, which is the one way to clear it), and a key that is missing or written as a JSON {@code null} stays
 * {@link Patch#absent absent} (the field is not touched: a null counts as not given, as it does in the digest of the
 * arguments, because the server side of the MCP SDK drops the null of a map before the binding sees it). Jackson asks a
 * deserializer for each case separately ({@link #deserialize}, {@link #getNullValue}, {@link #getAbsentValue}).
 */
public final class PatchText extends ValueDeserializer<Patch<String>> {

    @Override
    public Patch<String> deserialize(JsonParser parser, DeserializationContext context) {
        return Patch.set(context.readValue(parser, String.class));
    }

    /** A JSON {@code null} is no value: it counts as not given and the field is left as it is. */
    @Override
    public Object getNullValue(DeserializationContext context) {
        return Patch.absent();
    }

    /** A key that is not in the input is no value: the field is left as it is. */
    @Override
    public Object getAbsentValue(DeserializationContext context) {
        return Patch.absent();
    }
}
