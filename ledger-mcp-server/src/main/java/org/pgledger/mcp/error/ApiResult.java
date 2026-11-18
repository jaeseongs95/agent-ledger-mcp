package org.pgledger.mcp.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Standard {@code ApiResult.v1} envelope returned by every MCP tool call.
 *
 * @param schemaVersion contract schema version ({@code "1.0.0"})
 * @param ok            {@code true} when the call succeeded and {@code data} holds the payload; {@code false} when
 *                      {@code error} holds the structured refusal
 * @param data          tool output record on success, or {@code null} on failure
 * @param error         structured {@link ToolError} on failure, or {@code null} on success
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResult<T>(
        @JsonProperty("schemaVersion") String schemaVersion,
        @JsonProperty("ok") boolean ok,
        @JsonProperty("data") T data,
        @JsonProperty("error") ToolError error) {

    public static final String SCHEMA_VERSION = "1.0.0";

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(SCHEMA_VERSION, true, data, null);
    }

    public static <T> ApiResult<T> failed(ToolError error) {
        return new ApiResult<>(SCHEMA_VERSION, false, null, error);
    }
}
