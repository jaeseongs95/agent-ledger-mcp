// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.passkey;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.value.Sha256;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The approvals this process asked for that no write has used yet. An approval is a passkey assertion over a challenge; the
 * challenge is the SHA-256 of the intent, the JSON text of what is to be approved: the method and the path of the owner write,
 * the digest of its parameters (every field of the form but the CSRF token and the approval itself), the digest of the session
 * it is for, a random nonce and the time it was asked for. The server remembers the {@link Binding} under the challenge and,
 * when the write arrives, compares it with the write that actually came ({@link #spend}), so a signature is good for exactly
 * one change in exactly one session, once, for {@link #TTL}.
 *
 * <p>ceiling: per process {@value #PENDING_LIMIT} approvals; an approval older than {@link #TTL} is dropped when the next is
 * asked for, and past the limit the oldest is dropped first (its page asks again). A restart empties the memory, so nothing asked
 * for by an earlier run is accepted by the next.
 */
@Component
public final class ApprovalIntents {

    /** How long an approval may wait for its write. */
    public static final Duration TTL = Duration.ofMinutes(2);

    static final String CSRF_FIELD = "_csrf";
    static final String APPROVAL_FIELD = "approval";

    private static final int PENDING_LIMIT = 1024;
    private static final int NONCE_BYTES = 16;

    /** What an approval is for: the write (method, path, parameters) and the session. */
    public record Binding(String method, String path, String params, String session) {
    }

    /** The text that is hashed into the challenge. */
    private record Intent(String method, String path, String params, String session, String nonce, String issuedAt) {
    }

    private record Pending(Binding binding, Instant issuedAt) {
    }

    private final Clock clock;
    private final JsonMapper mapper;
    private final SecureRandom random = new SecureRandom();
    /** The challenge (base64url of the SHA-256 of the intent) to what it is for, oldest first. */
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    public ApprovalIntents(Clock clock, JsonMapper mapper) {
        this.clock = clock;
        this.mapper = mapper;
    }

    /**
     * The binding of a write: the parameters are digested by name and value in a fixed order (the CSRF token and the approval
     * field are not part of the write), the session by its id.
     */
    public static Binding binding(String method, String path, Map<String, List<String>> parameters, String sessionId) {
        Map<String, List<String>> sorted = new TreeMap<>();
        parameters.forEach((name, values) -> {
            if (!Objects.equals(CSRF_FIELD, name) && !Objects.equals(APPROVAL_FIELD, name)) {
                sorted.put(name, values.stream().sorted().toList());
            }
        });
        StringBuilder canonical = new StringBuilder();
        sorted.forEach((name, values) -> values.forEach(value -> canonical.append(name).append('=').append(value)
                .append('\n')));
        return new Binding(method, path, Sha256.ofText(canonical.toString()).hex(), Sha256.ofText(sessionId).hex());
    }

    /** Remembers an approval for the binding and returns its challenge (32 bytes). */
    public synchronized byte[] issue(Binding binding) {
        Instant now = clock.instant();
        Iterator<Pending> oldest = pending.values().iterator();
        while (oldest.hasNext()) {
            Pending candidate = oldest.next();
            if (pending.size() < PENDING_LIMIT && Duration.between(candidate.issuedAt(), now).compareTo(TTL) <= 0) {
                break;
            }
            oldest.remove();
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        String intent = mapper.writeValueAsString(new Intent(binding.method(), binding.path(), binding.params(),
                binding.session(), Base64.getUrlEncoder().withoutPadding().encodeToString(nonce), now.toString()));
        byte[] challenge = HexFormat.of().parseHex(Sha256.ofText(intent).hex());
        pending.put(Base64.getUrlEncoder().withoutPadding().encodeToString(challenge), new Pending(binding, now));
        return challenge;
    }

    /**
     * Spends the approval of this challenge for the write that came: it is gone after this call, whatever the answer is.
     *
     * @throws AdminException PASSKEY_APPROVAL_INVALID when the challenge is not one this process asked for, was used, has
     *         expired or is for another write or another session
     */
    public synchronized void spend(String challenge, Binding binding) {
        Pending asked = pending.remove(challenge);
        if (asked == null) {
            throw invalid("unknown or used");
        }
        if (Duration.between(asked.issuedAt(), clock.instant()).compareTo(TTL) > 0) {
            throw invalid("expired");
        }
        if (!Objects.equals(asked.binding(), binding)) {
            throw invalid("another change or another session");
        }
    }

    private static AdminException invalid(String reason) {
        return new AdminException(AdminCode.PASSKEY_APPROVAL_INVALID, List.of(reason));
    }
}
