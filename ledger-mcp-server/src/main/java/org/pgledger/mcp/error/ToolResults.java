package org.pgledger.mcp.error;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wraps every MCP tool call outcome in the {@link ApiResult} ({@code ApiResult.v1}) envelope: on success,
 * {@code ok=true} with {@code data} and serialized JSON in {@code TextContent}; on failure, {@code ok=false} with
 * {@code error}, {@code isError=true}, and {@code <TOKEN>: <message>} in {@code TextContent}. {@link #run} is the
 * single MCP boundary that catches runtime exceptions.
 */
@Component
public class ToolResults {

    private final JsonMapper mapper;
    private final ToolErrorMapper errors;

    public ToolResults(JsonMapper mapper, ToolErrorMapper errors) {
        this.mapper = mapper;
        this.errors = errors;
    }

    public CallToolResult run(Supplier<Object> call) {
        try {
            return success(call.get());
        } catch (RuntimeException failure) {
            return failure(errors.map(failure));
        }
    }

    private CallToolResult success(Object output) {
        ApiResult<Object> envelope = ApiResult.ok(output);
        return CallToolResult.builder()
                .structuredContent(envelope)
                .addTextContent(mapper.writeValueAsString(envelope))
                .isError(false)
                .build();
    }

    private CallToolResult failure(ToolError error) {
        ApiResult<Object> envelope = ApiResult.failed(error);
        return CallToolResult.builder()
                .structuredContent(envelope)
                .addTextContent(error.code() + ": " + error.message())
                .isError(true)
                .build();
    }
}
