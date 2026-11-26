// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.admin.app.LedgerAdminProperties;
import org.veridger.domain.error.AdminCode;
import org.veridger.domain.error.AdminException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * R3-F5: Admin RequestSizeFilter enforces maxRequestSize on unknown-length bodies even without Transfer-Encoding.
 */
class RequestSizeFilterUnknownLengthTest {

    @Test
    @DisplayName("R3-F5: admin filter refuses unknown Content-Length without Transfer-Encoding exceeding maxBytes with PAYLOAD_TOO_LARGE")
    void r3F5_adminUnknownLengthWithoutTransferEncodingRefused() throws Exception {
        LedgerAdminProperties props = new LedgerAdminProperties("0123456789abcdef", "fedcba9876543210",
                List.of("127.0.0.1"), DataSize.ofBytes(16), Path.of("/etc/veridger/jdbc-driver.yaml"),
                Path.of("/etc/veridger/drivers"), null, null, null, null, null);
        AtomicReference<Exception> resolved = new AtomicReference<>();
        HandlerExceptionResolver resolver = (req, res, handler, ex) -> {
            resolved.set(ex);
            res.setStatus(413);
            return null;
        };
        RequestSizeFilter filter = new RequestSizeFilter(props, resolver);

        MockHttpServletRequest oversized = new MockHttpServletRequest("POST", "/login") {
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
        assertThat(resolved.get()).isInstanceOf(AdminException.class);
        assertThat(((AdminException) resolved.get()).code()).isEqualTo(AdminCode.PAYLOAD_TOO_LARGE);
        assertThat(response.getStatus()).isEqualTo(413);
    }
}
