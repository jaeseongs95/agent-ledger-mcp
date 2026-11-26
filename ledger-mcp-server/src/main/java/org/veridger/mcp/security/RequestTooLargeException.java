// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.security;

/**
 * The body of a request is larger than the configured size ({@link RequestSizeFilter}). The filter only raises it; the
 * one HTTP error advice ({@link McpHttpExceptionAdvice}) decides what the client is told.
 */
public final class RequestTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RequestTooLargeException() {
        super("the request body is larger than the configured size");
    }
}
