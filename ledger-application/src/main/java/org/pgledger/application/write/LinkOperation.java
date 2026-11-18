// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.write;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.application.gate.Lookup;
import org.pgledger.application.gate.Target;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.application.write.WriteResult.LinkResult;
import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.LinkRepository;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.CodeCatalog;
import org.pgledger.domain.model.CriterionLink;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.IssueRelation;
import org.pgledger.domain.model.IssueSpecLink;
import org.pgledger.domain.model.IssueTaskLink;
import org.pgledger.domain.model.Link;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.model.SpecTaskLink;
import org.pgledger.domain.rule.CodeDirection;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.LinkKind;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.CriterionId;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.SpecId;
import org.pgledger.domain.value.TaskId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Links or unlinks issues, specifications, tasks, milestone criteria, and issue-to-issue relations.
 * Adding an existing link or removing an absent one fails with {@code NOTHING_CHANGES}; unlinking records the removed
 * row in {@code before_data}.
 */
@Component
public class LinkOperation extends WriteOperation<LinkCommand, LinkResult> {

    private final WriteGate gate;
    private final Rows rows;
    private final CommonCodeRepository codes;
    private final LinkRepository links;
    private final HistoryRepository historyRows;
    private final HistoryWriter history;

    public LinkOperation(WriteGate gate, ReceiptService receipts, Rows rows, CommonCodeRepository codes,
            LinkRepository links, HistoryRepository historyRows, HistoryWriter history) {
        super(gate, receipts, ReceiptOperation.LINK, LinkResult.class);
        this.gate = gate;
        this.rows = rows;
        this.codes = codes;
        this.links = links;
        this.historyRows = historyRows;
        this.history = history;
    }

    private LinkKind knownKind(String token) {
        LinkKind kind = LinkKind.ofToken(token);
        if (kind != LinkKind.RELATION) {
            return kind;
        }
        CodeCatalog catalog = codes.catalog(CodeGroup.RELATION_KIND);
        if (token != null && catalog.entries().stream().anyMatch(e -> Objects.equals(e.code().value(), token))) {
            return kind;
        }
        throw unknownKind(catalog, token);
    }

    private CodeDirection directionOf(String token) {
        CodeCatalog catalog = codes.catalog(CodeGroup.RELATION_KIND);
        return catalog.directionOf(CodeValue.of(token)).orElseThrow(() -> unknownKind(catalog, token));
    }

    private static ValidationException unknownKind(CodeCatalog catalog, String token) {
        List<String> allowed = new ArrayList<>(List.of(
                LinkKind.ISSUE_TASK.token(),
                LinkKind.ISSUE_SPEC.token(),
                LinkKind.SPEC_TASK.token(),
                LinkKind.CRITERION_TASK.token(),
                LinkKind.CRITERION_ISSUE.token(),
                LinkKind.CRITERION_SPEC.token()));
        catalog.validCodes().forEach(code -> allowed.add(code.value()));
        return ValidationException.invalid("kind", ValidationReason.UNKNOWN_VALUE, allowed, String.valueOf(token));
    }

    @Override
    protected Lookup resolve(LinkCommand c) {
        return Lookup.afterActor(() -> switch (knownKind(c.kind())) {
            case ISSUE_TASK -> List.of(
                    new Target(rows.issue(IssueId.of(c.a())).projectId(), Scope.ISSUE),
                    new Target(rows.task(TaskId.of(c.b())).projectId(), Scope.ISSUE));
            case ISSUE_SPEC -> List.of(
                    new Target(rows.issue(IssueId.of(c.a())).projectId(), Scope.SPEC),
                    new Target(rows.spec(SpecId.of(c.b())).projectId(), Scope.SPEC));
            case SPEC_TASK -> List.of(
                    new Target(rows.spec(SpecId.of(c.a())).projectId(), Scope.SPEC),
                    new Target(rows.task(TaskId.of(c.b())).projectId(), Scope.SPEC));
            case CRITERION_TASK -> List.of(
                    new Target(rows.criterion(CriterionId.of(c.a())).projectId(), Scope.TASK),
                    new Target(rows.task(TaskId.of(c.b())).projectId(), Scope.TASK));
            case CRITERION_ISSUE -> List.of(
                    new Target(rows.criterion(CriterionId.of(c.a())).projectId(), Scope.TASK),
                    new Target(rows.issue(IssueId.of(c.b())).projectId(), Scope.TASK));
            case CRITERION_SPEC -> List.of(
                    new Target(rows.criterion(CriterionId.of(c.a())).projectId(), Scope.TASK),
                    new Target(rows.spec(SpecId.of(c.b())).projectId(), Scope.TASK));
            case RELATION -> List.of(
                    new Target(rows.issue(IssueId.of(c.a())).projectId(), Scope.ISSUE),
                    new Target(rows.issue(IssueId.of(c.b())).projectId(), Scope.ISSUE));
        });
    }

    @Override
    protected Prepared<LinkResult> prepare(LinkCommand c) {
        LinkKind kind = LinkKind.ofToken(c.kind());
        boolean remove = c.remove() != null && c.remove();
        CodeDirection direction = kind == LinkKind.RELATION ? directionOf(c.kind()) : CodeDirection.CANONICAL;
        List<String> ends = switch (kind) {
            case ISSUE_TASK -> {
                IssueId.of(c.a());
                TaskId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case ISSUE_SPEC -> {
                IssueId.of(c.a());
                SpecId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case SPEC_TASK -> {
                SpecId.of(c.a());
                TaskId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case CRITERION_TASK -> {
                CriterionId.of(c.a());
                TaskId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case CRITERION_ISSUE -> {
                CriterionId.of(c.a());
                IssueId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case CRITERION_SPEC -> {
                CriterionId.of(c.a());
                SpecId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case RELATION -> {
                IssueRelation.Pair pair = IssueRelation.Pair.of(IssueId.of(c.a()), IssueId.of(c.b()), direction);
                yield List.of(pair.first().value(), pair.second().value());
            }
        };
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty().text("kind", c.kind())
                .text("a", ends.get(0)).text("b", ends.get(1)).text("remove", Boolean.toString(remove));
        return new Prepared<>(payload, ctx -> execute(ctx, kind, c.kind(), c.a(), c.b(), direction, remove));
    }

    private LinkResult execute(WriteContext ctx, LinkKind kind, String token, String a, String b,
            CodeDirection direction, boolean remove) {
        AuthorId author = ctx.author();
        // Canonical entity pin order: spec -> task -> criterion (issues cannot move projects).
        Link link = switch (kind) {
            case ISSUE_TASK -> {
                IssueId issue = IssueId.of(a);
                TaskId task = TaskId.of(b);
                authorizeIssue(author, issue, Scope.ISSUE);
                pinTask(author, task, Scope.ISSUE);
                yield new IssueTaskLink(issue, task, author);
            }
            case ISSUE_SPEC -> {
                IssueId issue = IssueId.of(a);
                SpecId spec = SpecId.of(b);
                authorizeIssue(author, issue, Scope.SPEC);
                pinSpec(author, spec, Scope.SPEC);
                yield new IssueSpecLink(issue, spec, author);
            }
            case SPEC_TASK -> {
                SpecId spec = SpecId.of(a);
                TaskId task = TaskId.of(b);
                pinSpec(author, spec, Scope.SPEC);
                pinTask(author, task, Scope.SPEC);
                yield new SpecTaskLink(spec, task, author);
            }
            case CRITERION_TASK -> {
                CriterionId criterion = CriterionId.of(a);
                TaskId task = TaskId.of(b);
                pinTask(author, task, Scope.TASK);
                pinCriterion(author, criterion, Scope.TASK);
                yield CriterionLink.toTask(criterion, task, author);
            }
            case CRITERION_ISSUE -> {
                CriterionId criterion = CriterionId.of(a);
                IssueId issue = IssueId.of(b);
                authorizeIssue(author, issue, Scope.TASK);
                pinCriterion(author, criterion, Scope.TASK);
                yield CriterionLink.toIssue(criterion, issue, author);
            }
            case CRITERION_SPEC -> {
                CriterionId criterion = CriterionId.of(a);
                SpecId spec = SpecId.of(b);
                pinSpec(author, spec, Scope.TASK);
                pinCriterion(author, criterion, Scope.TASK);
                yield CriterionLink.toSpec(criterion, spec, author);
            }
            case RELATION -> {
                IssueId first = IssueId.of(a);
                IssueId second = IssueId.of(b);
                authorizeIssue(author, first, Scope.ISSUE);
                authorizeIssue(author, second, Scope.ISSUE);
                IssueRelation relation = IssueRelation.between(first, second, CodeValue.of(token), direction, author);
                if (!remove) {
                    codes.catalog(CodeGroup.RELATION_KIND).check("issue_relation.kind", relation.relation(), null);
                }
                yield relation;
            }
        };
        HistoryWriter.Entry entry = HistoryWriter.Entry.of(link.historyEntity(), link.historyId(),
                remove ? HistoryOp.UNLINK : HistoryOp.LINK,
                remove ? ChangeSet.empty().with("kind", token, null) : ChangeSet.empty().with("kind", null, token));
        try {
            if (remove) {
                Snapshot stored = stored(link).orElseThrow(
                        () -> new TransitionException(TransitionCode.NOTHING_CHANGES, List.of()));
                if (!removeLink(link)) {
                    throw new TransitionException(TransitionCode.NOTHING_CHANGES, List.of());
                }
                entry = entry.withBefore(stored);
            } else if (!addLink(link)) {
                throw new TransitionException(TransitionCode.NOTHING_CHANGES, List.of());
            }
        } catch (DuplicateKeyException e) {
            throw new TransitionException(TransitionCode.NOTHING_CHANGES, List.of(), e);
        }
        long historyId = history.append(ctx, entry);
        return new LinkResult(WriteStatus.COMMITTED, link.historyEntity().token(), link.historyId(), remove,
                historyId, author.value(), ctx.login());
    }

    private void authorizeIssue(AuthorId author, IssueId issue, Scope scope) {
        gate.authorize(author, new Target(rows.issue(issue).projectId(), scope));
    }

    private void pinSpec(AuthorId author, SpecId spec, Scope scope) {
        while (true) {
            ProjectId project = rows.spec(spec).projectId();
            gate.authorize(author, new Target(project, scope));
            if (links.pinSpecProject(spec, project)) {
                return;
            }
        }
    }

    private void pinTask(AuthorId author, TaskId task, Scope scope) {
        while (true) {
            ProjectId project = rows.task(task).projectId();
            gate.authorize(author, new Target(project, scope));
            if (links.pinTaskProject(task, project)) {
                return;
            }
        }
    }

    private void pinCriterion(AuthorId author, CriterionId criterion, Scope scope) {
        while (true) {
            ProjectId project = rows.criterion(criterion).projectId();
            gate.authorize(author, new Target(project, scope));
            if (links.pinCriterionProject(criterion, project)) {
                return;
            }
        }
    }

    private boolean addLink(Link link) {
        return switch (link) {
            case IssueTaskLink l -> links.add(l);
            case IssueSpecLink l -> links.add(l);
            case SpecTaskLink l -> links.add(l);
            case IssueRelation l -> links.add(l);
            case CriterionLink l -> links.add(l);
        };
    }

    private boolean removeLink(Link link) {
        return switch (link) {
            case IssueTaskLink l -> links.remove(l.issueId(), l.taskId());
            case IssueSpecLink l -> links.remove(l.issueId(), l.specId());
            case SpecTaskLink l -> links.remove(l.specId(), l.taskId());
            case IssueRelation l -> links.remove(l);
            case CriterionLink l -> links.remove(l);
        };
    }

    private Optional<Snapshot> stored(Link link) {
        return switch (link) {
            case IssueTaskLink l -> links.tasksOf(l.issueId()).stream()
                    .filter(x -> Objects.equals(x.taskId(), l.taskId()))
                    .findFirst().<Snapshot>map(Snapshot.IssueTaskRow::of);
            case IssueSpecLink l -> links.specsOfIssue(l.issueId()).contains(l.specId().value())
                    ? Optional.of(new Snapshot.IssueSpecRow(l.issueId().value(), l.specId().value(),
                            latestCreator(HistoryEntity.ISSUE_SPEC, l.historyId())))
                    : Optional.empty();
            case SpecTaskLink l -> links.tasksOfSpec(l.specId()).contains(l.taskId().value())
                    ? Optional.of(new Snapshot.SpecTaskRow(l.specId().value(), l.taskId().value(),
                            latestCreator(HistoryEntity.SPEC_TASK, l.historyId())))
                    : Optional.empty();
            case IssueRelation l -> links.relationsOf(l.a()).stream()
                    .filter(x -> Objects.equals(x.a(), l.a()) && Objects.equals(x.b(), l.b())
                            && Objects.equals(x.relation(), l.relation()))
                    .findFirst().<Snapshot>map(Snapshot.IssueRelationRow::of);
            case CriterionLink l -> links.linksOf(l.criterionId()).stream()
                    .filter(x -> Objects.equals(x.linkKey(), l.linkKey()))
                    .findFirst().<Snapshot>map(Snapshot.CriterionLinkRow::of);
        };
    }

    private String latestCreator(HistoryEntity entity, String historyId) {
        List<HistoryEntry> entries = historyRows.findByEntityAndEntityIdOrderById(entity, historyId);
        for (int i = entries.size() - 1; i >= 0; i--) {
            HistoryEntry entry = entries.get(i);
            if (entry.op() == HistoryOp.LINK || entry.op() == HistoryOp.IMPORT) {
                return entry.authorId().value();
            }
        }
        return AuthorId.OWNER.value();
    }
}
