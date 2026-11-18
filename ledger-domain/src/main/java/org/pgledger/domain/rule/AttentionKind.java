package org.pgledger.domain.rule;

import java.util.Arrays;
import java.util.List;

/** The kinds of item that need attention across issues, specs, and tasks. */
public enum AttentionKind {
    OPEN_WITH_DELIVERABLE(HistoryEntity.ISSUE),
    RESOLVED_WITHOUT_DELIVERABLE(HistoryEntity.ISSUE),
    OPEN_NO_OWNER(HistoryEntity.ISSUE),
    SPEC_COMPLETED_NOT_VERIFIED(HistoryEntity.SPEC),
    TASK_UNKNOWN(HistoryEntity.TASK),
    DONE_NOT_VERIFIED(HistoryEntity.TASK);

    private final HistoryEntity entity;

    AttentionKind(HistoryEntity entity) {
        this.entity = entity;
    }

    /** The entity kind this item belongs to: ISSUE, SPEC, or TASK. */
    public HistoryEntity entity() {
        return entity;
    }

    public static List<AttentionKind> of(HistoryEntity entity) {
        return Arrays.stream(values()).filter(kind -> kind.entity == entity).toList();
    }
}
