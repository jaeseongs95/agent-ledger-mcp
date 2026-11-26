// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.admin;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.agentledger.application.gate.ProjectPinner;
import org.agentledger.database.repository.Counter;
import org.agentledger.database.repository.GrantStateRepository;
import org.agentledger.database.repository.IdAllocator;
import org.agentledger.database.repository.ProjectRepository;
import org.agentledger.database.repository.SessionGrantRepository;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.ConflictException;
import org.agentledger.domain.error.LookupCode;
import org.agentledger.domain.error.LookupException;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationReason;
import org.agentledger.domain.model.SessionGrant;
import org.agentledger.domain.rule.LockOrder;
import org.agentledger.domain.rule.OwnerAction;
import org.agentledger.domain.rule.Scope;
import org.agentledger.domain.value.AuthorId;
import org.agentledger.domain.value.OwnerIdentity;
import org.agentledger.domain.value.ProjectId;
import org.agentledger.domain.value.Tokens;
import org.agentledger.domain.value.UtcInstant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session grants, used by the admin web and the CLI (never by the MCP tools). A grant is a row per scope; granting
 * closes the live grant of the same key first and rows are never deleted. Two grants of one key at the same time
 * end with exactly one live row. Grants apply directly to any valid {@code <vendor>:<session-id>} author without
 * requiring any separate session key or per-session token registration.
 *
 * <p>The order of the locks of every method is the one of the writes: the project row, then the {@code session_grant} rows (in
 * {@link LockOrder}), then the numbers (see {@code Counter}).
 */
@Service
@Transactional
public class GrantService {

    /** Who may do what: author, project, scopes (tokens), optional expiry (ISO-8601 with Z or an offset), note. */
    public record GrantCommand(String author, String project, List<String> scopes, String expiresAt, String note) {
    }

    /** What to revoke: all live grants of an author, narrowed by project and, with a project, by scope. */
    public record RevokeCommand(String author, String project, String scope) {
    }

    /** Desired scopes for one author on one project; scopes not listed are revoked and newly listed ones are issued. */
    public record SyncCommand(String author, String project, List<String> scopes, String expiresAt, String note) {
    }

    /** How many scopes were added and revoked during one {@link #syncProjectScopes} call. */
    public record SyncResult(int added, int revoked) {
    }

    private final GrantStateRepository state;
    private final SessionGrantRepository grants;
    private final ProjectRepository projects;
    private final ProjectPinner projectPinner;
    private final IdAllocator ids;
    private final OwnerAuditWriter audit;
    private final Clock clock;

    public GrantService(GrantStateRepository state, SessionGrantRepository grants, ProjectRepository projects,
            ProjectPinner projectPinner, IdAllocator ids, OwnerAuditWriter audit, Clock clock) {
        this.state = state;
        this.grants = grants;
        this.projects = projects;
        this.projectPinner = projectPinner;
        this.ids = ids;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Issues the grants and returns the id of each, one per scope in the order given; who issued them is recorded in the
     * owner's audit trail in the same transaction. Two grants of one key at the same moment are decided by the unique key
     * of the live grant: the call that loses it ends with the translated DUPLICATE_KEY, and no call is run again.
     */
    public List<Long> grant(GrantCommand command, OwnerIdentity actor) {
        Plan plan = plan(command);
        return issueAll(plan, actor);
    }

    /** What a grant call asks for, checked: the author, the project, the scopes in the order given, the expiry and the time. */
    private record Plan(AuthorId author, ProjectId project, List<Scope> scopes, Instant expiresAt, String note, Instant now) {
    }

    private Plan plan(GrantCommand command) {
        AuthorId author = Authors.of(command.author());
        ProjectId project = ProjectId.of(command.project());
        if (!projects.existsById(project)) {
            throw new LookupException(LookupCode.NOT_FOUND, List.of("project", project.value()));
        }
        if (command.scopes() == null || command.scopes().isEmpty()) {
            throw ValidationException.invalid("scopes", ValidationReason.MUST_NAME_A_SCOPE,
                    Tokens.allowed(Scope.values()));
        }
        Set<Scope> scopes = new LinkedHashSet<>();
        command.scopes().forEach(token -> scopes.add(Tokens.parse("scopes", token, Scope.values())));
        Instant expiresAt = command.expiresAt() == null || command.expiresAt().isBlank() ? null
                : UtcInstant.parse("expires_at", command.expiresAt()).value();
        return new Plan(author, project, List.copyOf(scopes), expiresAt, command.note(), clock.instant());
    }

    /**
     * Pins the project row first (the first lock of the order of the locks, which serializes concurrent multi-scope grants of
     * one project and keeps the unique key of the live grant from deadlocking with a revocation), then closes the live grants of
     * the same key in {@link LockOrder} before the number of {@code Counter.SESSION_GRANT} is taken, then inserts the new ones
     * in the order the caller gave, so that the ids returned keep it.
     */
    private List<Long> insertGrants(Plan plan) {
        projectPinner.pin(plan.project());
        for (Scope scope : plan.scopes().stream().sorted(LockOrder.SCOPE).toList()) {
            closeLive(plan.author(), plan.project(), scope, plan.now());
        }
        List<Long> issued = new ArrayList<>(plan.scopes().size());
        for (Scope scope : plan.scopes()) {
            issued.add(insertGrant(plan.author(), plan.project(), scope, plan.now(), plan.expiresAt(), plan.note()));
        }
        return issued;
    }

    private List<Long> issueAll(Plan plan, OwnerIdentity actor) {
        List<Long> issued = insertGrants(plan);
        recordIssued(plan, issued, actor);
        return issued;
    }

    /** The last step of every grant: the owner's audit row (its number is the last one a grant takes). */
    private void recordIssued(Plan plan, List<Long> issued, OwnerIdentity actor) {
        audit.record(actor, OwnerAction.GRANT_ISSUED, plan.author().value() + "|" + plan.project().value(),
                "scopes=" + plan.scopes().stream().map(Scope::token).collect(Collectors.joining(",")) + " expires="
                        + (plan.expiresAt() == null ? "never" : plan.expiresAt()) + " grants="
                        + issued.stream().map(String::valueOf).collect(Collectors.joining(",")));
    }

    private long insertGrant(AuthorId author, ProjectId project, Scope scope, Instant now, Instant expiresAt,
            String note) {
        long id = ids.next(Counter.SESSION_GRANT);
        try {
            grants.insert(SessionGrant.issue(id, author, project, scope, AuthorId.OWNER, now, expiresAt, note));
        } catch (DuplicateKeyException duplicate) {
            // another grant of the same key became live at the same moment: the unique key of the live grant decided
            throw new ConflictException(ConflictCode.DUPLICATE_KEY,
                    List.of("grant " + author.value() + "|" + project.value() + "|" + scope.token()), duplicate);
        }
        return id;
    }

    private void closeLive(AuthorId author, ProjectId project, Scope scope, Instant now) {
        grants.findByActiveKey(SessionGrant.liveKey(author, project, scope))
                .ifPresent(open -> state.revoke(open.id(), now));
    }

    /** Revokes the matching live grants, records who did it in the owner's audit trail, and returns how many. */
    public int revoke(RevokeCommand command, OwnerIdentity actor) {
        return revokeAll(command, actor);
    }

    /**
     * Synchronizes an author's live scopes on a single project to match {@code command.scopes()} in one transaction.
     * Unchanged live scopes keep their existing rows; removed scopes are revoked and newly requested scopes are issued.
     * Lock order matches {@link #insertGrants}: project row first, then {@code session_grant} rows in
     * {@link LockOrder#SCOPE}, then counter allocation and audit rows.
     */
    public SyncResult syncProjectScopes(SyncCommand command, OwnerIdentity actor) {
        AuthorId author = Authors.of(command.author());
        ProjectId project = ProjectId.of(command.project());
        if (!projects.existsById(project)) {
            throw new LookupException(LookupCode.NOT_FOUND, List.of("project", project.value()));
        }
        Set<Scope> desired = new LinkedHashSet<>();
        if (command.scopes() != null) {
            command.scopes().forEach(token -> desired.add(Tokens.parse("scopes", token, Scope.values())));
        }
        Instant expiresAt = command.expiresAt() == null || command.expiresAt().isBlank() ? null
                : UtcInstant.parse("expires_at", command.expiresAt()).value();
        Instant now = clock.instant();

        projectPinner.pin(project);
        Map<Scope, SessionGrant> current = new LinkedHashMap<>();
        for (SessionGrant liveGrant : state.findLive(author, now)) {
            if (Objects.equals(project, liveGrant.projectId())) {
                current.put(liveGrant.scope(), liveGrant);
            }
        }

        List<SessionGrant> toRevoke = current.entrySet().stream()
                .filter(entry -> !desired.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey(LockOrder.SCOPE))
                .map(Map.Entry::getValue)
                .toList();
        List<Scope> toAdd = desired.stream()
                .filter(scope -> !current.containsKey(scope))
                .toList();

        int revokedCount = 0;
        for (SessionGrant existing : toRevoke) {
            state.revoke(existing.id(), now);
            revokedCount++;
        }

        List<Long> issuedIds = new ArrayList<>(toAdd.size());
        if (!toAdd.isEmpty()) {
            for (Scope scope : toAdd.stream().sorted(LockOrder.SCOPE).toList()) {
                closeLive(author, project, scope, now);
            }
            for (Scope scope : toAdd) {
                issuedIds.add(insertGrant(author, project, scope, now, expiresAt, command.note()));
            }
        }

        if (revokedCount > 0) {
            String revokedTokens = toRevoke.stream().map(g -> g.scope().token()).collect(Collectors.joining(","));
            audit.record(actor, OwnerAction.GRANT_REVOKED, author.value() + "|" + project.value(),
                    "scope=" + revokedTokens + " revoked=" + revokedCount);
        }
        if (!issuedIds.isEmpty()) {
            Plan addedPlan = new Plan(author, project, toAdd, expiresAt, command.note(), now);
            recordIssued(addedPlan, issuedIds, actor);
        }
        return new SyncResult(issuedIds.size(), revokedCount);
    }

    private int revokeAll(RevokeCommand command, OwnerIdentity actor) {
        AuthorId author = Authors.of(command.author());
        boolean hasProject = command.project() != null && !command.project().isBlank();
        boolean hasScope = command.scope() != null && !command.scope().isBlank();
        if (hasScope && !hasProject) {
            throw ValidationException.invalid("project", ValidationReason.REQUIRED_WITH_SCOPE);
        }
        ProjectId project = hasProject ? ProjectId.of(command.project()) : null;
        Scope scope = hasScope ? Tokens.parse("scope", command.scope(), Scope.values()) : null;
        int revoked = state.revokeLive(author, project, scope, clock.instant());
        audit.record(actor, OwnerAction.GRANT_REVOKED,
                author.value() + (project == null ? "" : "|" + project.value()),
                "scope=" + (scope == null ? "all" : scope.token()) + " revoked=" + revoked);
        return revoked;
    }

    /** The live grants of an author now. */
    @Transactional(readOnly = true)
    public List<SessionGrant> live(String author) {
        return state.findLive(Authors.of(author), clock.instant());
    }

    /** The live grants of every author now, ordered by author, project and scope (the list of the admin web). */
    @Transactional(readOnly = true)
    public List<SessionGrant> liveAll() {
        return state.findAllLive(clock.instant());
    }
}
