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
import org.pgledger.database.repository.LinkRepository;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.CodeCatalog;
import org.pgledger.domain.model.ConditionLink;
import org.pgledger.domain.model.DefectRelation;
import org.pgledger.domain.model.DefectTaskLink;
import org.pgledger.domain.model.Link;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.rule.CodeDirection;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.LinkKind;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TaskId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Links or unlinks defect and task, two defects (a relation of a RELATION_KIND code), a condition and a task, or a
 * condition and a defect. Adding a link that exists and removing one that does not are NOTHING_CHANGES; the row
 * that an unlink removes is kept in the history as {@code before_data}.
 */
@Component
public class LinkOperation extends WriteOperation<LinkCommand, LinkResult> {

    private final WriteGate gate;
    private final Rows rows;
    private final CommonCodeRepository codes;
    private final LinkRepository links;
    private final HistoryWriter history;

    public LinkOperation(WriteGate gate, ReceiptService receipts, Rows rows, CommonCodeRepository codes,
            LinkRepository links, HistoryWriter history) {
        super(gate, receipts, ReceiptOperation.LINK, LinkResult.class);
        this.gate = gate;
        this.rows = rows;
        this.codes = codes;
        this.links = links;
        this.history = history;
    }

    /**
     * The kind of a request: structural, or the code of an existing relation kind; anything else is refused. This
     * reads the relation kind codes, so it is called once per request, by {@link #resolve}; {@link #prepare} runs
     * only after that succeeded and reads them again only to learn the direction of a relation kind
     * ({@link #directionOf}).
     */
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

    /** How the ends of a relation of this kind are kept (the kind exists: {@link #resolve} refused any other). */
    private CodeDirection directionOf(String token) {
        CodeCatalog catalog = codes.catalog(CodeGroup.RELATION_KIND);
        return catalog.directionOf(CodeValue.of(token)).orElseThrow(() -> unknownKind(catalog, token));
    }

    private static ValidationException unknownKind(CodeCatalog catalog, String token) {
        List<String> allowed = new ArrayList<>(List.of(LinkKind.DEFECT_TASK.token(),
                LinkKind.CONDITION_TASK.token(), LinkKind.CONDITION_DEFECT.token()));
        catalog.validCodes().forEach(code -> allowed.add(code.value()));
        return ValidationException.invalid("kind", ValidationReason.UNKNOWN_VALUE, allowed, String.valueOf(token));
    }

    /**
     * What a link or an unlink touches: the project of each end, with the scope that the kind needs (the same scope for both
     * ends, as for a relation and as for a move of a task to another project). The gate holds the targets as a set, so two ends
     * in one project are one check. The unlink is the same operation and passes this lookup too.
     */
    @Override
    protected Lookup resolve(LinkCommand c) {
        return Lookup.afterActor(() -> switch (knownKind(c.kind())) {
            case CONDITION_TASK -> List.of(new Target(rows.condition(ConditionId.of(c.a())).projectId(), Scope.TASK),
                    new Target(rows.task(TaskId.of(c.b())).projectId(), Scope.TASK));
            case CONDITION_DEFECT -> List.of(new Target(rows.condition(ConditionId.of(c.a())).projectId(), Scope.TASK),
                    new Target(rows.defect(DefectId.of(c.b())).projectId(), Scope.TASK));
            case DEFECT_TASK -> List.of(new Target(rows.defect(DefectId.of(c.a())).projectId(), Scope.DEFECT),
                    new Target(rows.task(TaskId.of(c.b())).projectId(), Scope.DEFECT));
            case RELATION -> List.of(new Target(rows.defect(DefectId.of(c.a())).projectId(), Scope.DEFECT),
                    new Target(rows.defect(DefectId.of(c.b())).projectId(), Scope.DEFECT));
        });
    }

    @Override
    protected Prepared<LinkResult> prepare(LinkCommand c) {
        LinkKind kind = LinkKind.ofToken(c.kind());
        boolean remove = c.remove() != null && c.remove();
        // the direction of a relation kind is a property of its code: a directed kind (GIVEN) keeps the ends as the caller
        // gave them, any other keeps them in canonical order; the domain's pair is the one place that knows the order
        CodeDirection direction = kind == LinkKind.RELATION ? directionOf(c.kind()) : CodeDirection.CANONICAL;
        // the two ends as the payload names them, in the order that the relation is stored
        List<String> ends = switch (kind) {
            case CONDITION_TASK -> {
                ConditionId.of(c.a());
                TaskId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case CONDITION_DEFECT -> {
                ConditionId.of(c.a());
                DefectId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case DEFECT_TASK -> {
                DefectId.of(c.a());
                TaskId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case RELATION -> {
                DefectRelation.Pair pair = DefectRelation.Pair.of(DefectId.of(c.a()), DefectId.of(c.b()), direction);
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
        Link link = switch (kind) {
            case DEFECT_TASK -> {
                DefectId defect = DefectId.of(a);
                TaskId task = TaskId.of(b);
                authorizeDefect(author, defect, Scope.DEFECT);
                pinTask(author, task, Scope.DEFECT);
                yield new DefectTaskLink(defect, task, author);
            }
            case CONDITION_TASK -> {
                ConditionId condition = ConditionId.of(a);
                TaskId task = TaskId.of(b);
                pinCondition(author, condition, Scope.TASK);
                pinTask(author, task, Scope.TASK);
                yield ConditionLink.toTask(condition, task, author);
            }
            case CONDITION_DEFECT -> {
                ConditionId condition = ConditionId.of(a);
                DefectId defect = DefectId.of(b);
                pinCondition(author, condition, Scope.TASK);
                authorizeDefect(author, defect, Scope.TASK);
                yield ConditionLink.toDefect(condition, defect, author);
            }
            case RELATION -> {
                DefectId first = DefectId.of(a);
                DefectId second = DefectId.of(b);
                authorizeDefect(author, first, Scope.DEFECT);
                authorizeDefect(author, second, Scope.DEFECT);
                // an unlink finds the row by the same rule that stored it: for a directed kind only the given order
                DefectRelation relation = DefectRelation.between(first, second, CodeValue.of(token), direction, author);
                if (!remove) {
                    codes.catalog(CodeGroup.RELATION_KIND).check("defect_relation.kind", relation.relation(), null);
                }
                yield relation;
            }
        };
        HistoryWriter.Entry entry = HistoryWriter.Entry.of(link.historyEntity(), link.historyId(),
                remove ? HistoryOp.UNLINK : HistoryOp.LINK,
                remove ? ChangeSet.empty().with("kind", token, null) : ChangeSet.empty().with("kind", null, token));
        try {
            if (remove) {
                // the row that the unlink removes is kept as before_data; a link that is not there changes nothing
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

    private void authorizeDefect(AuthorId author, DefectId defect, Scope scope) {
        gate.authorize(author, new Target(rows.defect(defect).projectId(), scope));
    }

    /** Re-reads, authorizes, and pins a task's project inside the transaction so concurrent moves serialize cleanly. */
    private void pinTask(AuthorId author, TaskId task, Scope scope) {
        while (true) {
            ProjectId project = rows.task(task).projectId();
            gate.authorize(author, new Target(project, scope));
            if (links.pinTaskProject(task, project)) {
                return;
            }
        }
    }

    /** Re-reads, authorizes, and pins a release condition's project inside the transaction. */
    private void pinCondition(AuthorId author, ConditionId condition, Scope scope) {
        while (true) {
            ProjectId project = rows.condition(condition).projectId();
            gate.authorize(author, new Target(project, scope));
            if (links.pinConditionProject(condition, project)) {
                return;
            }
        }
    }

    private boolean addLink(Link link) {
        return switch (link) {
            case DefectTaskLink l -> links.add(l);
            case DefectRelation l -> links.add(l);
            case ConditionLink l -> links.add(l);
        };
    }

    private boolean removeLink(Link link) {
        return switch (link) {
            case DefectTaskLink l -> links.remove(l.defectId(), l.taskId());
            case DefectRelation l -> links.remove(l);
            case ConditionLink l -> links.remove(l);
        };
    }

    /** The stored row of the link, as it is now (its creator is whoever linked first). */
    private Optional<Snapshot> stored(Link link) {
        return switch (link) {
            case DefectTaskLink l -> links.tasksOf(l.defectId()).stream()
                    .filter(x -> Objects.equals(x.taskId(), l.taskId())).findFirst().<Snapshot>map(Snapshot.DefectTaskRow::of);
            case DefectRelation l -> links.relationsOf(l.a()).stream()
                    .filter(x -> Objects.equals(x.a(), l.a()) && Objects.equals(x.b(), l.b())
                            && Objects.equals(x.relation(), l.relation()))
                    .findFirst().<Snapshot>map(Snapshot.DefectRelationRow::of);
            case ConditionLink l -> links.linksOf(l.conditionId()).stream()
                    .filter(x -> Objects.equals(x.linkKey(), l.linkKey())).findFirst().<Snapshot>map(Snapshot.ConditionLinkRow::of);
        };
    }
}
