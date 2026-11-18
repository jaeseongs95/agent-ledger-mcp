// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.migrate1x.service;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.pgledger.database.repository.Counter;
import org.pgledger.domain.value.IssueId;
import org.pgledger.migrate1x.database.LegacyRow;
import org.pgledger.migrate1x.database.LegacyTable;

/**
 * The highest numbers the legacy ledger has used, which the new ledger continues from: per project the number of the
 * last issue ({@code <CODE>-D-NNNN} or {@code <CODE>-I-NNNN}) and of the last task ({@code <CODE>-T-NNNN}; a task with
 * another id counts for nothing), and the highest id of each surrogate key sequence (history, deliverable, case, grant).
 * The next number issued is one above, so it collides with nothing.
 */
final class ImportCounters {

    /**
     * The most digits of the number of a task id that counts: 18 digits always fit a {@code long}, as for the ids the
     * server issues. A task id with a longer number is a free id like any other and counts for nothing.
     */
    private static final int MAX_DIGITS = 18;

    private final Map<String, String> codeByProject = new HashMap<>();
    private final Map<String, Pattern> taskIdByProject = new HashMap<>();
    private final Map<String, Long> issueNumbers = new HashMap<>();
    private final Map<String, Long> taskNumbers = new HashMap<>();
    private final Map<Counter, Long> surrogate = new EnumMap<>(Counter.class);

    /** Takes in one legacy row: the tables whose rows fix a counter say what they used, the others change nothing. */
    void collect(LegacyTable table, LegacyRow row) {
        switch (table) {
            case PROJECT -> project(row.text("id"), row.text("code"));
            case TASK -> task(row.text("project_id"), row.text("id"));
            case DEFECT -> issue(row.text("project_id"), row.text("id"));
            case DEFECT_FIX -> surrogate(Counter.DELIVERABLE, row.number("id"));
            case DEFECT_CASE -> surrogate(Counter.ISSUE_CASE, row.number("id"));
            case SESSION_GRANT -> surrogate(Counter.SESSION_GRANT, row.number("id"));
            case HISTORY -> surrogate(Counter.HISTORY, row.number("id"));
            case COMMON_CODE, DEFECT_TASK, DEFECT_RELATION, RELEASE_CONDITION, CONDITION_LINK -> {
                // no counter depends on these tables
            }
        }
    }

    private void project(String projectId, String code) {
        if (projectId != null && code != null) {
            codeByProject.put(projectId, code);
            taskIdByProject.put(projectId,
                    Pattern.compile(Pattern.quote(code) + "-T-([0-9]{4," + MAX_DIGITS + "})"));
        }
    }

    private void issue(String projectId, String issueId) {
        String code = codeByProject.get(projectId);
        IssueId.parse(issueId).filter(id -> Objects.equals(code, id.projectCode().value()))
                .ifPresent(id -> issueNumbers.merge(projectId, id.number(), Math::max));
    }

    private void task(String projectId, String taskId) {
        Pattern pattern = taskIdByProject.get(projectId);
        if (pattern != null && taskId != null) {
            Matcher matcher = pattern.matcher(taskId);
            if (matcher.matches()) {
                taskNumbers.merge(projectId, Long.parseLong(matcher.group(1)), Math::max);
            }
        }
    }

    private void surrogate(Counter counter, Long id) {
        if (id != null) {
            surrogate.merge(counter, id, Math::max);
        }
    }

    long issueNumber(String projectId) {
        return issueNumbers.getOrDefault(projectId, 0L);
    }

    long taskNumber(String projectId) {
        return taskNumbers.getOrDefault(projectId, 0L);
    }

    long highest(Counter counter) {
        return surrogate.getOrDefault(counter, 0L);
    }
}
