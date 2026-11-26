// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.support;

/** The problem detail (RFC 9457) the server answers an HTTP error with; {@code code} is the token of the contract. */
public record ProblemBody(int status, String detail, String code) {
}
