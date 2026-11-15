package org.pgledger.hook;

import tools.jackson.databind.node.ObjectNode;

/** The attestation decision for one ledger call. */
sealed interface Verdict {

    /**
     * The call may go on with {@code arguments}: the model's arguments plus {@code _hostAttestation}. The arguments
     * are arbitrary JSON of the model and stay a Jackson tree so that they are given back unchanged.
     *
     * @param attestation the text of the signed attestation token; it is written into the host's answer and
     *                    nowhere else (not logged)
     */
    record Stamped(ObjectNode arguments, String attestation) implements Verdict {}

    record Denied(Refusal reason) implements Verdict {}
}
