// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.passkey;

import com.webauthn4j.util.exception.WebAuthnException;
import com.webauthn4j.verifier.exception.BadOriginException;
import com.webauthn4j.verifier.exception.BadRpIdException;
import java.util.List;
import org.agentledger.domain.error.AdminCode;
import org.agentledger.domain.error.AdminException;

/**
 * Turns what WebAuthn4J and the WebAuthn support of Spring Security throw when a ceremony does not verify into the business
 * failures of the admin: a wrong host name or origin is {@code PASSKEY_HOST_MISMATCH} (naming the host name the passkeys are
 * for), a credential that is not known is {@code PASSKEY_NONE_REGISTERED} when no passkey is live at all, and every other
 * failure is {@code PASSKEY_ASSERTION_INVALID} naming the kind of check that failed (never an input value).
 */
final class AssertionFailures {

    private AssertionFailures() {
    }

    /**
     * @param failure    what the verification threw
     * @param rpId       the relying party id of this admin web
     * @param anyPasskey whether a passkey is live
     */
    static AdminException translate(RuntimeException failure, String rpId, boolean anyPasskey) {
        if (failure instanceof BadOriginException || failure instanceof BadRpIdException) {
            return new AdminException(AdminCode.PASSKEY_HOST_MISMATCH, List.of(rpId));
        }
        if (failure instanceof WebAuthnException) {
            return new AdminException(AdminCode.PASSKEY_ASSERTION_INVALID, List.of(failure.getClass().getSimpleName()));
        }
        if (!anyPasskey) {
            return new AdminException(AdminCode.PASSKEY_NONE_REGISTERED, List.of());
        }
        return new AdminException(AdminCode.PASSKEY_ASSERTION_INVALID, List.of("credential"));
    }
}
