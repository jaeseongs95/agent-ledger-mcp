// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.mcp.security.McpHttpExceptionAdvice;
import org.agentledger.mcp.support.Calls;
import org.agentledger.mcp.support.McpServerTest;
import org.agentledger.mcp.support.McpSession;
import org.agentledger.mcp.support.McpTestInfrastructure.FailingRoute;
import org.agentledger.mcp.support.McpTestSettings;
import org.agentledger.mcp.support.ProbeTools;
import org.agentledger.mcp.support.RawHttp;
import org.agentledger.mcp.support.RawHttp.Header;
import org.agentledger.mcp.support.RawHttp.Reply;
import org.agentledger.mcp.support.Scenario;
import org.agentledger.mcp.tool.WriteOutputs.Whoami;
import org.slf4j.LoggerFactory;

/**
 * MCC-PRN-01: the HTTP errors of the server are answered by the one advice, also the failure nobody expected. (The size
 * check through the advice, MCC-PRN-02, is run by the cases of the payload limit.)
 */
@McpServerTest
class HttpErrorsTest {

    /** The problem detail with the trace id of a server failure. */
    private record Problem(int status, String detail, String code, String traceId) {
    }

    @Test
    @DisplayName("MCC-PRN-01: an unexpected exception inside an HTTP handler is 500 application/problem+json with the code "
            + "INTERNAL_ERROR, a trace id and the plain sentence, never its own text; it is logged at ERROR with the same "
            + "trace id, and the server keeps serving")
    void anUnexpectedFailureIsTheCatchAll500(RawHttp http, McpSession session) {
        ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger(McpHttpExceptionAdvice.class);
        logger.addAppender(log);
        Reply reply;
        try {
            reply = http.get(FailingRoute.PATH, Header.bearer(McpTestSettings.TOKEN));
        } finally {
            logger.detachAppender(log);
        }

        assertThat(reply.status()).isEqualTo(500);
        assertThat(reply.header("Content-Type")).hasValueSatisfying(type -> assertThat(type)
                .startsWith("application/problem+json"));
        Problem problem = session.mapper().readValue(reply.body(), Problem.class);
        assertThat(problem.status()).isEqualTo(500);
        assertThat(problem.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(problem.detail()).isEqualTo("An internal error occurred.");
        assertThat(problem.traceId()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(reply.body()).doesNotContain(ProbeTools.SECRET_DETAIL).doesNotContain("IllegalStateException");
        assertThat(log.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains(problem.traceId());
            assertThat(event.getThrowableProxy().getMessage()).contains(ProbeTools.SECRET_DETAIL);
        });
        assertThat(session.ok("ledger_whoami", Calls.Whoami.as(Scenario.AUTHOR), Whoami.class).author())
                .isEqualTo(Scenario.AUTHOR);
    }

    @Test
    @DisplayName("MCC-PRN-01: the failing route needs the bearer token like the rest of the server: without it the answer "
            + "is 401, not 500")
    void theRouteIsBehindTheSecurity(RawHttp http) {
        assertThat(http.get(FailingRoute.PATH).status()).isEqualTo(401);
    }
}
