// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.veridger.application.gate.McpCallerResolver;
import org.veridger.application.gate.ToolCall;
import org.veridger.database.repository.GrantStateRepository;
import org.veridger.domain.model.SessionGrant;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.ProjectId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What a declared author may do right now (read only). */
@Service
@Transactional(readOnly = true)
public class WhoamiService {

    /** One live grant. */
    public record GrantView(String project, String scope, Instant expiresAt) {
    }

    /**
     * The author, whether its format is valid, whether this caller can perform reviewer verifications (either via the
     * reviewer channel or by holding any live {@link Scope#VERIFY} grant on the unified MCP connection), the resolved
     * login label, and the live grants.
     */
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
        return whoami(actor, channel, null);
    }

    public Whoami whoami(String actor, Channel channel, String projectFilter) {
        ProjectId project = projectFilter == null ? null : ProjectId.of(projectFilter);
        return switch (callerResolver.prove(actor, ToolCall.current(), "")) {
            case McpCallerResolver.Proof.MalformedAuthor malformed ->
                new Whoami(malformed.text(), false, channel.mayVerify(), channel.login(), List.of());
            case McpCallerResolver.Proof.Proven proven -> {
                List<SessionGrant> live = grants.findLive(proven.author(), clock.instant());
                boolean canVerify = channel.mayVerify()
                        || live.stream().anyMatch(grant -> grant.scope() == Scope.VERIFY);
                String login = canVerify ? Channel.REVIEWER.login() : channel.login();
                List<GrantView> views = live.stream()
                        .filter(grant -> project == null || Objects.equals(grant.projectId(), project))
                        .map(WhoamiService::view).toList();
                yield new Whoami(proven.author().value(), true, canVerify, login, views);
            }
        };
    }

    private static GrantView view(SessionGrant grant) {
        return new GrantView(grant.projectId().value(), grant.scope().token(), grant.expiresAt());
    }
}
