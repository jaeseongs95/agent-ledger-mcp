package org.pgledger.domain.model;

import java.util.Objects;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.CodeDirection;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.LinkKind;
import org.pgledger.domain.rule.RelationEnd;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.IssueId;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.InsertOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A relation between two distinct issues ({@code issue_relation}). Canonical relations sort {@code a < b};
 * directed relations preserve the caller order {@code (a, b)}.
 */
@Table("issue_relation")
public record IssueRelation(IssueId a, IssueId b, @Column("kind") CodeValue relation,
        @CreatedBy @InsertOnlyProperty ActorId createdBy) implements Link {

    public IssueRelation {
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);
        Objects.requireNonNull(relation);
        if (Objects.equals(a, b)) {
            throw ValidationException.invalid("b", ValidationReason.MUST_DIFFER_FROM, "a");
        }
    }

    public static IssueRelation between(IssueId a, IssueId b, CodeValue relation, CodeDirection direction,
            ActorId createdBy) {
        Pair pair = Pair.of(a, b, direction);
        return new IssueRelation(pair.first(), pair.second(), relation, createdBy);
    }

    public static IssueRelation between(IssueId a, IssueId b, CodeValue relation, CodeDirection direction) {
        return between(a, b, relation, direction, null);
    }

    public IssueId endOf(RelationEnd end) {
        return switch (end) {
            case A -> a;
            case B -> b;
        };
    }

    @Override
    public LinkKind kind() {
        return LinkKind.RELATION;
    }

    @Override
    public HistoryEntity historyEntity() {
        return HistoryEntity.ISSUE_RELATION;
    }

    @Override
    public String historyId() {
        return a.value() + "|" + b.value() + "|" + relation.value();
    }

    /** The ordered ends of an issue relation under a {@link CodeDirection}. */
    public record Pair(IssueId first, IssueId second) {
        public Pair {
            Objects.requireNonNull(first);
            Objects.requireNonNull(second);
            if (Objects.equals(first, second)) {
                throw ValidationException.invalid("b", ValidationReason.MUST_DIFFER_FROM, "a");
            }
        }

        public static Pair of(IssueId x, IssueId y, CodeDirection direction) {
            Objects.requireNonNull(direction);
            if (direction == CodeDirection.CANONICAL && x.value().compareTo(y.value()) > 0) {
                return new Pair(y, x);
            }
            return new Pair(x, y);
        }
    }
}
