// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.veridger.admin.app.LedgerAdminProperties;
import org.veridger.domain.error.AdminCode;
import org.veridger.domain.error.AdminException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Refuses a request whose body is larger than the configured size with 413, before the handler reads it: by the
 * declared {@code Content-Length}, or, for a body that is sent in chunks and declares no length, by reading at most
 * the limit plus one byte. A body within the limit is handed on from memory (at most the limit of one request);
 * nothing beyond the limit is read.
 *
 * <p>Why this is a filter of ours (principle 2.1): the connector settings do not give a deterministic 413. The
 * Tomcat 11 HTTP connector documentation (https://tomcat.apache.org/tomcat-11.0-doc/config/http.html) says
 * {@code maxPostSize} is the limit of a form POST that the container parses (what it does not accept is dropped
 * silently, so the request fails later as a missing CSRF token, not as "too large") and {@code maxSwallowSize} the
 * number of bytes it swallows of an aborted upload. Spring's {@code ContentCachingRequestWrapper} caches but does not
 * limit what the handler reads. The same filter guards the MCP server (its Javadoc has the same citation). The refusal
 * is not written here (principle 3.8): the exception goes to the {@link HandlerExceptionResolver} of the dispatcher,
 * which hands it to the one HTTP error advice that renders the problem detail.
 *
 * <p>The filter is a component; {@code AdminWebConfiguration} registers it (URL pattern and order).
 */
@Component
public final class RequestSizeFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final HandlerExceptionResolver errors;

    public RequestSizeFilter(LedgerAdminProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        this.maxBytes = properties.maxRequestSize().toBytes();
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            refuse(request, response);
            return;
        }
        if (declared < 0) {
            byte[] body = request.getInputStream().readNBytes(Math.toIntExact(maxBytes + 1));
            if (body.length > maxBytes) {
                refuse(request, response);
                return;
            }
            if (body.length > 0) {
                chain.doFilter(new BufferedBodyRequest(request, body), response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response) {
        errors.resolveException(request, response, null, new AdminException(AdminCode.PAYLOAD_TOO_LARGE, List.of()));
    }

    /**
     * The request with the body that was read: the handler reads it again from memory. The body of a form was consumed from
     * the container's own request, which can no longer parse it, so the parameters of the form are read from the buffered body
     * here (the filter stands in front of the security chain, which asks for the CSRF token of the form).
     */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private static final String FORM = "application/x-www-form-urlencoded";

        private final byte[] body;
        private Map<String, String[]> parameters;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public String getParameter(String name) {
            String[] values = getParameterMap().get(name);
            return values == null || values.length == 0 ? null : values[0];
        }

        @Override
        public String[] getParameterValues(String name) {
            return getParameterMap().get(name);
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(getParameterMap().keySet());
        }

        /** The parameters of the query string, then those of the form body when the body is a form. */
        @Override
        public synchronized Map<String, String[]> getParameterMap() {
            if (parameters == null) {
                Map<String, List<String>> merged = new LinkedHashMap<>();
                super.getParameterMap().forEach((name, values) -> merged.put(name, new ArrayList<>(List.of(values))));
                String type = getContentType();
                if (type != null && type.regionMatches(true, 0, FORM, 0, FORM.length())) {
                    addForm(merged, charset().decode(ByteBuffer.wrap(body)).toString());
                }
                Map<String, String[]> built = new LinkedHashMap<>();
                merged.forEach((name, values) -> built.put(name, values.toArray(String[]::new)));
                parameters = Collections.unmodifiableMap(built);
            }
            return parameters;
        }

        private void addForm(Map<String, List<String>> into, String form) {
            for (String pair : form.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int equals = pair.indexOf('=');
                String name = equals < 0 ? pair : pair.substring(0, equals);
                String value = equals < 0 ? "" : pair.substring(equals + 1);
                String decodedName;
                String decodedValue;
                try {
                    decodedName = URLDecoder.decode(name, charset());
                    decodedValue = URLDecoder.decode(value, charset());
                } catch (IllegalArgumentException malformed) {
                    // a pair with a broken percent escape is dropped, as the container drops it
                    continue;
                }
                into.computeIfAbsent(decodedName, key -> new ArrayList<>()).add(decodedValue);
            }
        }

        private Charset charset() {
            String name = getCharacterEncoding();
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {

                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return source.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("the body is read from memory, not asynchronously");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
        }
    }
}
