// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.application.admin.CommonCodeService;
import org.agentledger.application.admin.CommonCodeService.CodeCommand;
import org.agentledger.application.history.HistoryKey;
import org.agentledger.application.read.CatalogService;
import org.agentledger.application.read.CatalogService.CodeView;
import org.agentledger.application.read.CatalogService.CodesQuery;
import org.agentledger.application.read.Cursors;
import org.agentledger.application.read.DetailService;
import org.agentledger.application.read.HistoryService;
import org.agentledger.application.read.HistoryService.HistoryQuery;
import org.agentledger.application.read.HistoryService.HistoryView;
import org.agentledger.application.read.Page;
import org.agentledger.application.support.Expect;
import org.agentledger.application.support.LedgerServiceTest;
import org.agentledger.application.support.Scenario;
import org.agentledger.application.write.LinkCommand;
import org.agentledger.application.write.LinkService;
import org.agentledger.application.write.RecordDeliverableService;
import org.agentledger.application.write.SaveService;
import org.agentledger.domain.error.ValidationCode;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationReason;
import org.agentledger.domain.value.PageCursor;
import org.agentledger.testing.TestOwners;

/**
 * R9-D0079, R9-D0080, R9-D0085 and R9-D0086: the read side of the services. The cursor of a list of codes, the project of a
 * history row whose entity has a {@code |} or an {@code @} in its id, a null in the list of history ids, and the one
 * constructor of {@link DetailService}.
 */
@LedgerServiceTest
class ReadDefectsR9Test {

    private static final String GROUP = "ISSUE_CATEGORY";

    private static CodeCommand code(String code, int order, String state) {
        return new CodeCommand(GROUP, code, "이름 " + code, "Name " + code, null, null, order, state);
    }

    @Test
    @DisplayName("R9-D0079: a cursor whose code was retired after the page was read goes on with the codes after it; a cursor that names no code is refused")
    void theCursorOfARetiredCodeStillHasItsPlace(Scenario s) {
        CommonCodeService codes = s.bean(CommonCodeService.class);
        CatalogService catalog = s.bean(CatalogService.class);
        String tag = s.code().toLowerCase(Locale.ROOT);
        for (int i = 1; i <= 3; i++) {
            codes.set(code("r9_" + tag + "_" + i, 900 + i, null), TestOwners.OWNER);
        }
        int active = catalog.codes(new CodesQuery(GROUP, null, 1000, null)).items().size();
        Page<CodeView> first = catalog.codes(new CodesQuery(GROUP, null, active - 1, null));
        assertThat(first.truncated()).isTrue();
        CodeView lastOfFirst = first.items().getLast();
        codes.set(code(lastOfFirst.code(), lastOfFirst.sortOrder(), "INACTIVE"), TestOwners.OWNER);

        Page<CodeView> second = catalog.codes(new CodesQuery(GROUP, null, active - 1, first.nextCursor()));

        assertThat(second.items()).as("the one code that came after the retired one").extracting(CodeView::code)
                .containsExactly("r9_" + tag + "_3");
        assertThat(second.truncated()).isFalse();

        String unknown = s.bean(Cursors.class).encode(PageCursor.Kind.CODES, List.of(GROUP, false), null,
                GROUP + ":no_such_code_" + tag);
        ValidationException refused = (ValidationException) Expect.failure(
                () -> catalog.codes(new CodesQuery(GROUP, null, 10, unknown)), ValidationCode.VALIDATION_FAILED);
        assertThat(refused.args()).containsExactly("cursor");
        assertThat(Expect.firstIssue(refused).reason()).isEqualTo(ValidationReason.CURSOR_DAMAGED);
    }

    private static List<String> entityIds(Scenario s, String project) {
        return s.bean(HistoryService.class).history(new HistoryQuery(null, null, null, null, null, project, null,
                null, null, null, null, 1000, null)).items().stream().map(HistoryView::entityId).toList();
    }

    /** A second project on which the author may write. */
    private static String otherProject(Scenario s) {
        String other = s.otherProject();
        s.grant(Scenario.AUTHOR, other, "issue", "spec", "task", "deliverable");
        return other;
    }

    private static String save(Scenario s, String entity, String id, Map<String, String> fields, String project) {
        fields.put("project_id", project);
        return s.bean(SaveService.class).save(Scenario.AUTHOR, s.create(entity, id, fields)).id();
    }

    private static String link(Scenario s, String kind, String a, String b) {
        return s.bean(LinkService.class)
                .link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), kind, a, b, Scenario.REASON, null)).id();
    }

    @Test
    @DisplayName("R9-D0080: the link of a criterion whose id continues the id of a criterion of another project with | is not a row of that project")
    void aCriterionLinkBelongsToTheProjectOfItsCriterion(Scenario s) {
        String other = otherProject(s);
        String shortId = s.code() + "-c9";
        String longId = shortId + "|x";
        save(s, "criterion", shortId, s.criterionFields("m", "a"), s.projectId());
        save(s, "criterion", longId, s.criterionFields("m", "b"), other);
        String task = save(s, "task", null, s.taskFields("t"), other);
        String linkId = link(s, "criterion_task", longId, task);

        assertThat(entityIds(s, other)).contains(linkId);
        assertThat(entityIds(s, s.projectId())).doesNotContain(linkId);
    }

    @Test
    @DisplayName("R9-D0080: the link of a spec whose id continues the id of a spec of another project with | is not a row of that project")
    void aSpecTaskLinkBelongsToTheProjectOfItsSpec(Scenario s) {
        String other = otherProject(s);
        String shortId = s.code() + "-sp";
        String longId = shortId + "|x";
        save(s, "spec", shortId, s.specFields("short"), s.projectId());
        save(s, "spec", longId, s.specFields("long"), other);
        String task = save(s, "task", null, s.taskFields("t"), other);
        String linkId = link(s, "spec_task", longId, task);

        assertThat(entityIds(s, other)).contains(linkId);
        assertThat(entityIds(s, s.projectId())).doesNotContain(linkId);
    }

    @Test
    @DisplayName("R9-D0080: the deliverable of a spec whose id continues the id of a spec of another project with @ is not a row of that project")
    void aDeliverableBelongsToTheProjectOfItsSpec(Scenario s) {
        String other = otherProject(s);
        String shortId = s.code() + "-sd";
        String longId = shortId + "@x";
        save(s, "spec", shortId, s.specFields("short"), s.projectId());
        save(s, "spec", longId, s.specFields("long"), other);
        String ref = Scenario.ref('d');
        s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                s.deliverableCommand("spec", longId, ref));
        String key = HistoryKey.deliverableEntityId("spec", longId, "code_change", ref);

        assertThat(entityIds(s, other)).contains(key);
        assertThat(entityIds(s, s.projectId())).doesNotContain(key);
    }

    @Test
    @DisplayName("R9-D0085: a null in the list of history ids is VALIDATION_FAILED on ids, not an internal error")
    void aNullIdIsAValidationFailure(Scenario s) {
        List<Long> ids = Arrays.asList(1L, null);

        Throwable failure = catchThrowable(() -> s.bean(HistoryService.class).history(new HistoryQuery(ids, null, null,
                null, null, null, null, null, null, null, null, 1000, null)));

        assertThat(failure).as("a business failure, not %s", failure).isInstanceOf(ValidationException.class);
        ValidationException refused = (ValidationException) failure;
        assertThat(refused.args()).containsExactly("ids");
        assertThat(Expect.firstIssue(refused).reason()).isEqualTo(ValidationReason.REQUIRED);
    }

    @Test
    @DisplayName("R9-D0086: DetailService has the one constructor that the container calls, none that leaves the criteria out")
    void detailServiceHasOneConstructor() {
        assertThat(Arrays.stream(DetailService.class.getDeclaredConstructors())
                .filter(c -> Modifier.isPublic(c.getModifiers()))).hasSize(1);
    }
}
