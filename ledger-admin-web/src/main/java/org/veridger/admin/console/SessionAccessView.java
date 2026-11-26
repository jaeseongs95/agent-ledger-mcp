// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.veridger.domain.model.SessionGrant;

/**
 * Session-centric view model for the admin {@code /grants} console. Groups active project grants by session author so
 * operators manage permissions per session in one place.
 */
public final class SessionAccessView {

    /** Active scopes held by one session on one project, ready for the interactive scope matrix. */
    public record ProjectGrantRow(String project, Set<String> scopes, Instant expiresAt, boolean mixedExpiry,
            String note) {

        public boolean hasScope(String token) {
            return scopes.contains(token);
        }

        public String expiresAtIso() {
            return expiresAt == null ? "" : expiresAt.toString();
        }

        public String initialScopesCsv() {
            return String.join(",", scopes);
        }

        public int scopeCount() {
            return scopes.size();
        }
    }

    /** Everything an operator needs to inspect and manage for one session author. */
    public record SessionCard(String author, List<ProjectGrantRow> projects, List<String> availableProjects,
            int totalGrantCount) {

        public String projectListText() {
            return projects.stream().map(ProjectGrantRow::project).collect(Collectors.joining(" "));
        }

        public String availableProjectsCsv() {
            return String.join(",", availableProjects);
        }

        public String searchableText() {
            StringBuilder sb = new StringBuilder(author);
            for (ProjectGrantRow row : projects) {
                sb.append(' ').append(row.project());
                if (row.note() != null && !row.note().isBlank()) {
                    sb.append(' ').append(row.note());
                }
            }
            return sb.toString();
        }
    }

    private SessionAccessView() {
    }

    /** Aggregates active grants by author id into sorted session cards. */
    public static List<SessionCard> build(List<SessionGrant> grants, List<String> allProjectIds) {
        Map<String, Map<String, List<SessionGrant>>> grantsByAuthor = new TreeMap<>(
                String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        for (SessionGrant grant : grants) {
            grantsByAuthor.computeIfAbsent(grant.authorId().value(), ignored -> new TreeMap<>())
                    .computeIfAbsent(grant.projectId().value(), ignored -> new ArrayList<>())
                    .add(grant);
        }

        List<SessionCard> cards = new ArrayList<>(grantsByAuthor.size());
        for (Map.Entry<String, Map<String, List<SessionGrant>>> authorEntry : grantsByAuthor.entrySet()) {
            String author = authorEntry.getKey();
            Map<String, List<SessionGrant>> byProject = authorEntry.getValue();

            List<ProjectGrantRow> projectRows = new ArrayList<>(byProject.size());
            int totalGrants = 0;
            for (Map.Entry<String, List<SessionGrant>> entry : byProject.entrySet()) {
                List<SessionGrant> projectGrants = entry.getValue();
                totalGrants += projectGrants.size();
                projectRows.add(toProjectRow(entry.getKey(), projectGrants));
            }

            List<String> available = new ArrayList<>();
            for (String projectId : allProjectIds) {
                if (!byProject.containsKey(projectId)) {
                    available.add(projectId);
                }
            }
            cards.add(new SessionCard(author, List.copyOf(projectRows), List.copyOf(available), totalGrants));
        }
        return List.copyOf(cards);
    }

    /** Returns every session author that currently holds at least one active grant. */
    public static List<String> knownAuthors(List<SessionCard> cards) {
        return cards.stream().map(SessionCard::author).toList();
    }

    private static ProjectGrantRow toProjectRow(String project, List<SessionGrant> projectGrants) {
        Set<String> scopes = new LinkedHashSet<>();
        Instant earliest = null;
        boolean hasNullExpiry = false;
        Set<Instant> distinctExpiries = new LinkedHashSet<>();
        String note = null;
        for (SessionGrant grant : projectGrants) {
            scopes.add(grant.scope().token());
            if (grant.expiresAt() == null) {
                hasNullExpiry = true;
            } else {
                distinctExpiries.add(grant.expiresAt());
                if (earliest == null || grant.expiresAt().isBefore(earliest)) {
                    earliest = grant.expiresAt();
                }
            }
            if (note == null && grant.note() != null && !grant.note().isBlank()) {
                note = grant.note();
            }
        }
        boolean mixedExpiry = distinctExpiries.size() > 1 || (hasNullExpiry && !distinctExpiries.isEmpty());
        return new ProjectGrantRow(project, Set.copyOf(scopes), earliest, mixedExpiry, note);
    }
}
