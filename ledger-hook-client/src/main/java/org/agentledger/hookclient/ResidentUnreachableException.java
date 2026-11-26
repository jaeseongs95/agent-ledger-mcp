// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hookclient;

/**
 * The resident program did not take the call: nothing listens at its address (it ended, or is ending), or it does not know the
 * secret of the address file (another program replaced it since the file was read). The call was not processed, so asking again
 * after finding the program anew is safe; any other failure of a call is a plain {@link ResidentException} and is not asked again.
 */
public final class ResidentUnreachableException extends ResidentException {

    private static final long serialVersionUID = 1L;

    public ResidentUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }

    public ResidentUnreachableException(String message) {
        super(message);
    }
}
