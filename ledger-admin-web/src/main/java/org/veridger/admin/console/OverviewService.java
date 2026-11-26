// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.veridger.application.read.AttentionService;
import org.veridger.application.read.AttentionService.AttentionQuery;
import org.veridger.application.read.CatalogService.ProjectView;
import org.veridger.application.read.Page;
import org.veridger.application.read.StatsService;
import org.veridger.application.read.StatsService.Stats;
import org.veridger.application.read.StatsService.StatsQuery;
import org.veridger.domain.rule.AttentionKind;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** What the overview shows (the page and the JSON of {@code /api/overview}): MCP status, counts, attention. */
@Component
public class OverviewService {

    private static final int SHOWN = 25;
    private static final int COUNTED = 1000;

    private static final AttentionKind[] PRIORITY_ORDER = {
        AttentionKind.SPEC_COMPLETED_NOT_VERIFIED,
        AttentionKind.DONE_NOT_VERIFIED,
        AttentionKind.OPEN_WITH_DELIVERABLE,
        AttentionKind.RESOLVED_WITHOUT_DELIVERABLE,
        AttentionKind.OPEN_NO_OWNER,
        AttentionKind.TASK_UNKNOWN
    };

    private static final AttentionKind[] HYGIENE_KINDS = {
        AttentionKind.OPEN_WITH_DELIVERABLE,
        AttentionKind.RESOLVED_WITHOUT_DELIVERABLE,
        AttentionKind.OPEN_NO_OWNER,
        AttentionKind.TASK_UNKNOWN
    };

    /** The number of items of one attention kind; {@code atLeast} when the count stopped at the largest page. */
    public record AttentionCount(AttentionKind kind, int count, boolean atLeast) {
    }

    /** Bounded sum of hygiene attention counts. */
    public record BoundedTotal(int count, boolean atLeast) {
    }

    public record Overview(McpStatus mcp, List<ProjectView> projects, String project, Stats stats,
            List<AttentionCount> attentionCounts, List<AttentionService.Item> attention, boolean moreAttention) {
    }

    /** Enriched cockpit model for the HTML overview page while keeping {@link Overview} JSON untouched. */
    public record ConsoleOverview(Overview base, List<AttentionService.HydratedItem> attentionItems,
            List<AttentionCount> activeAttentionCounts, long needsVerification, long specsCompleted,
            long tasksCompleted, long criteriaCompleted, BoundedTotal hygieneAnomalies, long activeWork,
            long issuesOpen, long specsActive, long tasksActive, long criteriaActive, long blockedDisputed,
            long issuesDisputed, long specsBlocked, long tasksBlocked, long criteriaNotMet, long totalItems,
            Map<String, Long> nonZeroIssuesByStatus, Map<String, Long> nonZeroIssuesBySeverity,
            Map<String, Long> nonZeroSpecsByStatus, Map<String, Long> nonZeroTasksByState,
            Map<String, Long> nonZeroCriteriaByState) {
    }

    private final McpHealthClient mcp;
    private final Choices choices;
    private final StatsService stats;
    private final AttentionService attention;

    public OverviewService(McpHealthClient mcp, Choices choices, StatsService stats, AttentionService attention) {
        this.mcp = mcp;
        this.choices = choices;
        this.stats = stats;
        this.attention = attention;
    }

    /** The overview of a project, or of the whole ledger when {@code project} is blank. */
    public Overview overview(String project) {
        String scope = StringUtils.hasText(project) ? project : null;
        Map<AttentionKind, AttentionService.BoundedCount> bounded = attention.counts(scope, COUNTED);
        List<AttentionCount> counts = new ArrayList<>(AttentionKind.values().length);
        for (AttentionKind kind : AttentionKind.values()) {
            AttentionService.BoundedCount bc = bounded.get(kind);
            counts.add(bc == null ? new AttentionCount(kind, 0, false)
                    : new AttentionCount(kind, bc.count(), bc.atLeast()));
        }
        Page<AttentionService.Item> first = attention.attention(new AttentionQuery(scope, null, SHOWN, null));
        return new Overview(mcp.status(), choices.project(null).projects(), scope,
                stats.stats(new StatsQuery(scope, null)), counts, first.items(), first.truncated());
    }

    /** Enriched cockpit model for {@code GET /}, hydrating the shown attention rows and computing live action tiles. */
    public ConsoleOverview consoleOverview(String project) {
        Overview base = overview(project);
        Stats s = base.stats();
        Map<AttentionKind, AttentionCount> byKind = new EnumMap<>(AttentionKind.class);
        for (AttentionCount c : base.attentionCounts()) {
            byKind.put(c.kind(), c);
        }
        List<AttentionCount> activeAttention = new ArrayList<>();
        for (AttentionKind kind : PRIORITY_ORDER) {
            AttentionCount c = byKind.get(kind);
            if (c != null && c.count() > 0) {
                activeAttention.add(c);
            }
        }
        int hygieneCount = 0;
        boolean hygieneAtLeast = false;
        for (AttentionKind kind : HYGIENE_KINDS) {
            AttentionCount c = byKind.get(kind);
            if (c != null) {
                hygieneCount += c.count();
                if (c.atLeast()) {
                    hygieneAtLeast = true;
                }
            }
        }
        long specsCompleted = countOf(s.specsByStatus(), "COMPLETED");
        long tasksCompleted = countOf(s.tasksByState(), "COMPLETED");
        long criteriaCompleted = countOf(s.criteriaByState(), "COMPLETED");
        long needsVerification = specsCompleted + tasksCompleted + criteriaCompleted;
        long issuesOpen = countOf(s.issuesByStatus(), "OPEN");
        long specsActive = countOf(s.specsByStatus(), "ACTIVE");
        long tasksActive = countOf(s.tasksByState(), "ACTIVE");
        long criteriaActive = countOf(s.criteriaByState(), "ACTIVE");
        long activeWork = issuesOpen + specsActive + tasksActive + criteriaActive;
        long issuesDisputed = countOf(s.issuesByStatus(), "DISPUTED");
        long specsBlocked = countOf(s.specsByStatus(), "BLOCKED");
        long tasksBlocked = countOf(s.tasksByState(), "BLOCKED");
        long criteriaNotMet = countOf(s.criteriaByState(), "NOT_MET");
        long blockedDisputed = issuesDisputed + specsBlocked + tasksBlocked + criteriaNotMet;
        long totalItems = s.issues() + s.specs() + s.tasks() + s.criteria();
        List<AttentionService.HydratedItem> hydrated = attention.hydrate(base.attention());
        return new ConsoleOverview(base, hydrated, activeAttention, needsVerification, specsCompleted, tasksCompleted,
                criteriaCompleted, new BoundedTotal(hygieneCount, hygieneAtLeast), activeWork, issuesOpen, specsActive,
                tasksActive, criteriaActive, blockedDisputed, issuesDisputed, specsBlocked, tasksBlocked,
                criteriaNotMet, totalItems, nonZero(s.issuesByStatus()), nonZero(s.issuesBySeverity()),
                nonZero(s.specsByStatus()), nonZero(s.tasksByState()), nonZero(s.criteriaByState()));
    }

    private static long countOf(Map<String, Long> map, String key) {
        return map == null ? 0L : map.getOrDefault(key, 0L);
    }

    private static Map<String, Long> nonZero(Map<String, Long> map) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (map != null) {
            for (Map.Entry<String, Long> e : map.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0L) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }
}
