// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.gate;

import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.pgledger.database.repository.GrantStateRepository;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.springframework.stereotype.Component;

/**
 * Decides whether a write may start. The order is fixed: a verification through the author channel, the host
 * attestation (present; a signed token with an author of the right format; the signature under the live key of the
 * author, which is read and not locked; the tool, operation key and arguments digest of the call; the time
 * window), the project, the grant. The attestation is checked by the {@link AttestationVerifier}: nothing is looked up
 * for a caller that cannot prove its author, and the grant is only asked for the author the signature proves.
 *
 * <p>To prevent {@code session_grant} AB-BA deadlocks when movable entities change projects concurrently, {@link #open}
 * performs a read-only grant liveness pre-check (taking no {@code session_grant} row locks), and each write operation
 * acquires its entity locks first in canonical order ({@code project -> issue -> spec -> task -> milestone_criterion})
 * before calling {@link #authorizeAll} once to conditionally update and lock all final {@code session_grant} rows in
 * ascending {@code (project_id, grant_scope)} order.
 */
@Component
public class WriteGate {

    private final McpCallerResolver callerResolver;
    private final GrantStateRepository grants;
    private final Clock clock;

    public WriteGate(McpCallerResolver callerResolver, GrantStateRepository grants, Clock clock) {
        this.callerResolver = callerResolver;
        this.grants = grants;
        this.clock = clock;
    }

    /**
     * Runs the gate checks (read-only grant pre-check) and returns the trusted session.
     *
     * @param actor        the host attestation or caller token as the caller sent it
     * @param operationKey the operation key the attestation must be bound to (the key of the call; of a batch, the one
     *                     of the whole batch), as the caller sent it
     */
    public GateSession open(String actor, String operationKey, Channel channel, Lookup lookup) {
        // a verification through the author channel is refused first, so it is looked up first (once)
        Optional<Resolution> early = !channel.mayVerify() && lookup.mayVerify() ? Optional.of(lookup.find().get())
                : Optional.empty();
        early.ifPresent(found -> {
            if (found instanceof Resolution.Resolved resolved) {
                requireReviewerForVerification(channel, resolved.targets());
            }
        });
        // outside a tool call there is no tool and no digest: that is the call ToolCall.Outside, which matches nothing signed
        AuthorId author = callerResolver.verifyForWrite(actor, ToolCall.current(), operationKey);
        Resolution resolution = early.orElseGet(lookup.find());
        return switch (resolution) {
            case Resolution.Unresolved unresolved -> throw unresolved.failure();
            case Resolution.Resolved resolved -> {
                if (early.isEmpty()) {
                    requireReviewerForVerification(channel, resolved.targets());
                }
                Set<Target> distinct = new LinkedHashSet<>(resolved.targets());
                distinct.stream().sorted(Comparator.comparing((Target t) -> t.project().value())
                        .thenComparing(Target::scope)).forEach(target -> requireLive(author, target));
                yield new GateSession(author, channel);
            }
        };
    }

    /**
     * Only the caller check of the call, for a write that has to know its author before it looks at a project (the
     * batch reserves the receipt of its key first; each of its operations then passes {@link #open} with its own grants).
     * The checks and their order are those of {@link #open} up to caller verification.
     *
     * @param operationKey the operation key the attestation must be bound to, as the caller sent it
     */
    public AuthorId verifyAuthor(String actor, String operationKey) {
        return callerResolver.verifyForWrite(actor, ToolCall.current(), operationKey);
    }

    /**
     * The gate of a call that touches no project (the upload tools of the attachments): caller identity is checked like
     * for every write, and the author needs at least one live grant.
     */
    public GateSession openWithAnyGrant(String actor, Channel channel) {
        AuthorId author = callerResolver.verifyForWrite(actor, ToolCall.current(), "");
        if (grants.findLive(author, clock.instant()).isEmpty()) {
            throw new TrustException(TrustCode.SESSION_NOT_GRANTED, List.of(author.value(), "any", "any"),
                    Map.of("author", List.of(author.value())));
        }
        return new GateSession(author, channel);
    }

    private static void requireReviewerForVerification(Channel channel, List<Target> targets) {
        if (!channel.mayVerify() && targets.stream().anyMatch(t -> t.scope() == Scope.VERIFY)) {
            throw new TrustException(TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN, List.of());
        }
    }

    /** True when the active transport request already carries a resolved per-author session identity. */
    public boolean hasAmbientCaller() {
        return callerResolver.hasAmbientCaller();
    }

    /** Read-only liveness check for one grant without locking {@code session_grant}. */
    public void requireLive(AuthorId author, Target target) {
        if (!grants.hasLive(author, target.project(), target.scope(), clock.instant())) {
            throw notGranted(author, target);
        }
    }

    /**
     * Conditionally updates ({@code last_used_at}) and locks every required grant in ascending
     * {@code (project_id, grant_scope)} order. Call after all entity pins/locks of the operation have been acquired.
     */
    public void authorizeAll(AuthorId author, Collection<Target> targets) {
        Set<Target> distinct = new LinkedHashSet<>(targets);
        distinct.stream().sorted(Comparator.comparing((Target t) -> t.project().value())
                .thenComparing(Target::scope)).forEach(target -> authorize(author, target));
    }

    /** Checks (and stamps) one grant of an author that already passed the gate. */
    public void authorize(AuthorId author, Target target) {
        if (!grants.markUsed(author, target.project(), target.scope(), clock.instant())) {
            throw notGranted(author, target);
        }
    }

    private static TrustException notGranted(AuthorId author, Target target) {
        return new TrustException(TrustCode.SESSION_NOT_GRANTED,
                List.of(author.value(), target.scope().token(), target.project().value()),
                Map.of("author", List.of(author.value()), "scope", List.of(target.scope().token()),
                        "project", List.of(target.project().value())));
    }
}
