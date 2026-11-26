// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.read;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.agentledger.database.repository.CommonCodeRepository;
import org.agentledger.database.repository.StatsQueryRepository;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationReason;
import org.agentledger.domain.model.CommonCode;
import org.agentledger.domain.rule.CodeGroup;
import org.agentledger.domain.rule.CriterionState;
import org.agentledger.domain.rule.IssueStatus;
import org.agentledger.domain.rule.SpecStatus;
import org.agentledger.domain.rule.TaskState;
import org.agentledger.domain.value.ProjectId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Summary counts of issues, specifications, tasks, and milestone criteria, optionally bucketed by UTC creation day. */
@Service
@Transactional(readOnly = true)
public class StatsService {

    public record StatsQuery(String project, String bucket) {

        public StatsQuery {
            project = Texts.nullIfBlank(project);
            bucket = Texts.nullIfBlank(bucket);
        }
    }

    public record DayCount(LocalDate day, long issues, long specs, long tasks) {
    }

    public record Stats(long issues, long specs, long tasks, long criteria,
            Map<String, Long> issuesByStatus, Map<String, Long> issuesBySeverity,
            Map<String, Long> specsByStatus, Map<String, Long> tasksByState,
            Map<String, Long> criteriaByState, List<DayCount> createdPerDay) {
    }

    private final StatsQueryRepository queries;
    private final CommonCodeRepository codes;

    public StatsService(StatsQueryRepository queries, CommonCodeRepository codes) {
        this.queries = queries;
        this.codes = codes;
    }

    public Stats stats(StatsQuery q) {
        ProjectId project = q.project() == null ? null : ProjectId.of(q.project());
        boolean withDays = false;
        if (q.bucket() != null) {
            if (!"day".equals(q.bucket())) {
                throw ValidationException.invalid("bucket", ValidationReason.UNKNOWN_VALUE, List.of("day"), q.bucket());
            }
            withDays = true;
        }
        Map<String, Long> issuesByStatus = fillEnum(IssueStatus.values(), queries.issuesByStatus(project));
        Map<String, Long> issuesBySeverity = fillSeverities(queries.issuesBySeverity(project));
        Map<String, Long> specsByStatus = fillEnum(SpecStatus.values(), queries.specsByStatus(project));
        Map<String, Long> tasksByState = fillEnum(TaskState.values(), queries.tasksByState(project));
        Map<String, Long> criteriaByState = fillEnum(CriterionState.values(), queries.criteriaByState(project));

        List<DayCount> perDay = withDays ? buildDayCounts(project) : List.of();
        return new Stats(
                sum(issuesByStatus),
                sum(specsByStatus),
                sum(tasksByState),
                sum(criteriaByState),
                issuesByStatus,
                issuesBySeverity,
                specsByStatus,
                tasksByState,
                criteriaByState,
                perDay);
    }

    private List<DayCount> buildDayCounts(ProjectId project) {
        Map<LocalDate, Long> issues = queries.issuesCreatedPerDay(project);
        Map<LocalDate, Long> specs = queries.specsCreatedPerDay(project);
        Map<LocalDate, Long> tasks = queries.tasksCreatedPerDay(project);
        Set<LocalDate> allDays = new TreeSet<>();
        allDays.addAll(issues.keySet());
        allDays.addAll(specs.keySet());
        allDays.addAll(tasks.keySet());
        List<DayCount> out = new ArrayList<>(allDays.size());
        for (LocalDate day : allDays) {
            out.add(new DayCount(
                    day,
                    issues.getOrDefault(day, 0L),
                    specs.getOrDefault(day, 0L),
                    tasks.getOrDefault(day, 0L)));
        }
        return out;
    }

    private Map<String, Long> fillSeverities(Map<String, Long> actual) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (CommonCode code : codes.catalog(CodeGroup.ISSUE_SEVERITY).entries()) {
            out.put(code.code().value(), actual.getOrDefault(code.code().value(), 0L));
        }
        for (Map.Entry<String, Long> e : actual.entrySet()) {
            out.putIfAbsent(e.getKey(), e.getValue());
        }
        return out;
    }

    private static <E extends Enum<E>> Map<String, Long> fillEnum(E[] values, Map<String, Long> actual) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (E v : values) {
            out.put(v.name(), actual.getOrDefault(v.name(), 0L));
        }
        return out;
    }

    private static long sum(Map<String, Long> map) {
        long total = 0L;
        for (long count : map.values()) {
            total += count;
        }
        return total;
    }
}
