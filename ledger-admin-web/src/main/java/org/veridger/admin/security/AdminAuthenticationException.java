// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.security;

import org.veridger.domain.error.AdminCode;
import org.springframework.security.core.AuthenticationException;

/** An authentication failure that has a code of the external contract (the failure handlers turn it into a problem). */
public final class AdminAuthenticationException extends AuthenticationException {

    private static final long serialVersionUID = 1L;

    private final transient AdminCode code;

    public AdminAuthenticationException(AdminCode code) {
        super(code.token());
        this.code = code;
    }

    public AdminAuthenticationException(AdminCode code, Throwable cause) {
        super(code.token(), cause);
        this.code = code;
    }

    public AdminCode code() {
        return code;
    }
}
