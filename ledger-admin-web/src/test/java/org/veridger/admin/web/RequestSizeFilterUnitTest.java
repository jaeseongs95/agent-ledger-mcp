// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.veridger.admin.app.LedgerAdminProperties;
import org.veridger.admin.app.SignInMethod;
import org.veridger.domain.error.AdminCode;
import org.veridger.domain.error.AdminException;

/**
 * Direct unit tests for {@link RequestSizeFilter} covering invalid charsets/media types, declared vs chunked payload
 * limits, empty chunked bodies, and {@code BufferedBodyRequest} form parsing and stream/reader replay.
 */
class RequestSizeFilterUnitTest {

    private static RequestSizeFilter filter(long maxBytes, AtomicReference<Exception> resolved) {
        LedgerAdminProperties props = new LedgerAdminProperties(
                "admin-token-0123456789", "agent-token-0123456789", List.of("127.0.0.1"),
                DataSize.ofBytes(maxBytes), Path.of("/tmp/d.yaml"), Path.of("/tmp/d"),
                new LedgerAdminProperties.Lockout(Duration.ofSeconds(60), 50, Duration.ofSeconds(1)),
                new LedgerAdminProperties.Oidc(null, null),
                new LedgerAdminProperties.Mcp(null, Duration.ofSeconds(2)),
                SignInMethod.TOKEN,
                new LedgerAdminProperties.Passkey(null, List.of(), "Ledger admin"),
                new LedgerAdminProperties.Update(true, "jaeseongs95/veridger", "https://api.github.com",
                        List.of("github.com"), Path.of("/tmp/u"), Duration.ofSeconds(10), "4.3.1", 13));
        HandlerExceptionResolver resolver = (req, res, handler, ex) -> {
            resolved.set(ex);
            return null;
        };
        return new RequestSizeFilter(props, resolver);
    }

    private static MockHttpServletRequest chunkedRequest(String contentType, byte[] bytes) {
        return new MockHttpServletRequest("POST", "/console") {
            {
                if (contentType != null) {
                    setContentType(contentType);
                }
            }

            @Override
            public long getContentLengthLong() {
                return -1L;
            }

            @Override
            public ServletInputStream getInputStream() {
                ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override
                    public int read() {
                        return bais.read();
                    }

                    @Override
                    public boolean isFinished() {
                        return bais.available() == 0;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        // test stub
                    }
                };
            }
        };
    }

    @Test
    @DisplayName("ADM-RSF-01 RequestSizeFilter rejects bad charsets and oversized declared or chunked bodies")
    void rejectsInvalidCharsetAndOversizedBodies() throws Exception {
        AtomicReference<Exception> resolved = new AtomicReference<>();
        RequestSizeFilter filter = filter(16L, resolved);

        MockHttpServletRequest badMedia = new MockHttpServletRequest("POST", "/console");
        badMedia.setContentType("not a media type");
        filter.doFilter(badMedia, new MockHttpServletResponse(), (req, res) -> {
            throw new AssertionError("should not pass");
        });
        assertThat(resolved.get()).isInstanceOf(HttpMediaTypeNotSupportedException.class);

        resolved.set(null);
        MockHttpServletRequest declaredTooBig = new MockHttpServletRequest("POST", "/console");
        declaredTooBig.setContent("01234567890123456789".getBytes(StandardCharsets.UTF_8));
        filter.doFilter(declaredTooBig, new MockHttpServletResponse(), (req, res) -> {
            throw new AssertionError("should not pass");
        });
        assertThat(resolved.get()).isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).code()).isEqualTo(AdminCode.PAYLOAD_TOO_LARGE);

        resolved.set(null);
        MockHttpServletRequest chunkedTooBig = chunkedRequest("application/json",
                "01234567890123456789".getBytes(StandardCharsets.UTF_8));
        filter.doFilter(chunkedTooBig, new MockHttpServletResponse(), (req, res) -> {
            throw new AssertionError("should not pass");
        });
        assertThat(resolved.get()).isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).code()).isEqualTo(AdminCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    @DisplayName("ADM-RSF-02 RequestSizeFilter buffers chunked forms, replays stream/reader, and passes empty bodies")
    void buffersChunkedFormsAndReplaysStreams() throws Exception {
        AtomicReference<Exception> resolved = new AtomicReference<>();
        RequestSizeFilter filter = filter(256L, resolved);

        // Empty chunked body passes original request straight through
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        MockHttpServletRequest emptyChunked = chunkedRequest(null, new byte[0]);
        filter.doFilter(emptyChunked, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));
        assertThat(seen.get()).isSameAs(emptyChunked);

        // Chunked form body with query param, blank segment, key-only, malformed %ZZ escape, and valid pairs
        MockHttpServletRequest formReq = chunkedRequest("application/x-www-form-urlencoded;charset=UTF-8",
                "&a=hello+world&flag&bad=%ZZ&a=second".getBytes(StandardCharsets.UTF_8));
        formReq.addParameter("q", "1");
        filter.doFilter(formReq, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));
        HttpServletRequest wrapped = seen.get();
        assertThat(wrapped.getParameter("q")).isEqualTo("1");
        assertThat(wrapped.getParameter("a")).isEqualTo("hello world");
        assertThat(wrapped.getParameterValues("a")).containsExactly("hello world", "second");
        assertThat(wrapped.getParameter("flag")).isEmpty();
        assertThat(wrapped.getParameter("bad")).isNull();
        assertThat(wrapped.getParameter("absent")).isNull();
        assertThat(Collections.list(wrapped.getParameterNames())).contains("q", "a", "flag");

        ServletInputStream is = wrapped.getInputStream();
        assertThat(is.isReady()).isTrue();
        assertThat(is.isFinished()).isFalse();
        assertThat(is.read()).isEqualTo('&');
        byte[] buf = new byte[128];
        int n = is.read(buf, 0, buf.length);
        assertThat(n).isPositive();
        assertThat(is.isFinished()).isTrue();
        assertThatThrownBy(() -> is.setReadListener(null)).isInstanceOf(IllegalStateException.class);
        assertThat(wrapped.getReader().readLine()).startsWith("&a=hello+world");

        // Chunked JSON body (non-form) does not parse body as form params
        MockHttpServletRequest jsonReq = chunkedRequest("application/json", "{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8));
        jsonReq.setCharacterEncoding("UTF-8");
        filter.doFilter(jsonReq, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));
        assertThat(seen.get().getParameterMap()).isEmpty();
        assertThat(seen.get().getReader().readLine()).isEqualTo("{\"k\":\"v\"}");
    }
}
