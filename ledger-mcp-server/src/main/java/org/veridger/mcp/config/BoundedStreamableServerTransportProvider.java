// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.config;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSession;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.util.KeepAliveScheduler;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStdioDisabledCondition;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Veridger-owned Streamable HTTP MCP transport provider replacing Spring AI's final
 * {@code WebMvcStreamableServerTransportProvider} to enforce three transport hardening guarantees:
 * <ul>
 * <li>Atomic {@code maxSessions} admission under a single lock so concurrent {@code initialize} floods can never
 * overshoot session capacity.</li>
 * <li>Idle session eviction and earlier reclamation of disconnected sessions when admission reaches capacity.
 * In-flight requests are protected from eviction.</li>
 * <li>SSE-only keep-alive probing that skips POST-only sessions (eliminating false {@code Stream unavailable} errors)
 * and releases dead listening streams via {@code onPingFailure}.</li>
 * </ul>
 */
@Component
@Conditional({McpServerStdioDisabledCondition.class, McpServerAutoConfiguration.EnabledStreamableServerCondition.class})
public final class BoundedStreamableServerTransportProvider implements McpStreamableServerTransportProvider {

    public static final String MESSAGE_EVENT_TYPE = "message";

    private static final Logger LOG = LoggerFactory.getLogger(BoundedStreamableServerTransportProvider.class);
    private static final Duration MIN_SWEEP_INTERVAL = Duration.ofMillis(50);
    private static final Duration MAX_SWEEP_INTERVAL = Duration.ofMinutes(1);
    private static final Duration CAPACITY_IDLE_GRACE = Duration.ofMinutes(1);

    private final McpJsonMapper jsonMapper;
    private final String mcpEndpoint;
    private final boolean disallowDelete;
    private final Duration sessionIdleTimeout;
    private final long maxSessions;
    private final Clock clock;
    private final McpTransportContextExtractor<ServerRequest> contextExtractor = req -> McpTransportContext.EMPTY;
    private final RouterFunction<ServerResponse> routerFunction;
    private final ConcurrentHashMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();
    private long pendingAdmissions;
    private volatile McpStreamableServerSession.Factory sessionFactory;
    private volatile boolean closing;
    private final KeepAliveScheduler keepAliveScheduler;
    private final Disposable idleSweepSubscription;

    @Autowired
    public BoundedStreamableServerTransportProvider(@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
            McpServerStreamableHttpProperties streamableProperties, LedgerMcpProperties mcpProperties, Clock clock) {
        this(new JacksonMcpJsonMapper(jsonMapper), streamableProperties.getMcpEndpoint(),
                streamableProperties.isDisallowDelete(),
                McpTransportConfiguration.resolveKeepAlive(streamableProperties.getKeepAliveInterval(), mcpProperties),
                mcpProperties.sessionIdleTimeout(), mcpProperties.maxSessions(), clock);
    }

    BoundedStreamableServerTransportProvider(McpJsonMapper jsonMapper, String mcpEndpoint, boolean disallowDelete,
            Duration keepAliveInterval, Duration sessionIdleTimeout, long maxSessions, Clock clock) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        this.mcpEndpoint = Objects.requireNonNull(mcpEndpoint, "mcpEndpoint must not be null");
        this.disallowDelete = disallowDelete;
        this.sessionIdleTimeout = Objects.requireNonNull(sessionIdleTimeout, "sessionIdleTimeout must not be null");
        if (maxSessions < 1L) {
            throw new IllegalArgumentException("maxSessions must be positive");
        }
        this.maxSessions = maxSessions;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.routerFunction = RouterFunctions.route()
                .GET(this.mcpEndpoint, this::handleGet)
                .POST(this.mcpEndpoint, this::handlePost)
                .DELETE(this.mcpEndpoint, this::handleDelete)
                .build();
        if (keepAliveInterval != null) {
            this.keepAliveScheduler = KeepAliveScheduler
                    .builder(() -> closing ? Flux.empty()
                            : Flux.fromIterable(sessions.values())
                                    .map(entry -> entry.session)
                                    .filter(McpStreamableServerSession::hasListeningStream)
                                    .cast(McpSession.class))
                    .initialDelay(keepAliveInterval)
                    .interval(keepAliveInterval)
                    .onPingFailure(failed -> {
                        if (failed instanceof McpStreamableServerSession session) {
                            LOG.debug("keep-alive ping failed for SSE stream of session {}; releasing stream",
                                    session.getId());
                            session.releaseListeningStream();
                        }
                    })
                    .build();
            this.keepAliveScheduler.start();
        } else {
            this.keepAliveScheduler = null;
        }
        Duration sweepInterval = computeSweepInterval(sessionIdleTimeout);
        this.idleSweepSubscription = Flux.interval(sweepInterval, sweepInterval)
                .subscribe(tick -> evictIdleSessions());
    }

    static Duration computeSweepInterval(Duration idleTimeout) {
        Duration quarter = idleTimeout.dividedBy(4);
        if (quarter.compareTo(MIN_SWEEP_INTERVAL) < 0) {
            return MIN_SWEEP_INTERVAL;
        }
        if (quarter.compareTo(MAX_SWEEP_INTERVAL) > 0) {
            return MAX_SWEEP_INTERVAL;
        }
        return quarter;
    }

    @Override
    public List<String> protocolVersions() {
        return List.of(ProtocolVersions.MCP_2024_11_05, ProtocolVersions.MCP_2025_03_26,
                ProtocolVersions.MCP_2025_06_18, ProtocolVersions.MCP_2025_11_25);
    }

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public RouterFunction<ServerResponse> getRouterFunction() {
        return routerFunction;
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (sessions.isEmpty()) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> {
            for (SessionEntry entry : sessions.values()) {
                McpStreamableServerSession session = entry.session;
                if (!session.hasListeningStream()) {
                    continue;
                }
                try {
                    session.sendNotification(method, params).block();
                } catch (IllegalStateException | McpError failure) {
                    LOG.debug("failed to notify session {}: {}", session.getId(), failure.getMessage());
                }
            }
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            List<SessionEntry> snapshot;
            synchronized (admissionLock) {
                closing = true;
                snapshot = new ArrayList<>(sessions.values());
                sessions.clear();
            }
            if (keepAliveScheduler != null) {
                keepAliveScheduler.shutdown();
            }
            idleSweepSubscription.dispose();
            for (SessionEntry entry : snapshot) {
                McpStreamableServerSession session = entry.session;
                try {
                    session.closeGracefully().block();
                } catch (IllegalStateException | McpError failure) {
                    LOG.debug("failed to close session {} during shutdown: {}", session.getId(), failure.getMessage());
                }
            }
        });
    }

    /** Evicts expired sessions after their in-flight requests have finished. */
    public void evictIdleSessions() {
        Instant cutoff = clock.instant().minus(sessionIdleTimeout);
        List<SessionEntry> expired = new ArrayList<>();
        synchronized (admissionLock) {
            sessions.values().removeIf(entry -> {
                if (entry.inFlight == 0 && entry.lastActivity.isBefore(cutoff)) {
                    expired.add(entry);
                    return true;
                }
                return false;
            });
        }
        expired.forEach(this::closeEvictedSession);
    }

    // Called only while holding admissionLock and after the session cap is reached.
    private SessionEntry reclaimCapacity() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(sessionIdleTimeout);
        Instant pressureCutoff = now.minus(CAPACITY_IDLE_GRACE);
        SessionEntry oldest = null;
        for (SessionEntry entry : sessions.values()) {
            if (entry.inFlight == 0 && (entry.lastActivity.isBefore(cutoff)
                    || (entry.lastActivity.isBefore(pressureCutoff) && !entry.session.hasOpenStream()))
                    && (oldest == null || entry.lastActivity.isBefore(oldest.lastActivity))) {
                oldest = entry;
            }
        }
        return oldest == null ? null : sessions.remove(oldest.session.getId());
    }

    private void closeEvictedSession(SessionEntry entry) {
        LOG.debug("evicting idle MCP session {}", entry.session.getId());
        entry.session.closeGracefully().subscribe(ignored -> {
        }, err -> LOG.debug("error closing evicted MCP session {}: {}", entry.session.getId(), err.getMessage()));
    }

    private SessionEntry acquireSession(String sessionId, boolean trackRequest) {
        synchronized (admissionLock) {
            SessionEntry entry = sessions.get(sessionId);
            if (entry != null) {
                entry.lastActivity = clock.instant();
                if (trackRequest) {
                    entry.inFlight++;
                }
            }
            return entry;
        }
    }

    private void finishRequest(SessionEntry entry) {
        synchronized (admissionLock) {
            entry.lastActivity = clock.instant();
            entry.inFlight--;
        }
    }

    private ServerResponse handleGet(ServerRequest request) {
        if (closing) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
        }
        List<MediaType> accept = request.headers().accept();
        if (!accept.contains(MediaType.TEXT_EVENT_STREAM)) {
            return ServerResponse.badRequest()
                    .body(McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST)
                            .message("Invalid Accept header. Expected TEXT_EVENT_STREAM").build());
        }
        String sessionId = request.headers().firstHeader(HttpHeaders.MCP_SESSION_ID);
        if (sessionId == null || sessionId.isBlank()) {
            return ServerResponse.badRequest()
                    .body(McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST)
                            .message("Session ID required in mcp-session-id header").build());
        }
        SessionEntry entry = acquireSession(sessionId, false);
        if (entry == null) {
            return ServerResponse.status(HttpStatus.NOT_FOUND)
                    .body(McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST).message("Session not found").build());
        }
        McpStreamableServerSession session = entry.session;
        McpTransportContext transportContext = contextExtractor.extract(request);
        return ServerResponse.sse(sseBuilder -> {
            SseSessionTransport transport = new SseSessionTransport(sessionId, sseBuilder);
            McpStreamableServerSession.McpStreamableServerSessionStream stream = session.listeningStream(transport);
            sseBuilder.onComplete(() -> {
                transport.markClosed();
                stream.close();
            });
            sseBuilder.onTimeout(() -> {
                transport.markClosed();
                stream.close();
            });
            sseBuilder.onError(err -> {
                LOG.debug("SSE listening stream error for session {}: {}", sessionId, err.getMessage());
                transport.markClosed();
                stream.close();
            });
            String lastEventId = request.headers().firstHeader(HttpHeaders.LAST_EVENT_ID);
            if (lastEventId != null && !lastEventId.isBlank()) {
                session.replay(lastEventId)
                        .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
                        .toIterable()
                        .forEach(msg -> transport.sendMessage(msg).block());
            }
        }, Duration.ZERO);
    }

    private ServerResponse handlePost(ServerRequest request) {
        if (closing) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
        }
        List<MediaType> accept = request.headers().accept();
        if (!accept.contains(MediaType.TEXT_EVENT_STREAM) || !accept.contains(MediaType.APPLICATION_JSON)) {
            return ServerResponse.badRequest()
                    .body(McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST)
                            .message("Invalid Accept header. Expected both application/json and text/event-stream")
                            .build());
        }
        McpSchema.JSONRPCMessage message;
        try {
            String body = request.body(String.class);
            message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);
        } catch (ServletException | IOException | IllegalArgumentException invalid) {
            LOG.debug("failed to deserialize MCP JSON-RPC request: {}", invalid.getMessage());
            return ServerResponse.badRequest()
                    .body(McpError.builder(McpSchema.ErrorCodes.PARSE_ERROR).message("Invalid message format").build());
        }
        if (message instanceof McpSchema.JSONRPCRequest req
                && McpSchema.METHOD_INITIALIZE.equals(req.method())) {
            return handleInitRequest(req);
        }
        String sessionId = request.headers().firstHeader(HttpHeaders.MCP_SESSION_ID);
        if (sessionId == null || sessionId.isBlank()) {
            return ServerResponse.badRequest()
                    .body(McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST)
                            .message("Session ID required in mcp-session-id header").build());
        }
        SessionEntry entry = acquireSession(sessionId, true);
        if (entry == null) {
            return ServerResponse.status(HttpStatus.NOT_FOUND)
                    .body(McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST).message("Session not found").build());
        }
        McpStreamableServerSession session = entry.session;
        McpTransportContext transportContext = contextExtractor.extract(request);
        if (message instanceof McpSchema.JSONRPCRequest req) {
            return ServerResponse.sse(sseBuilder -> {
                SseSessionTransport transport = new SseSessionTransport(sessionId, sseBuilder);
                sseBuilder.onComplete(transport::markClosed);
                sseBuilder.onTimeout(transport::markClosed);
                sseBuilder.onError(err -> {
                    LOG.debug("SSE response stream error for session {}: {}", sessionId, err.getMessage());
                    transport.markClosed();
                });
                try {
                    session.responseStream(transport).handle(req)
                            .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
                            .block();
                } catch (McpError error) {
                    transport.sendError(req.id(), error);
                } finally {
                    try {
                        transport.close();
                    } finally {
                        finishRequest(entry);
                    }
                }
            }, Duration.ZERO);
        }
        try {
            if (message instanceof McpSchema.JSONRPCResponse response) {
                session.accept(response)
                        .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
                        .block();
                return ServerResponse.accepted().build();
            }
            if (message instanceof McpSchema.JSONRPCNotification notification) {
                session.accept(notification)
                        .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
                        .block();
                return ServerResponse.accepted().build();
            }
            return ServerResponse.badRequest()
                    .body(McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST).message("Unknown message type").build());
        } finally {
            finishRequest(entry);
        }
    }

    private ServerResponse handleInitRequest(McpSchema.JSONRPCRequest req) {
        McpStreamableServerSession.Factory factory = this.sessionFactory;
        if (factory == null) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                            .message("Session factory not initialized").build());
        }
        boolean reservationHeld = false;
        try {
            McpSchema.InitializeRequest initRequest = jsonMapper.convertValue(req.params(),
                    McpSchema.InitializeRequest.class);
            SessionEntry evicted = null;
            synchronized (admissionLock) {
                if (closing) {
                    return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
                }
                if (sessions.size() + pendingAdmissions >= maxSessions) {
                    evicted = reclaimCapacity();
                    if (evicted == null) {
                        return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                                        .message("Maximum number of concurrent sessions reached").build());
                    }
                }
                pendingAdmissions++;
                reservationHeld = true;
            }
            if (evicted != null) {
                closeEvictedSession(evicted);
            }
            McpStreamableServerSession.McpStreamableServerSessionInit init = factory.startSession(initRequest);
            McpSchema.InitializeResult result = init.initResult().block();
            McpStreamableServerSession session = init.session();
            boolean shuttingDown;
            synchronized (admissionLock) {
                shuttingDown = closing;
                if (!shuttingDown) {
                    sessions.put(session.getId(), new SessionEntry(session, clock.instant()));
                }
                pendingAdmissions--;
                reservationHeld = false;
            }
            if (shuttingDown) {
                session.closeGracefully().block();
                return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
            }
            McpSchema.JSONRPCResponse response = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, req.id(),
                    result, null);
            return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.MCP_SESSION_ID, session.getId())
                    .body(response);
        } catch (IllegalArgumentException | McpError failure) {
            LOG.debug("failed to initialize MCP session: {}", failure.getMessage());
            return ServerResponse.badRequest()
                    .body(McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
                            .message("Failed to initialize session").build());
        } finally {
            if (reservationHeld) {
                synchronized (admissionLock) {
                    pendingAdmissions--;
                }
            }
        }
    }

    private ServerResponse handleDelete(ServerRequest request) {
        if (closing) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
        }
        if (disallowDelete) {
            return ServerResponse.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        }
        String sessionId = request.headers().firstHeader(HttpHeaders.MCP_SESSION_ID);
        if (sessionId == null || sessionId.isBlank()) {
            return ServerResponse.badRequest()
                    .body(McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST)
                            .message("Session ID required in mcp-session-id header").build());
        }
        SessionEntry entry;
        synchronized (admissionLock) {
            entry = sessions.remove(sessionId);
        }
        if (entry == null) {
            return ServerResponse.status(HttpStatus.NOT_FOUND)
                    .body(McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST).message("Session not found").build());
        }
        McpTransportContext transportContext = contextExtractor.extract(request);
        entry.session.delete()
                .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
                .block();
        return ServerResponse.ok().build();
    }

    private static final class SessionEntry {

        private final McpStreamableServerSession session;
        private Instant lastActivity;
        private int inFlight;

        SessionEntry(McpStreamableServerSession session, Instant lastActivity) {
            this.session = session;
            this.lastActivity = lastActivity;
        }
    }

    private final class SseSessionTransport implements McpStreamableServerTransport {

        private final String sessionId;
        private final ServerResponse.SseBuilder sseBuilder;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean closed;

        SseSessionTransport(String sessionId, ServerResponse.SseBuilder sseBuilder) {
            this.sessionId = sessionId;
            this.sseBuilder = sseBuilder;
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return sendMessage(message, null);
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
            return Mono.fromRunnable(() -> {
                if (closed) {
                    throw new IllegalStateException("SSE stream already closed for session " + sessionId);
                }
                lock.lock();
                try {
                    if (closed) {
                        throw new IllegalStateException("SSE stream already closed for session " + sessionId);
                    }
                    String json = jsonMapper.writeValueAsString(message);
                    if (messageId != null) {
                        sseBuilder.id(messageId).event(MESSAGE_EVENT_TYPE).data(json);
                    } else {
                        sseBuilder.event(MESSAGE_EVENT_TYPE).data(json);
                    }
                } catch (IOException writeFailure) {
                    closed = true;
                    throw new IllegalStateException("Failed to write SSE event for session " + sessionId, writeFailure);
                } finally {
                    lock.unlock();
                }
            });
        }

        void sendError(Object rpcId, McpError error) {
            McpSchema.JSONRPCResponse.JSONRPCError rpcError = error.getJsonRpcError() != null
                    ? error.getJsonRpcError()
                    : new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
                            "Internal error", null);
            McpSchema.JSONRPCResponse response = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, rpcId,
                    null, rpcError);
            try {
                sendMessage(response).block();
            } catch (IllegalStateException ignoredClosed) {
                LOG.debug("could not send MCP error frame to closed SSE stream {}", sessionId, ignoredClosed);
            }
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return jsonMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(this::close);
        }

        @Override
        public void close() {
            lock.lock();
            try {
                if (!closed) {
                    closed = true;
                    sseBuilder.complete();
                }
            } finally {
                lock.unlock();
            }
        }

        void markClosed() {
            closed = true;
        }
    }
}
