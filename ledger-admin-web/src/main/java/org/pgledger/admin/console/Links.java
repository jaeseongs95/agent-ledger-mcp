package org.pgledger.admin.console;

import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.value.Tokens;
import org.springframework.stereotype.Component;

/**
 * What the pages may turn into a link: evidence addresses are shown as links only when they are http or https, and the
 * item of the attention list goes to the page of its kind.
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

    /**
     * The page of an item of the attention list, as the link base the page gives the {@code id} to: the issue page
     * ({@code /issues/{id}}, the id is a path segment) for an issue, the specification page ({@code /specs/detail}, the
     * id is a query parameter: ids may be free text) for a specification, and the task page ({@code /tasks/detail}, the
     * id is a query parameter) for a task.
     *
     * @throws org.pgledger.domain.error.ValidationException when the text names no kind of row
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
}
