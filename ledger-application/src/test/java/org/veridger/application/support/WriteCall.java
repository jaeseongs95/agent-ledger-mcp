// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.support;

import java.util.List;
import java.util.function.BiConsumer;
import org.veridger.application.write.BatchOpInput;
import org.veridger.application.write.BatchService;
import org.veridger.application.write.CorrectCommand;
import org.veridger.application.write.CorrectService;
import org.veridger.application.write.LinkCommand;
import org.veridger.application.write.LinkService;
import org.veridger.application.write.RecordCaseCommand;
import org.veridger.application.write.RecordCaseService;
import org.veridger.application.write.RecordDeliverableService;
import org.veridger.application.write.ReviewService;
import org.veridger.application.write.SaveService;
import org.veridger.application.write.TransitionCommand;
import org.veridger.application.write.TransitionService;
import org.veridger.application.write.WhoamiService;
import org.veridger.domain.rule.Channel;

/** Every write service (and whoami) invoked with a given actor against a scenario's targets. */
public record WriteCall(String name, BiConsumer<Scenario, String> invoke) {

    @Override
    public String toString() {
        return name;
    }

    public static List<WriteCall> all() {
        return List.of(
                new WriteCall("save", (s, actor) -> s.bean(SaveService.class).save(actor,
                        s.create("issue", null, s.issueFields("t")))),
                new WriteCall("transition", (s, actor) -> s.bean(TransitionService.class).transition(actor,
                        s.move("issue", s.issueId(1), 1, "DISPUTED", Scenario.EVIDENCE))),
                new WriteCall("record_deliverable", (s, actor) -> s.bean(RecordDeliverableService.class)
                        .recordDeliverable(actor, s.deliverableCommand(s.issueId(1), Scenario.ref('a')))),
                new WriteCall("record_case", (s, actor) -> s.bean(RecordCaseService.class).recordCase(actor,
                        new RecordCaseCommand(Scenario.uuid(), s.issueId(1), "seen", Scenario.REASON, null, null,
                                null))),
                new WriteCall("link", (s, actor) -> s.bean(LinkService.class).link(actor,
                        new LinkCommand(Scenario.uuid(), "issue_task", s.issueId(1), s.taskId(1), Scenario.REASON,
                                null))),
                new WriteCall("correct", (s, actor) -> s.bean(CorrectService.class).correct(actor,
                        new CorrectCommand(Scenario.uuid(), 1L, Scenario.REASON))),
                new WriteCall("batch", (s, actor) -> s.bean(BatchService.class).batch(actor, Scenario.uuid(),
                        List.of(new BatchOpInput.Save("issue", Fields.of(s.issueFields("t")), Scenario.REASON, null,
                                null)))),
                new WriteCall("review", (s, actor) -> s.bean(ReviewService.class).review(actor,
                        new TransitionCommand(Scenario.uuid(), "issue", s.issueId(1), 1L, "DISPUTED", Scenario.REASON,
                                Scenario.EVIDENCE, null, null))),
                new WriteCall("review_correct", (s, actor) -> s.bean(ReviewService.class).reviewCorrect(actor,
                        new CorrectCommand(Scenario.uuid(), 1L, Scenario.REASON))),
                new WriteCall("whoami", (s, actor) -> s.bean(WhoamiService.class).whoami(actor, Channel.AUTHOR)));
    }
}
