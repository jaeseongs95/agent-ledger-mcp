// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
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

    @Test
    @DisplayName("TRN-03 full session lifecycle: init, cap 503, parse error, factory failures, notifications, responses, SSE requests, eviction, delete, and shutdown")
    void fullSessionLifecycleAndFailurePaths() throws Exception {
        JsonMapper rawMapper = JsonMapper.builder().build();
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(rawMapper);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-27T00:00:00Z"));
        transport = new BoundedStreamableServerTransportProvider(
                mapper, "/mcp", false, Duration.ofSeconds(5), Duration.ofSeconds(30), 2L, mutableClock(now));

        // Invalid JSON body -> 400 PARSE_ERROR
        MockHttpServletRequest badJson = postRequest("{not-valid-json", null);
        assertThat(dispatch(transport, badJson).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Factory throwing IllegalArgumentException / McpError -> 400 and decrements pendingAdmissions
        transport.setSessionFactory(req -> {
            throw new IllegalArgumentException("bad init params");
        });
        assertThat(dispatch(transport, initRequest(1)).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        transport.setSessionFactory(req -> {
            throw McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS).message("bad protocol").build();
        });
        assertThat(dispatch(transport, initRequest(2)).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Factory throwing unexpected RuntimeException -> rethrown and decrements pendingAdmissions
        transport.setSessionFactory(req -> {
            throw new IllegalStateException("unexpected factory failure");
        });
        assertThatThrownBy(() -> dispatch(transport, initRequest(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected factory failure");

        // Wire real MCP SDK sync server onto transport
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("veridger-unit-test", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .build();
        try {
            // Initialize session 1 -> 200 OK with mcp-session-id header
            ServerResponse initResp1 = dispatch(transport, initRequest(10));
            assertThat(initResp1.statusCode()).isEqualTo(HttpStatus.OK);
            String session1 = initResp1.headers().getFirst(HttpHeaders.MCP_SESSION_ID);
            assertThat(session1).isNotBlank();
            assertThat(transport.activeSessionCount()).isEqualTo(1);

            // Initialize session 2 -> 200 OK; session 3 -> 503 maxSessions reached
            ServerResponse initResp2 = dispatch(transport, initRequest(11));
            assertThat(initResp2.statusCode()).isEqualTo(HttpStatus.OK);
            String session2 = initResp2.headers().getFirst(HttpHeaders.MCP_SESSION_ID);
            assertThat(transport.activeSessionCount()).isEqualTo(2);
            assertThat(dispatch(transport, initRequest(12)).statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

            // POST notification -> 202 Accepted
            MockHttpServletRequest notif = postRequest(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session1);
            assertThat(dispatch(transport, notif).statusCode()).isEqualTo(HttpStatus.ACCEPTED);

            // POST JSONRPCResponse for unknown request id -> dispatched to session.accept(response) which throws McpError
            MockHttpServletRequest rpcResp = postRequest(
                    "{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{}}", session1);
            assertThatThrownBy(() -> dispatch(transport, rpcResp))
                    .isInstanceOf(McpError.class)
                    .hasMessageContaining("99");

            // POST JSONRPCRequest (ping) -> SSE response executed via ServerResponse.writeTo
            MockHttpServletRequest pingReq = postRequest(
                    "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"ping\"}", session1);
            pingReq.setAsyncSupported(true);
            ServerResponse pingResp = dispatch(transport, pingReq);
            assertThat(pingResp.statusCode()).isEqualTo(HttpStatus.OK);
            MockHttpServletResponse servletResp = new MockHttpServletResponse();
            ServerResponse.Context ctx = new ServerResponse.Context() {
                @Override
                public List<org.springframework.http.converter.HttpMessageConverter<?>> messageConverters() {
                    return List.of(new JacksonJsonHttpMessageConverter(rawMapper));
                }
            };
            pingResp.writeTo(pingReq, servletResp, ctx);

            // POST JSONRPCRequest for unknown method -> exercises SSE error frame path (sendError)
            MockHttpServletRequest badMethodReq = postRequest(
                    "{\"jsonrpc\":\"2.0\",\"id\":43,\"method\":\"unknown/nonexistent\"}", session1);
            badMethodReq.setAsyncSupported(true);
            ServerResponse badMethodResp = dispatch(transport, badMethodReq);
            assertThat(badMethodResp.statusCode()).isEqualTo(HttpStatus.OK);
            badMethodResp.writeTo(badMethodReq, new MockHttpServletResponse(), ctx);

            // notifyClients skips POST-only sessions cleanly without throwing
            transport.notifyClients("notifications/tools/list_changed", Map.of()).block();

            // GET SSE listening stream with Last-Event-ID header
            MockHttpServletRequest getSse = new MockHttpServletRequest(HttpMethod.GET.name(), "/mcp");
            getSse.addHeader("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
            getSse.addHeader(HttpHeaders.MCP_SESSION_ID, session1);
            getSse.addHeader(HttpHeaders.LAST_EVENT_ID, "evt-0");
            getSse.setAsyncSupported(true);
            ServerResponse sseResp = dispatch(transport, getSse);
            assertThat(sseResp.statusCode()).isEqualTo(HttpStatus.OK);

            // DELETE session 2 -> 200 OK
            MockHttpServletRequest del2 = new MockHttpServletRequest(HttpMethod.DELETE.name(), "/mcp");
            del2.addHeader(HttpHeaders.MCP_SESSION_ID, session2);
            assertThat(dispatch(transport, del2).statusCode()).isEqualTo(HttpStatus.OK);
            assertThat(transport.activeSessionCount()).isEqualTo(1);

            // Advance clock beyond idle timeout -> evictIdleSessions evicts session 1
            now.set(now.get().plusSeconds(35));
            transport.evictIdleSessions();
            assertThat(transport.activeSessionCount()).isZero();

            // Re-initialize a fresh session and confirm closeGracefully closes active sessions cleanly
            ServerResponse initResp3 = dispatch(transport, initRequest(13));
            assertThat(initResp3.statusCode()).isEqualTo(HttpStatus.OK);
            assertThat(transport.activeSessionCount()).isEqualTo(1);
            transport.closeGracefully().block();
            assertThat(transport.activeSessionCount()).isZero();
        } finally {
            server.closeGracefully();
        }
    }

    @Test
    @DisplayName("TRN-04: full capacity reclaims disconnected sessions and preserves in-flight requests and open streams")
    void admitsNewSessionByReclaimingOnlyIdleDisconnectedSession() throws Exception {
        JsonMapper rawMapper = JsonMapper.builder().build();
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(rawMapper);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-27T00:00:00Z"));
        transport = new BoundedStreamableServerTransportProvider(
                mapper, "/mcp", false, null, Duration.ofMinutes(30), 3L, mutableClock(now));
        McpSyncServer server = McpServer.sync(transport).serverInfo("veridger-unit-test", "1.0.0").build();
        try {
            String busy = dispatch(transport, initRequest(1)).headers().getFirst(HttpHeaders.MCP_SESSION_ID);
            String listening = dispatch(transport, initRequest(2)).headers().getFirst(HttpHeaders.MCP_SESSION_ID);
            String idle = dispatch(transport, initRequest(3)).headers().getFirst(HttpHeaders.MCP_SESSION_ID);
            assertThat(List.of(busy, listening, idle)).doesNotHaveDuplicates();

            ServerResponse.Context context = () -> List.of(new JacksonJsonHttpMessageConverter(rawMapper));
            MockHttpServletRequest ping = postRequest("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}", busy);
            ping.setAsyncSupported(true);
            ServerResponse accepted = dispatch(transport, ping);
            MockHttpServletRequest listen = new MockHttpServletRequest(HttpMethod.GET.name(), "/mcp");
            listen.addHeader("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
            listen.addHeader(HttpHeaders.MCP_SESSION_ID, listening);
            listen.setAsyncSupported(true);
            dispatch(transport, listen).writeTo(listen, new MockHttpServletResponse(), context);

            assertThat(dispatch(transport, initRequest(5)).statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            now.updateAndGet(current -> current.plusSeconds(61));
            assertThat(dispatch(transport, initRequest(6)).statusCode()).isEqualTo(HttpStatus.OK);
            assertThat(transport.activeSessionCount()).isEqualTo(3);
            String notification = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
            assertThat(dispatch(transport, postRequest(notification, idle)).statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(dispatch(transport, postRequest(notification, listening)).statusCode())
                    .isEqualTo(HttpStatus.ACCEPTED);

            MockHttpServletResponse pingResponse = new MockHttpServletResponse();
            accepted.writeTo(ping, pingResponse, context);
            assertThat(pingResponse.getContentAsString()).contains("\"id\":4", "\"result\"");
            assertThat(dispatch(transport, initRequest(7)).statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            server.closeGracefully();
        }
    }

    private static Clock mutableClock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
    }

    private static MockHttpServletRequest initRequest(int id) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + McpSchema.METHOD_INITIALIZE
                + "\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"unit-client\",\"version\":\"1.0\"}}}";
        return postRequest(body, null);
    }

    private static MockHttpServletRequest postRequest(String body, String sessionId) {
        MockHttpServletRequest req = new MockHttpServletRequest(HttpMethod.POST.name(), "/mcp");
        req.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE + ", " + MediaType.TEXT_EVENT_STREAM_VALUE);
        req.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (sessionId != null) {
            req.addHeader(HttpHeaders.MCP_SESSION_ID, sessionId);
        }
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        return req;
    }
}
