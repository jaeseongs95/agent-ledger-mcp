// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.agentledger.attestation.ArgumentsDigest;
import org.agentledger.domain.rule.ToolContract;
import org.agentledger.domain.value.AuthorId;
import org.springframework.context.ApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the client hook does for the tests: a write call whose {@code _hostAttestation} is a plain author of the ledger
 * format ({@code claude:e2e-author}) becomes the call the hook would send: the author is signed for the tool and the
 * {@code operationKey} of the call exactly as the call carries it, and the signed text takes the place of the plain
 * author. Like the hook, it never makes a key up and changes no argument but {@code _hostAttestation}: a call without
 * a key (or with a key that is no text) is signed with the empty key, and the server refuses it. The calls of
 * {@link Calls} carry a fresh key already; a test that wants another key puts it into the call. This is the one place of
 * the MCP tests that makes a signature for the tests that are not about the attestation itself; the authors are
 * registered with their keys the first time they are used. A value that is not a plain author (an empty or malformed
 * one, a token a test made itself) is sent as it is, and so is a call whose test asks for
 * {@link McpSession#callUnsigned}.
 */
public final class Attester {

    private static final String ATTESTATION = "_hostAttestation";
    private static final String OPERATION_KEY = "operationKey";
    private static final String WHOAMI = "ledger_whoami";
    /** The tools that carry the attestation: the nine writes, whoami and the five attachment tools (a read tool never gets one). */
    private static final Set<String> ATTESTED_TOOLS = Stream.concat(ToolContract.WRITE.stream(),
            ToolContract.ATTACHMENT.stream()).collect(Collectors.toUnmodifiableSet());
    /** The tools whose attestation is signed with an empty operation key: whoami and the attachment tools. */
    private static final Set<String> WITHOUT_OPERATION_KEY = Stream.concat(Stream.of(WHOAMI),
            ToolContract.ATTACHMENT.stream()).collect(Collectors.toUnmodifiableSet());

    private final KeyRing keys;
    private final JsonMapper mapper;

    private Attester(KeyRing keys, JsonMapper mapper) {
        this.keys = keys;
        this.mapper = mapper;
    }

    /** The attester of the server in the context. */
    public static Attester of(ApplicationContext context) {
        return new Attester(context.getBean(KeyRing.class), context.getBean(JsonMapper.class));
    }

    /** An attester that changes nothing (a client without a hook). */
    public static Attester none() {
        return new Attester(null, null);
    }

    /** The arguments as the hook would send them; the object itself when there is nothing to sign. */
    public Object stamp(String tool, Object arguments) {
        if (keys == null || !ATTESTED_TOOLS.contains(tool)) {
            return arguments;
        }
        Map<String, Object> call = mapper.convertValue(arguments, new TypeReference<LinkedHashMap<String, Object>>() {
        });
        if (!(call.get(ATTESTATION) instanceof String author) || !AuthorId.matches(author)) {
            return arguments;
        }
        String operationKey = "";
        if (!WITHOUT_OPERATION_KEY.contains(tool) && call.get(OPERATION_KEY) instanceof String given) {
            operationKey = given;
        }
        keys.enroll(author);
        // the hook signs the arguments it hands the host: the operation key is in as the caller sent it, the attestation itself is not
        call.put(ATTESTATION, keys.token(author, tool, operationKey, ArgumentsDigest.of(call)));
        return call;
    }
}
