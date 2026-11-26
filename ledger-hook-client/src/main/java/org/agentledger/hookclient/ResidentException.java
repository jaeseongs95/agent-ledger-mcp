// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hookclient;

/**
 * The resident hook program cannot be reached, started or understood. The message says what failed in words for the user;
 * it holds no secret and no content of the call. The light client turns it into a line on the standard error and exit code 2,
 * the code with which the hook has always failed a call that it cannot answer.
 */
public class ResidentException extends Exception {

    private static final long serialVersionUID = 1L;

    public ResidentException(String message) {
        super(message);
    }

    public ResidentException(String message, Throwable cause) {
        super(message, cause);
    }
}
