package org.pgledger.hook;

import java.time.Clock;
import java.util.Map;
import org.pgledger.attestation.ArgumentsDigest;
import org.pgledger.attestation.AttestationClaims;
import org.pgledger.hook.keys.SessionKey;
import org.pgledger.hook.keys.SessionKeyException;
import org.pgledger.hook.keys.SessionKeyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The attestation rule, the same for every host: a ledger write call gets {@code _hostAttestation} added to the
 * model's arguments, unless the arguments are not an object, already contain the reserved key, the host id is missing
 * or malformed, or the {@code operationKey} is not a text. The value is the text of an
 * {@link org.pgledger.attestation.AttestationToken}, signed with the private key of the author
 * {@code <vendor>:<host id>} for the MCP server of the call, for the canonical tool name ({@code ledger_<operation>} or
 * {@code artifact-<operation>}),
 * the operation key of the call, the digest of the arguments the hook hands the host (RFC 8785 canonical form, SHA-256:
 * {@link ArgumentsDigest}; the operation key is already in them, the attestation is not) and the time of the injected
 * clock. The operation key is the argument {@code operationKey}, exactly as the model gave it; the hook never makes
 * one up and adds nothing to the arguments but the attestation: a call without a key is signed with the empty key and
 * the server refuses it. whoami and the five attachment tools have no operation key in the claim
 * ({@link WriteTool#carriesOperationKey}): the claim is empty (the digest of the arguments covers the {@code operationKey}
 * of {@code artifact-begin}).
 * The digest covers the whole arguments object whatever its size (an attachment chunk is up to 349528 base64
 * characters): the hook neither limits nor truncates the arguments, the host's 10 second hook timeout is the bound.
 * Nothing else is changed.
 *
 * <p>The key pair of an author for a server is created in the key folder by the first call of that author for that
 * server.
 *
 * <p>This class knows no host: the dialect of the client that the command line named is a bean found by {@link HostDialects},
 * and the vendor of the authors is the one that {@link Client} pairs with the client.
 */
@Component
final class LedgerHook {

    // SLF4J at DEBUG, like the rest of the hook: see HookCli for why.
    private static final Logger LOG = LoggerFactory.getLogger(LedgerHook.class);
    private static final String RESERVED_KEY = Intake.RESERVED_KEY;
    private static final String OPERATION_KEY = "operationKey";

    private final HostDialects dialects;
    private final JsonCodec codec;
    private final Clock clock;
    private final SessionKeyStore keys;

    LedgerHook(HostDialects dialects, JsonCodec codec, Clock clock, SessionKeyStore keys) {
        this.dialects = dialects;
        this.codec = codec;
        this.clock = clock;
        this.keys = keys;
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
            // the one place where malformed input becomes the host's answer (the stderr contract allows no log line)
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
        // The arguments are arbitrary JSON of the model: the copy of the tree keeps every value as the host sent it.
        if (!(call.arguments() instanceof ObjectNode arguments)) {
            return new Verdict.Denied(Refusal.ARGUMENTS_NOT_OBJECT);
        }
        if (arguments.has(RESERVED_KEY)) {
            return new Verdict.Denied(Refusal.RESERVED_FIELD);
        }
        if (!(call.host() instanceof HostId id)) {
            return new Verdict.Denied(Refusal.HOST_ID_INVALID);
        }
        // the key is the caller's: the hook never makes one up. A call without one is signed with the empty key and the
        // server refuses it (VALIDATION_FAILED, path operationKey).
        String operationKey = "";
        if (call.tool().carriesOperationKey()) {
            JsonNode given = arguments.get(OPERATION_KEY);
            if (given != null && !given.isNull()) {
                if (!given.isString()) {
                    return new Verdict.Denied(Refusal.OPERATION_KEY_INVALID);
                }
                operationKey = given.stringValue();
            }
        }
        // what the host hands the server, without the attestation: the signature covers exactly this (F1/F3)
        ObjectNode stamped = arguments.deepCopy();
        String argumentsDigest;
        try {
            argumentsDigest = ArgumentsDigest.of((Map<?, ?>) codec.plain(stamped));
        } catch (IllegalArgumentException | JacksonException notSignable) {
            // for example 1e999: no double, so no canonical form; nothing is signed and no key is created
            LOG.debug("the tool arguments cannot be put into the canonical form", notSignable);
            return new Verdict.Denied(Refusal.ARGUMENTS_NOT_SIGNABLE);
        }
        String author = Author.of(vendor, id).value();
        SessionKey key;
        try {
            key = keys.load(call.server(), author);
        } catch (SessionKeyException unusable) {
            // the denial names no path and no key; the reason is in the cause (fine level only)
            LOG.debug("the session key of the author cannot be used", unusable);
            return new Verdict.Denied(Refusal.SESSION_KEY_UNAVAILABLE);
        }
        AttestationClaims claims = new AttestationClaims(author, call.tool().canonicalName(), operationKey,
                argumentsDigest, clock.instant().getEpochSecond());
        String attestation = key.sign(claims).text();
        stamped.put(RESERVED_KEY, attestation);
        return new Verdict.Stamped(stamped, attestation);
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
