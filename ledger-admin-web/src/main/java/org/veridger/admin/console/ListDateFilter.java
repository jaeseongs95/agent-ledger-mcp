// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.ui.Model;
import org.veridger.database.repository.DateQuery;
import org.veridger.database.repository.DateQuery.Column;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;

/** Calendar dates in the browser's time zone, and the sort links for a paged table. */
public record ListDateFilter(String dateField, @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo, String dateZone, String sort,
        String direction) {

    public record SortLink(String href, String ariaSort, String arrow) {
    }

    public boolean hasRange() {
        return dateFrom != null || dateTo != null;
    }

    DateQuery query(boolean history) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw ValidationException.invalid("dateTo", ValidationReason.OUT_OF_RANGE, dateFrom.toString(), "9999-12-31");
        }
        Column rangeField = hasRange() ? dateColumn(dateField == null ? "created" : dateField, history, "dateField")
                : null;
        Column sortField = sort == null ? null : dateColumn(sort, history, "sort");
        if (direction != null && !List.of("asc", "desc").contains(direction)) {
            throw ValidationException.invalid("direction", ValidationReason.UNKNOWN_VALUE, List.of("asc", "desc"), direction);
        }
        ZoneId zone = hasRange() ? zone() : ZoneOffset.UTC;
        return new DateQuery(rangeField, dateFrom == null ? null : dateFrom.atStartOfDay(zone).toInstant(),
                dateTo == null ? null : dateTo.plusDays(1).atStartOfDay(zone).toInstant(),
                sortField, sortField != null && "asc".equals(direction));
    }

    void populate(Model model, String path, Map<String, String> filters, Integer limit, String defaultSort) {
        filters.put("dateField", dateField);
        filters.put("dateFrom", dateFrom == null ? null : dateFrom.toString());
        filters.put("dateTo", dateTo == null ? null : dateTo.toString());
        filters.put("dateZone", dateZone);
        filters.put("sort", sort);
        filters.put("direction", direction);
        String active = sort == null ? defaultSort : sort;
        model.addAttribute("createdSort", sortLink(path, filters, limit, "created", active));
        if (!"/history".equals(path)) {
            model.addAttribute("updatedSort", sortLink(path, filters, limit, "updated", active));
        }
    }

    private SortLink sortLink(String path, Map<String, String> filters, Integer limit, String column, String active) {
        boolean sorted = Objects.equals(column, active);
        boolean ascending = sorted && sort != null && "asc".equals(direction);
        Map<String, String> link = new LinkedHashMap<>(filters);
        link.put("sort", column);
        link.put("direction", sorted && !ascending ? "asc" : "desc");
        return new SortLink(Paging.firstHref(path, link, limit),
                sorted ? (ascending ? "ascending" : "descending") : null,
                sorted ? (ascending ? "↑" : "↓") : "↕");
    }

    private ZoneId zone() {
        try {
            return dateZone == null ? ZoneOffset.UTC : ZoneId.of(dateZone);
        } catch (DateTimeException invalid) {
            throw ValidationException.invalid("dateZone", ValidationReason.MUST_MATCH, "IANA time zone");
        }
    }

    private static Column dateColumn(String field, boolean history, String parameter) {
        if ("created".equals(field)) {
            return history ? Column.OCCURRED : Column.CREATED;
        }
        if (!history && "updated".equals(field)) {
            return Column.UPDATED;
        }
        throw ValidationException.invalid(parameter, ValidationReason.UNKNOWN_VALUE,
                history ? List.of("created") : List.of("created", "updated"), field);
    }
}
