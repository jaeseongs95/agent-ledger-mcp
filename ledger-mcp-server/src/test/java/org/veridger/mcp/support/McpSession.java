// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.veridger.mcp.error.ApiResult;
import org.veridger.mcp.error.ToolError;
import org.veridger.mcp.tool.ReadOutputs;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real MCP Java SDK client over Streamable HTTP. Unwraps and asserts the {@link ApiResult} ({@code ApiResult.v1})
 * envelope on every {@link #read}, {@link #readItems}, and {@link #error} call.
 */
public final class McpSession implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final McpSyncClient client;
    private final JsonMapper mapper;
    private final McpJsonMapper wire;
    private final BlockingQueue<List<Tool>> listChanges = new LinkedBlockingQueue<>();
    private final Attester attester;

    private McpSession(int port, String bearer, JsonMapper mapper, Attester attester) {
        this.mapper = mapper;
        this.attester = attester;
        this.wire = new JacksonMcpJsonMapper(mapper);
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port).endpoint("/mcp").jsonMapper(wire)
                .addHttpRequestCustomizer((request, method, uri, body, context) ->
                        request.header("Authorization", "Bearer " + bearer))
                .build();
        this.client = McpClient.sync(transport).requestTimeout(TIMEOUT).initializationTimeout(TIMEOUT)
                .toolsChangeConsumer(listChanges::add).build();
        this.client.initialize();
    }

    /** Opens a session on the server at the port, presenting the bearer token without any client hook. */
    public static McpSession open(int port, String bearer, JsonMapper mapper) {
        return new McpSession(port, bearer, mapper, Attester.none());
    }

    /** Opens a session on the server of the context with the test token and test attester. */
    public static McpSession open(ApplicationContext context) {
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        return new McpSession(port, McpTestSettings.TOKEN, context.getBean(JsonMapper.class), Attester.of(context));
    }

    public McpSyncClient client() {
        return client;
    }

    public JsonMapper mapper() {
        return mapper;
    }

    public List<Tool> tools() {
        return client.listTools().tools();
    }

    public Tool tool(String name) {
        return tools().stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    public CallToolResult call(String tool, Object arguments) {
        return callUnsigned(tool, attester.stamp(tool, arguments));
    }

    public CallToolResult callUnsigned(String tool, Object arguments) {
        return client.callTool(CallToolRequest.builder(tool).arguments(wire, mapper.writeValueAsString(arguments)).build());
    }

    public <T> T ok(String tool, Object arguments, Class<T> type) {
        CallToolResult result = call(tool, arguments);
        assertThat(result.isError()).as("%s failed: %s", tool, result.content()).isFalse();
        return read(result, type);
    }

    public ToolError failed(String tool, Object arguments) {
        CallToolResult result = call(tool, arguments);
        assertThat(result.isError()).as("%s must fail, got %s", tool, result.content()).isTrue();
        return error(result);
    }

    /** Reads {@code data} from a successful {@code ApiResult.v1} structured result. */
    public <T> T read(CallToolResult result, Class<T> type) {
        JavaType envelopeType = mapper.getTypeFactory().constructParametricType(ApiResult.class, type);
        ApiResult<T> envelope = mapper.convertValue(result.structuredContent(), envelopeType);
        assertThat(envelope.schemaVersion()).isEqualTo(ApiResult.SCHEMA_VERSION);
        assertThat(envelope.ok()).isTrue();
        assertThat(envelope.error()).isNull();
        return envelope.data();
    }

    /** Reads a paginated {@code Items<T>} payload from {@code data} of {@code ApiResult.v1}. */
    public <T> ReadOutputs.Items<T> readItems(CallToolResult result, Class<T> item) {
        JavaType itemsType = mapper.getTypeFactory().constructParametricType(ReadOutputs.Items.class, item);
        JavaType envelopeType = mapper.getTypeFactory().constructParametricType(ApiResult.class, itemsType);
        ApiResult<ReadOutputs.Items<T>> envelope = mapper.convertValue(result.structuredContent(), envelopeType);
        assertThat(envelope.schemaVersion()).isEqualTo(ApiResult.SCHEMA_VERSION);
        assertThat(envelope.ok()).isTrue();
        assertThat(envelope.error()).isNull();
        return envelope.data();
    }

    /** First text content block of a result. */
    public static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    /** Reads {@code error} from a failed {@code ApiResult.v1} structured result. */
    public ToolError error(CallToolResult result) {
        JavaType envelopeType = mapper.getTypeFactory().constructParametricType(ApiResult.class, Object.class);
        ApiResult<Object> envelope = mapper.convertValue(result.structuredContent(), envelopeType);
        assertThat(envelope.schemaVersion()).isEqualTo(ApiResult.SCHEMA_VERSION);
        assertThat(envelope.ok()).isFalse();
        assertThat(envelope.data()).isNull();
        assertThat(envelope.error()).isNotNull();
        return envelope.error();
    }

    public List<Tool> nextListChange(Duration within) throws InterruptedException {
        return listChanges.poll(within.toNanos(), TimeUnit.NANOSECONDS);
    }

    public List<Tool> awaitListChange(Duration within) {
        try {
            List<Tool> tools = listChanges.poll(within.toNanos(), TimeUnit.NANOSECONDS);
            assertThat(tools).as("a list_changed notification within %s", within).isNotNull();
            return tools;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for list_changed", failure);
        }
    }

    public void drainListChanges(Duration quiet) {
        try {
            for (int announcements = 0; listChanges.poll(quiet.toNanos(), TimeUnit.NANOSECONDS) != null; announcements++) {
                assertThat(announcements).as("list_changed notifications that keep coming without a change").isLessThan(10);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for silence", failure);
        }
    }

    public void awaitNoListChange(Duration within) {
        try {
            assertThat(listChanges.poll(within.toNanos(), TimeUnit.NANOSECONDS))
                    .as("no list_changed notification within %s", within).isNull();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for silence", failure);
        }
    }

    public int pendingListChanges() {
        return listChanges.size();
    }

    @Override
    public void close() {
        client.close();
    }
}
