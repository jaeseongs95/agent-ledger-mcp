// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Direct unit tests for {@link BoundedStreamableServerTransportProvider} covering validation, sweep interval clamping,
 * GET/POST/DELETE error responses, shutdown gating, and empty notifications.
 */
class BoundedTransportUnitTest {

    private BoundedStreamableServerTransportProvider transport;

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.closeGracefully().block();
            transport = null;
        }
    }

    private static ServerResponse dispatch(BoundedStreamableServerTransportProvider provider,
            MockHttpServletRequest servletRequest) throws Exception {
        ServerRequest request = ServerRequest.create(servletRequest,
                List.of(new org.springframework.http.converter.StringHttpMessageConverter()));
        HandlerFunction<ServerResponse> handler = provider.getRouterFunction().route(request).orElseThrow();
        return handler.handle(request);
    }

    @Test
    @DisplayName("TRN-01 constructor rejects non-positive maxSessions and computeSweepInterval clamps to [50ms, 60s]")
    void constructorValidationAndSweepIntervalClamping() {
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        Clock clock = Clock.fixed(Instant.parse("2026-11-27T00:00:00Z"), ZoneOffset.UTC);

        assertThatThrownBy(() -> new BoundedStreamableServerTransportProvider(
                mapper, "/mcp", false, null, Duration.ofSeconds(30), 0L, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        assertThat(BoundedStreamableServerTransportProvider.computeSweepInterval(Duration.ofMillis(40)))
                .isEqualTo(Duration.ofMillis(50));
        assertThat(BoundedStreamableServerTransportProvider.computeSweepInterval(Duration.ofSeconds(80)))
                .isEqualTo(Duration.ofSeconds(20));
        assertThat(BoundedStreamableServerTransportProvider.computeSweepInterval(Duration.ofMinutes(20)))
                .isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("TRN-02 GET/POST/DELETE validate Accept, session IDs, disallowDelete, JSON-RPC parsing, and shutdown 503")
    void handlerErrorAndLifecycleBranches() throws Exception {
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        Clock clock = Clock.fixed(Instant.parse("2026-11-27T00:00:00Z"), ZoneOffset.UTC);
        transport = new BoundedStreamableServerTransportProvider(
                mapper, "/mcp", false, null, Duration.ofSeconds(30), 2L, clock);

        assertThat(transport.protocolVersions()).hasSize(4);
        assertThat(transport.activeSessionCount()).isZero();
        transport.notifyClients("notifications/test", java.util.Map.of()).block();
        transport.evictIdleSessions();

        // GET without Accept: text/event-stream -> 400
        MockHttpServletRequest getNoAccept = new MockHttpServletRequest(HttpMethod.GET.name(), "/mcp");
        assertThat(dispatch(transport, getNoAccept).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // GET without session id -> 400
        MockHttpServletRequest getNoSession = new MockHttpServletRequest(HttpMethod.GET.name(), "/mcp");
        getNoSession.addHeader("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(dispatch(transport, getNoSession).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // GET unknown session id -> 404
        MockHttpServletRequest getUnknown = new MockHttpServletRequest(HttpMethod.GET.name(), "/mcp");
        getUnknown.addHeader("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
        getUnknown.addHeader(HttpHeaders.MCP_SESSION_ID, "unknown-session");
        assertThat(dispatch(transport, getUnknown).statusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // POST without required Accept headers -> 400
        MockHttpServletRequest postBadAccept = new MockHttpServletRequest(HttpMethod.POST.name(), "/mcp");
        postBadAccept.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        assertThat(dispatch(transport, postBadAccept).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // POST initialize when sessionFactory is null -> 503
        MockHttpServletRequest postInitNoFactory = new MockHttpServletRequest(HttpMethod.POST.name(), "/mcp");
        postInitNoFactory.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE + ", " + MediaType.TEXT_EVENT_STREAM_VALUE);
        postInitNoFactory.setContentType(MediaType.APPLICATION_JSON_VALUE);
        postInitNoFactory.setContent(("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""
                + McpSchema.METHOD_INITIALIZE + "\",\"params\":{}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(dispatch(transport, postInitNoFactory).statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // POST non-init without session id -> 400, with unknown session id -> 404
        MockHttpServletRequest postNoSession = new MockHttpServletRequest(HttpMethod.POST.name(), "/mcp");
        postNoSession.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE + ", " + MediaType.TEXT_EVENT_STREAM_VALUE);
        postNoSession.setContentType(MediaType.APPLICATION_JSON_VALUE);
        postNoSession.setContent("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(dispatch(transport, postNoSession).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        MockHttpServletRequest postUnknown = new MockHttpServletRequest(HttpMethod.POST.name(), "/mcp");
        postUnknown.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE + ", " + MediaType.TEXT_EVENT_STREAM_VALUE);
        postUnknown.addHeader(HttpHeaders.MCP_SESSION_ID, "unknown-session");
        postUnknown.setContentType(MediaType.APPLICATION_JSON_VALUE);
        postUnknown.setContent("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(dispatch(transport, postUnknown).statusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // DELETE missing session id -> 400, unknown session -> 404
        MockHttpServletRequest delNoSession = new MockHttpServletRequest(HttpMethod.DELETE.name(), "/mcp");
        assertThat(dispatch(transport, delNoSession).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        MockHttpServletRequest delUnknown = new MockHttpServletRequest(HttpMethod.DELETE.name(), "/mcp");
        delUnknown.addHeader(HttpHeaders.MCP_SESSION_ID, "unknown-session");
        assertThat(dispatch(transport, delUnknown).statusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Close gracefully -> GET/POST/DELETE all return 503
        transport.closeGracefully().block();
        transport.evictIdleSessions();
        assertThat(dispatch(transport, getNoAccept).statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(dispatch(transport, postBadAccept).statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(dispatch(transport, delNoSession).statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // disallowDelete=true -> DELETE returns 405
        BoundedStreamableServerTransportProvider noDelete = new BoundedStreamableServerTransportProvider(
                mapper, "/mcp", true, null, Duration.ofSeconds(30), 2L, clock);
        try {
            assertThat(dispatch(noDelete, delNoSession).statusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        } finally {
            noDelete.closeGracefully().block();
        }
    }
}
