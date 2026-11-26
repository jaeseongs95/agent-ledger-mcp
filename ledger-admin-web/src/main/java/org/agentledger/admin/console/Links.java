// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.console;

import java.util.Objects;
import org.agentledger.domain.rule.AttentionKind;
import org.agentledger.domain.rule.HistoryEntity;
import org.agentledger.domain.value.Tokens;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * What the pages may turn into a link: evidence addresses are shown as links only when they are http or https, and the
 * item of the attention list or history row goes to the page of its kind. Also builds filtered drilldown links from the
 * overview dashboard to the matching list pages.
 */
@Component("links")
public class Links {

    private static final String HTTP = "http://";
    private static final String HTTPS = "https://";

    /** True for an address that starts with {@code http://} or {@code https://} (any case). */
    public boolean isHttp(String address) {
        return address != null && (address.regionMatches(true, 0, HTTP, 0, HTTP.length())
                || address.regionMatches(true, 0, HTTPS, 0, HTTPS.length()));
    }

    /** True when the history/attention entity token has a dedicated detail page. */
    public boolean hasItemPage(String entity) {
        for (HistoryEntity candidate : HistoryEntity.values()) {
            if (Objects.equals(candidate.token(), entity)) {
                return candidate == HistoryEntity.ISSUE
                        || candidate == HistoryEntity.SPEC
                        || candidate == HistoryEntity.TASK;
            }
        }
        return false;
    }

    /**
     * The page of an item of the attention list, as the link base the page gives the {@code id} to: the issue page
     * ({@code /issues/{id}}, the id is a path segment) for an issue, the specification page ({@code /specs/detail}, the
     * id is a query parameter: ids may be free text) for a specification, and the task page ({@code /tasks/detail}, the
     * id is a query parameter) for a task.
     *
     * @throws org.agentledger.domain.error.ValidationException when the text names no kind of row
     * @throws IllegalArgumentException for a kind of row that has no page of its own
     */
    public String itemPage(String entity) {
        return switch (Tokens.parse("entity", entity, HistoryEntity.values())) {
            case ISSUE -> "/issues/{id}";
            case SPEC -> "/specs/detail";
            case TASK -> "/tasks/detail";
            case CRITERION, DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK,
                    ISSUE_CASE ->
                throw new IllegalArgumentException("the attention list has no item of the kind " + entity);
        };
    }

    /** Drilldown URL from an overview attention count to the matching list filtered by that attention kind. */
    public String attentionHref(String project, AttentionKind kind) {
        String path = switch (kind) {
            case OPEN_WITH_DELIVERABLE, RESOLVED_WITHOUT_DELIVERABLE, OPEN_NO_OWNER -> "/issues";
            case SPEC_COMPLETED_NOT_VERIFIED -> "/specs";
            case TASK_UNKNOWN, DONE_NOT_VERIFIED -> "/tasks";
        };
        return filteredHref(path, project, "attention", kind.name());
    }

    /** Drilldown URL from an overview distribution bucket to the matching list filtered by a single parameter. */
    public String statHref(String project, String path, String param, String value) {
        return filteredHref(path, project, param, value);
    }

    /** Project-scoped URL to a list page, omitting the project query parameter when all projects are selected. */
    public String projectHref(String path, String project) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        if (StringUtils.hasText(project)) {
            builder.queryParam("project", project);
        }
        return builder.build().encode().toUriString();
    }

    private static String filteredHref(String path, String project, String param, String value) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        if (StringUtils.hasText(project)) {
            builder.queryParam("project", project);
        }
        if (StringUtils.hasText(param) && StringUtils.hasText(value)) {
            builder.queryParam(param, value);
        }
        return builder.build().encode().toUriString();
    }
}
