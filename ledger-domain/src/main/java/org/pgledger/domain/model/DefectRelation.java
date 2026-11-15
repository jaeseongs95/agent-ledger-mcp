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
import org.pgledger.domain.value.DefectId;

/**
 * A relation between two different defects, as it is stored: {@code a} and {@code b} are the two ends in the order that
 * the direction of the relation kind keeps ({@link CodeDirection}): a directed kind (GIVEN, for example {@code caused_by}:
 * a is caused by b) keeps the order of the caller, a kind without direction (CANONICAL) keeps the canonical order
 * ({@code a} sorts before {@code b}), so (b, a) and (a, b) are the same relation. The relation kind is a RELATION_KIND
 * common code. {@link #between} makes a relation from the two defects as the caller names them; the constructor takes the
 * stored ends as they are (a row that is read back).
 */
public record DefectRelation(DefectId a, DefectId b, CodeValue relation, ActorId createdBy) implements Link {

    /** Two different defects in the order a relation keeps them: {@code first} is the end {@code a}, {@code second} is {@code b}. */
    public record Pair(DefectId first, DefectId second) {

        /**
         * The two defects in the order that {@code direction} keeps: the one rule of the order, for the relation and for a
         * caller that looks a relation up by its ends (an unlink finds the row with the same rule that stored it).
         *
         * @param x the defect the caller named first
         * @param y the defect the caller named second
         * @throws ValidationException when both are the same defect
         */
        public static Pair of(DefectId x, DefectId y, CodeDirection direction) {
            int order = x.value().compareTo(y.value());
            if (order == 0) {
                throw ValidationException.invalid("b", ValidationReason.MUST_DIFFER_FROM, "a");
            }
            return switch (direction) {
                case GIVEN -> new Pair(x, y);
                case CANONICAL -> order < 0 ? new Pair(x, y) : new Pair(y, x);
            };
        }
    }

    public DefectRelation {
        if (Objects.equals(a, b)) {
            throw ValidationException.invalid("b", ValidationReason.MUST_DIFFER_FROM, "a");
        }
    }

    /**
     * The relation of two defects named by a caller, with the ends in the order that the direction of the kind keeps.
     *
     * @throws ValidationException when both are the same defect
     */
    public static DefectRelation between(DefectId x, DefectId y, CodeValue relation, CodeDirection direction,
            ActorId createdBy) {
        Pair ends = Pair.of(x, y, direction);
        return new DefectRelation(ends.first(), ends.second(), relation, createdBy);
    }

    /** Which end of this relation the defect is: the end {@code a} when it is {@code a}, else the end {@code b}. */
    public RelationEnd endOf(DefectId defect) {
        return Objects.equals(a, defect) ? RelationEnd.A : RelationEnd.B;
    }

    @Override
    public LinkKind kind() {
        return LinkKind.RELATION;
    }

    @Override
    public HistoryEntity historyEntity() {
        return HistoryEntity.DEFECT_RELATION;
    }

    @Override
    public String historyId() {
        return a.value() + "|" + b.value() + "|" + relation.value();
    }
}
