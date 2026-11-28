// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Response options shared by the tools. They do not become business arguments or enter a write receipt. */
record ResultOptions(Fields fields, boolean structuredOnly) {

    static final String FIELDS = "fields";
    static final String STRUCTURED_ONLY = "structured_only";
    static final int MAX_FIELDS = 16;
    static final int MAX_PATH_LENGTH = 120;
    private static final Pattern FIELD_PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){0,3}");
    private static final TypeReference<Map<String, Object>> DATA_OBJECT = new TypeReference<>() {};

    static ResultOptions parse(Map<String, Object> arguments, SchemaDocument output) {
        if (arguments == null) {
            return new ResultOptions(new Fields(), false);
        }
        Object mode = arguments.get(STRUCTURED_ONLY);
        if (mode != null && !(mode instanceof Boolean)) {
            throw ValidationException.invalid(STRUCTURED_ONLY, ValidationReason.WRONG_TYPE, "boolean");
        }
        Fields fields = new Fields();
        ResultOptions options = new ResultOptions(fields, Boolean.TRUE.equals(mode));
        Object requested = arguments.get(FIELDS);
        if (requested == null) {
            return options;
        }
        if (!(requested instanceof List<?> paths)) {
            throw ValidationException.invalid(FIELDS, ValidationReason.WRONG_TYPE, "array");
        }
        if (paths.isEmpty() || paths.size() > MAX_FIELDS) {
            throw ValidationException.invalid(FIELDS, ValidationReason.MUST_HOLD_ITEMS, "1",
                    Integer.toString(MAX_FIELDS));
        }
        for (Object rawPath : paths) {
            if (!(rawPath instanceof String path)) {
                throw ValidationException.invalid(FIELDS, ValidationReason.WRONG_TYPE, "string");
            }
            if (path.length() > MAX_PATH_LENGTH || !FIELD_PATH.matcher(path).matches()
                    || !output.supportsField(path)) {
                throw ValidationException.invalid(FIELDS, ValidationReason.UNKNOWN_VALUE,
                        path.substring(0, Math.min(path.length(), MAX_PATH_LENGTH)));
            }
            fields.add(path.split("\\."), 0);
        }
        return options;
    }

    static final class Fields {
        private final Map<String, Fields> children = new LinkedHashMap<>();
        private boolean entire;

        private void add(String[] path, int index) {
            if (index == path.length) {
                entire = true;
                children.clear();
            } else if (!entire) {
                children.computeIfAbsent(path[index], ignored -> new Fields()).add(path, index + 1);
            }
        }

        Object project(Object output, JsonMapper mapper) {
            if (children.isEmpty()) {
                return output;
            }
            Map<String, Object> source = mapper.convertValue(output, DATA_OBJECT);
            Map<String, Object> selected = takeProperties(source);
            if (children.containsKey("items")) {
                keep(source, selected, "truncated", "next_cursor");
            }
            keep(source, selected, "not_found");
            if (source.containsKey("totalBytes") && (children.containsKey("text") || children.containsKey("data"))) {
                keep(source, selected, "offsetBytes", "size", "totalBytes");
            }
            return selected;
        }

        private static void keep(Map<String, Object> source, Map<String, Object> target, String... keys) {
            for (String key : keys) {
                if (source.containsKey(key)) {
                    target.putIfAbsent(key, source.get(key));
                }
            }
        }

        private Object take(Object source) {
            if (entire || source == null) {
                return source;
            }
            if (source instanceof List<?> rows) {
                return rows.stream().map(this::take).toList();
            }
            return source instanceof Map<?, ?> properties ? takeProperties(properties) : Map.of();
        }

        private Map<String, Object> takeProperties(Map<?, ?> properties) {
            Map<String, Object> selected = new LinkedHashMap<>();
            for (Map.Entry<String, Fields> field : children.entrySet()) {
                String key = field.getKey();
                if (properties.containsKey(key)) {
                    selected.put(key, field.getValue().take(properties.get(key)));
                }
            }
            return selected;
        }
    }
}
