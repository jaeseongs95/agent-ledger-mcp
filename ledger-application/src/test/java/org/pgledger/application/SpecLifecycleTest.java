// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.DetailService.SpecDetail;
import org.pgledger.application.read.GetService;
import org.pgledger.application.read.ListService;
import org.pgledger.application.read.ListService.SpecsQuery;
import org.pgledger.application.read.SearchService;
import org.pgledger.application.read.SearchService.SearchQuery;
import org.pgledger.application.read.StatsService;
import org.pgledger.application.read.StatsService.StatsQuery;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.RecordDeliverableCommand;
import org.pgledger.application.write.RecordDeliverableService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult.RecordDeliverableResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.ValidationCode;

/**
 * Universal specification ({@code spec}) lifecycle: creation, parent hierarchy, deliverable completion, independent
 * reviewer verification, verified-edit lock (F6), cross-project issued-ID lock (D-0049), links, list/detail/search/stats.
 */
@LedgerServiceTest
class SpecLifecycleTest {

    @Test
    @DisplayName("SPEC-01: create spec, child spec, link to task & issue & criterion, complete via deliverable, verify by independent reviewer, block verified edit (F6)")
    void spec01_endToEndLifecycleAndGuards(Scenario s) {
        SaveService save = s.bean(SaveService.class);
        TransitionService transition = s.bean(TransitionService.class);
        ReviewService review = s.bean(ReviewService.class);
        RecordDeliverableService deliverables = s.bean(RecordDeliverableService.class);
        LinkService link = s.bean(LinkService.class);

        SaveResult root = s.newSpec("Storyboarding Chapter 1");
        assertThat(root.id()).isEqualTo(s.specId(1));
        assertThat(s.spec(root.id()).status().name()).isEqualTo("PLANNED");

        var childFields = s.specFields("Scene 1A Shot List");
        childFields.put("parent_id", root.id());
        childFields.put("priority", "HIGH");
        SaveResult child = save.save(Scenario.AUTHOR, s.create("spec", null, childFields));

        // Self-parent is refused
        Expect.failure(() -> save.save(Scenario.AUTHOR, s.edit("spec", root.id(), 1,
                Map.of("parent_id", root.id()))), ValidationCode.VALIDATION_FAILED);

        SaveResult task = s.newTask("Draft scene script");
        SaveResult issue = s.newIssue("Continuity gap in frame 12");
        SaveResult crit = s.newCriterion("1", "M1", "S1");

        link.link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "spec_task", child.id(), task.id(),
                Scenario.REASON, null));
        link.link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "issue_spec", issue.id(), child.id(),
                Scenario.REASON, null));
        link.link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "criterion_spec", crit.id(), child.id(),
                Scenario.REASON, null));

        transition.transition(Scenario.AUTHOR, s.move("spec", child.id(), 1, "ACTIVE", null));

        RecordDeliverableResult recorded = deliverables.recordDeliverable(Scenario.AUTHOR,
                new RecordDeliverableCommand(Scenario.uuid(), "spec", child.id(), "document", "docs/scene-1a-v1.pdf",
                        Scenario.REASON, "drive://films/scene1a", "Final shot list PDF", "review-check-1", "passed",
                        null, true));
        assertThat(recorded.targetState()).isEqualTo("COMPLETED");

        // Author who created & delivered cannot self-verify even if granted verify on reviewer channel
        Expect.failure(() -> review.review(Scenario.AUTHOR, s.move("spec", child.id(), 3, "VERIFIED",
                Scenario.EVIDENCE)), TransitionCode.SAME_SESSION);

        // Amend deliverable by a third actor -> that amender is also blocked from self-verifying (D-0029)
        String amender = s.actorWith("deliverable", "verify");
        deliverables.recordDeliverable(amender, new RecordDeliverableCommand(Scenario.uuid(), "spec", child.id(),
                "document", "docs/scene-1a-v1.pdf", Scenario.REASON, null, "Updated shot list PDF", "review-check-2",
                "passed", null, false));
        Expect.failure(() -> review.review(amender, s.move("spec", child.id(), 4, "VERIFIED", Scenario.EVIDENCE)),
                TransitionCode.SAME_SESSION);

        // Independent reviewer verifies cleanly
        review.review(Scenario.REVIEWER, s.move("spec", child.id(), 4, "VERIFIED", Scenario.EVIDENCE));
        assertThat(s.spec(child.id()).status().name()).isEqualTo("VERIFIED");

        // F6: verified spec cannot be edited until reopened by reviewer
        Expect.failure(() -> save.save(Scenario.AUTHOR, s.edit("spec", child.id(), 5,
                Map.of("title", "Unauthorized edit"))), TransitionCode.VERIFIED_ENTITY);

        // Read surfaces: detail, get, list, search, stats
        SpecDetail detail = s.bean(DetailService.class).spec(child.id());
        assertThat(detail.parentId()).isEqualTo(root.id());
        assertThat(detail.tasks()).containsExactly(task.id());
        assertThat(detail.issues()).containsExactly(issue.id());
        assertThat(detail.criteria()).containsExactly(crit.id());
        assertThat(detail.deliverables()).hasSize(1);
        assertThat(s.bean(DetailService.class).spec(root.id()).childSpecs()).containsExactly(child.id());

        assertThat(s.bean(GetService.class).get(List.of(child.id())).items())
                .extracting(GetService.Item::entity).containsExactly("spec");
        assertThat(s.bean(ListService.class).specs(new SpecsQuery(s.projectId(), "VERIFIED", root.id(), "HIGH", null,
                null, null, null)).items()).extracting(ListService.SpecItem::id).containsExactly(child.id());
        assertThat(s.bean(SearchService.class).search(new SearchQuery(s.projectId(), List.of("spec"),
                List.of("Shot List"), null, null, null, null, null, null)).items()).hasSize(1);
        assertThat(s.bean(StatsService.class).stats(new StatsQuery(s.projectId(), null)).specs()).isEqualTo(2);
    }

    @Test
    @DisplayName("SPEC-02 (D-0049): issued spec IDs of another project prefix are refused on create and issued spec IDs cannot move projects")
    void spec02_issuedIdPrefixAndMoveGuards(Scenario s) {
        SaveService save = s.bean(SaveService.class);
        String other = s.otherProject();
        s.grant(Scenario.AUTHOR, other, "spec");

        Expect.failure(() -> save.save(Scenario.AUTHOR, s.create("spec", "OTHER-S-0001", s.specFields("bad"))),
                ValidationCode.ID_PREFIX_MISMATCH);
        Expect.failure(() -> save.save(Scenario.AUTHOR, s.create("spec", "OTHER-S-0000", s.specFields("zero"))),
                ValidationCode.ID_PREFIX_MISMATCH);

        SaveResult issued = s.newSpec("issued");
        Expect.failure(() -> save.save(Scenario.AUTHOR, s.edit("spec", issued.id(), 1, Map.of("project_id", other))),
                TransitionCode.PROJECT_MOVE_FORBIDDEN);

        SaveResult freeId = save.save(Scenario.AUTHOR, s.create("spec", s.code() + ":CUSTOM-SPEC",
                s.specFields("movable")));
        assertThat(save.save(Scenario.AUTHOR, s.edit("spec", freeId.id(), 1, Map.of("project_id", other))).version())
                .isEqualTo(2);
    }
}
