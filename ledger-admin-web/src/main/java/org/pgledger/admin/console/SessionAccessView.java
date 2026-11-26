// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.console;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.model.SessionToken;

/**
 * Session-centric view model for the admin {@code /grants} console. Groups live session keys, unrevoked bearer tokens,
 * and active project grants by author so operators manage permissions per session instead of across three separate
 * tables.
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
    public record SessionCard(String author, SessionPublicKey liveKey, List<SessionToken> liveTokens,
            List<ProjectGrantRow> projects, List<String> availableProjects, int totalGrantCount) {

        public boolean hasCredential() {
            return liveKey != null || !liveTokens.isEmpty();
        }

        public String credentialKind() {
            if (liveKey != null && !liveTokens.isEmpty()) {
                return "both";
            }
            if (liveKey != null) {
                return "key";
            }
            if (!liveTokens.isEmpty()) {
                return "token";
            }
            return "none";
        }

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
            if (liveKey != null) {
                sb.append(' ').append(liveKey.fingerprint());
            }
            for (SessionToken token : liveTokens) {
                sb.append(' ').append(token.tokenPrefix());
                if (token.note() != null && !token.note().isBlank()) {
                    sb.append(' ').append(token.note());
                }
            }
            return sb.toString();
        }
    }

    private SessionAccessView() {
    }

    /** Aggregates keys, tokens, and grants by author id into sorted session cards. */
    public static List<SessionCard> build(List<SessionGrant> grants, List<SessionPublicKey> keys,
            List<SessionToken> tokens, List<String> allProjectIds) {
        Map<String, SessionPublicKey> keyByAuthor = new TreeMap<>(String.CASE_INSENSITIVE_ORDER.thenComparing(
                Comparator.naturalOrder()));
        for (SessionPublicKey key : keys) {
            keyByAuthor.put(key.authorId().value(), key);
        }

        Map<String, List<SessionToken>> tokensByAuthor = new TreeMap<>(String.CASE_INSENSITIVE_ORDER.thenComparing(
                Comparator.naturalOrder()));
        for (SessionToken token : tokens) {
            tokensByAuthor.computeIfAbsent(token.authorId().value(), ignored -> new ArrayList<>()).add(token);
        }

        Map<String, Map<String, List<SessionGrant>>> grantsByAuthor = new TreeMap<>(
                String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        for (SessionGrant grant : grants) {
            grantsByAuthor.computeIfAbsent(grant.authorId().value(), ignored -> new TreeMap<>())
                    .computeIfAbsent(grant.projectId().value(), ignored -> new ArrayList<>())
                    .add(grant);
        }

        Set<String> allAuthors = new LinkedHashSet<>();
        Map<String, String> sortedAuthors = new TreeMap<>(String.CASE_INSENSITIVE_ORDER.thenComparing(
                Comparator.naturalOrder()));
        keyByAuthor.keySet().forEach(a -> sortedAuthors.put(a, a));
        tokensByAuthor.keySet().forEach(a -> sortedAuthors.put(a, a));
        grantsByAuthor.keySet().forEach(a -> sortedAuthors.put(a, a));
        allAuthors.addAll(sortedAuthors.values());

        List<SessionCard> cards = new ArrayList<>(allAuthors.size());
        for (String author : allAuthors) {
            SessionPublicKey key = keyByAuthor.get(author);
            List<SessionToken> authorTokens = List.copyOf(tokensByAuthor.getOrDefault(author, List.of()));
            Map<String, List<SessionGrant>> byProject = grantsByAuthor.getOrDefault(author, Map.of());

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
            cards.add(new SessionCard(author, key, authorTokens, List.copyOf(projectRows), List.copyOf(available),
                    totalGrants));
        }
        return List.copyOf(cards);
    }

    /** Returns authors that currently hold a live key or an unrevoked bearer token. */
    public static List<String> enrolledAuthors(List<SessionCard> cards) {
        return cards.stream().filter(SessionCard::hasCredential).map(SessionCard::author).toList();
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
