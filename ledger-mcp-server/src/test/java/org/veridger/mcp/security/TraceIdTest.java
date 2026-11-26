// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** HLP-004: the trace id of the catch-all 500 of the server comes from the injected id generator. */
class TraceIdTest {

    private static final UUID FIXED = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @Test
    @DisplayName("HLP-004 the trace id of an unexpected failure is the id of the injected generator, not a random id made by the advice")
    void theTraceIdComesFromTheGenerator() {
        StaticMessageSource source = new StaticMessageSource();
        source.setUseCodeAsDefaultMessage(true);
        McpHttpExceptionAdvice advice = new McpHttpExceptionAdvice(new MessageSourceAccessor(source, Locale.ENGLISH),
                () -> FIXED);

        ResponseEntity<Object> reply = advice.unexpected(new IllegalStateException("probe"));

        assertThat(reply.getBody()).isInstanceOfSatisfying(ProblemDetail.class, problem ->
                assertThat(problem.getProperties()).containsEntry("traceId", FIXED.toString()));
    }
}
