// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.mcp.config.LedgerMcpProperties;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * R3-F5: RequestSizeFilter enforces maxRequestSize on unknown-length bodies even without Transfer-Encoding (e.g. HTTP/2).
 */
class RequestSizeFilterUnknownLengthTest {

    @Test
    @DisplayName("R3-F5: unknown Content-Length without Transfer-Encoding exceeding maxBytes is refused with RequestTooLargeException")
    void r3F5_unknownLengthWithoutTransferEncodingRefused() throws Exception {
        LedgerMcpProperties props = new LedgerMcpProperties("0123456789abcdef", List.of("127.0.0.1"), List.of(),
                DataSize.ofBytes(16), Duration.ofSeconds(5));
        AtomicReference<Exception> resolved = new AtomicReference<>();
        HandlerExceptionResolver resolver = (req, res, handler, ex) -> {
            resolved.set(ex);
            res.setStatus(413);
            return null;
        };
        RequestSizeFilter filter = new RequestSizeFilter(props, resolver);

        MockHttpServletRequest oversized = new MockHttpServletRequest("POST", "/mcp") {
            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        oversized.setContent(new byte[32]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();
        FilterChain chain = (req, res) -> chainCalled.set(true);

        filter.doFilter(oversized, response, chain);

        assertThat(chainCalled.get()).isFalse();
        assertThat(resolved.get()).isInstanceOf(RequestTooLargeException.class);
        assertThat(response.getStatus()).isEqualTo(413);
    }
}
