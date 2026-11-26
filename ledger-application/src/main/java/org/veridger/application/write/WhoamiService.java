// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.veridger.application.gate.McpCallerResolver;
import org.veridger.application.gate.ToolCall;
import org.veridger.database.repository.GrantStateRepository;
import org.veridger.domain.model.SessionGrant;
import org.veridger.domain.rule.Channel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What a declared author may do right now (read only). */
@Service
@Transactional(readOnly = true)
public class WhoamiService {

    /** One live grant. */
    public record GrantView(String project, String scope, Instant expiresAt) {
    }

    /** The author, whether its format is valid, whether the channel can verify, the login and the live grants. */
    public record Whoami(String author, boolean authorFormatOk, boolean canVerifyLogin, String login,
            List<GrantView> grants) {

        public Whoami {
            grants = List.copyOf(grants);
        }
    }

    private final McpCallerResolver callerResolver;
    private final GrantStateRepository grants;
    private final Clock clock;

    public WhoamiService(McpCallerResolver callerResolver, GrantStateRepository grants, Clock clock) {
        this.callerResolver = callerResolver;
        this.grants = grants;
        this.clock = clock;
    }

    /**
     * @param actor the host-stamped {@code <vendor>:<session-id>} author identifier; null or blank is
     *              HOST_ATTESTATION_REQUIRED.
     */
    public Whoami whoami(String actor, Channel channel) {
        return switch (callerResolver.prove(actor, ToolCall.current(), "")) {
            case McpCallerResolver.Proof.MalformedAuthor malformed ->
                new Whoami(malformed.text(), false, channel.mayVerify(), channel.login(), List.of());
            case McpCallerResolver.Proof.Proven proven -> {
                List<GrantView> live = grants.findLive(proven.author(), clock.instant()).stream()
                        .map(WhoamiService::view).toList();
                yield new Whoami(proven.author().value(), true, channel.mayVerify(), channel.login(), live);
            }
        };
    }

    private static GrantView view(SessionGrant grant) {
        return new GrantView(grant.projectId().value(), grant.scope().token(), grant.expiresAt());
    }
}
