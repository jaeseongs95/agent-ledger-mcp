// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.console;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.agentledger.application.read.Page;
import org.agentledger.application.read.CatalogService;
import org.agentledger.application.read.CatalogService.CodeView;
import org.agentledger.application.read.CatalogService.CodesQuery;
import org.agentledger.application.read.CatalogService.ProjectView;
import org.agentledger.application.read.CatalogService.ProjectsQuery;
import org.agentledger.domain.rule.CodeGroup;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The lists the pages and the filter forms offer whole: the projects, the codes of every group, and the active codes of a group.
 * The services page them (at most {@link #ALL} rows a page), so each list follows the cursors to the last page: a list that
 * stopped at the first page would drop the rest without a word.
 */
@Component
public class Choices {

    /** The most rows one page of a list asks for. */
    public static final int ALL = 1000;

    private final CatalogService catalog;

    public Choices(CatalogService catalog) {
        this.catalog = catalog;
    }

    /** The projects, and the one the page works on: the one asked for, else the first (absent when there is none). */
    public record ProjectChoice(List<ProjectView> projects, String selected) {
    }

    public ProjectChoice project(String requested) {
        List<ProjectView> projects = projects();
        String selected = StringUtils.hasText(requested) ? requested
                : projects.isEmpty() ? null : projects.getFirst().id();
        return new ProjectChoice(projects, selected);
    }

    /** Every project. */
    public List<ProjectView> projects() {
        return all(cursor -> catalog.projects(new ProjectsQuery(ALL, cursor)));
    }

    /** The active codes of a group. */
    public List<CodeView> codes(CodeGroup group) {
        return all(cursor -> catalog.codes(new CodesQuery(group.name(), false, ALL, cursor)));
    }

    /** Every code of every group, the retired ones too. */
    public List<CodeView> allCodes() {
        return all(cursor -> catalog.codes(new CodesQuery(null, true, ALL, cursor)));
    }

    /** The items of every page of a list, from the first to the page that has no next cursor. */
    private static <T> List<T> all(Function<String, Page<T>> pages) {
        List<T> items = new ArrayList<>();
        String cursor = null;
        do {
            Page<T> page = pages.apply(cursor);
            items.addAll(page.items());
            cursor = page.truncated() ? page.nextCursor() : null;
        } while (cursor != null);
        return items;
    }
}
