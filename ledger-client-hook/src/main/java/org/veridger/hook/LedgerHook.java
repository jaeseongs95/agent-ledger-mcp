// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The host attestation rule, the same for every host: a ledger write call gets {@code _hostAttestation} stamped with
 * {@code <vendor>:<host id>} in the model's arguments, unless the arguments are not an object, already contain the
 * reserved key (anti-spoofing), the host id is missing or malformed, or {@code operationKey} is not a text. Nothing
 * else in the arguments is modified.
 *
 * <p>This class knows no host: the dialect of the client that the command line named is a bean found by
 * {@link HostDialects}, and the vendor of the authors is the one that {@link Client} pairs with the client.
 */
@Component
final class LedgerHook {

    private static final Logger LOG = LoggerFactory.getLogger(LedgerHook.class);
    private static final String RESERVED_KEY = Intake.RESERVED_KEY;
    private static final String OPERATION_KEY = "operationKey";

    private final HostDialects dialects;
    private final JsonCodec codec;

    LedgerHook(HostDialects dialects, JsonCodec codec) {
        this.dialects = dialects;
        this.codec = codec;
    }

    /** Turns the raw stdin bytes of one invocation for this client into the answer for the host. */
    Response handle(Client client, byte[] stdin) {
        if (isBlank(stdin)) {
            return new Response.Silent();
        }
        HostDialect dialect = dialects.of(client);
        Intake intake;
        try {
            intake = dialect.read(stdin);
        } catch (JacksonException malformed) {
            LOG.debug("the hook input is not valid JSON", malformed);
            return dialect.malformedInput();
        }
        return switch (intake) {
            case Intake.Skip skip -> dialect.notLedgerCall();
            case Intake.Refuse refuse -> dialect.deny(refuse.reason());
            case Intake.Attest call -> switch (stamp(call, client.vendor())) {
                case Verdict.Stamped stamped -> dialect.allow(call, stamped);
                case Verdict.Denied denied -> dialect.deny(denied.reason());
            };
        };
    }

    private Verdict stamp(Intake.Attest call, Vendor vendor) {
        if (!(call.arguments() instanceof ObjectNode arguments)) {
            return new Verdict.Denied(Refusal.ARGUMENTS_NOT_OBJECT);
        }
        if (arguments.has(RESERVED_KEY)) {
            return new Verdict.Denied(Refusal.RESERVED_FIELD);
        }
        if (!(call.host() instanceof HostId id)) {
            return new Verdict.Denied(Refusal.HOST_ID_INVALID);
        }
        if (call.tool().carriesOperationKey()) {
            JsonNode given = arguments.get(OPERATION_KEY);
            if (given != null && !given.isNull() && !given.isString()) {
                return new Verdict.Denied(Refusal.OPERATION_KEY_INVALID);
            }
        }
        ObjectNode stamped = arguments.deepCopy();
        String author = Author.of(vendor, id).value();
        stamped.put(RESERVED_KEY, author);
        return new Verdict.Stamped(stamped, author);
    }

    private static boolean isBlank(byte[] bytes) {
        for (byte b : bytes) {
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
                return false;
            }
        }
        return true;
    }
}
