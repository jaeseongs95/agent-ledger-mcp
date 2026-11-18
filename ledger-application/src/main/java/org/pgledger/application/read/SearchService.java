package org.pgledger.application.read;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.pgledger.database.repository.KeywordMatch;
import org.pgledger.database.repository.SearchQueryRepository;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.CriterionState;
import org.pgledger.domain.rule.IssueStatus;
import org.pgledger.domain.rule.SpecStatus;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.PageCursor;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.Tokens;
import org.pgledger.domain.value.UtcInstant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * Multi-entity structured keyword search across issues, specifications, tasks, and milestone criteria, newest first.
 */
@Service
@Transactional(readOnly = true)
@Validated
public class SearchService {

    public static final int MAX_KEYWORDS = 20;
    public static final int MAX_KEYWORD_CHARS = 200;

    private static final List<String> ALLOWED_KINDS = List.of("issue", "spec", "task", "criterion");
    private static final Set<String> ALL_STATES = collectStates();

    private static Set<String> collectStates() {
        Set<String> out = new LinkedHashSet<>();
        for (IssueStatus s : IssueStatus.values()) {
            out.add(s.name());
        }
        for (SpecStatus s : SpecStatus.values()) {
            out.add(s.name());
        }
        for (TaskState s : TaskState.values()) {
            out.add(s.name());
        }
        for (CriterionState s : CriterionState.values()) {
            out.add(s.name());
        }
        return Set.copyOf(out);
    }

    public record SearchQuery(String project, List<String> entities,
            @NotEmpty(message = "{reason.MUST_HOLD_ITEMS}")
            @Size(max = MAX_KEYWORDS, message = "{reason.MUST_HOLD_ITEMS}") List<String> keywords,
            String match, List<String> excludeStates, String createdFrom, String createdTo,
            Number maxRows, String cursor) {

        public SearchQuery {
            project = Texts.nullIfBlank(project);
            match = Texts.nullIfBlank(match);
            createdFrom = Texts.nullIfBlank(createdFrom);
            createdTo = Texts.nullIfBlank(createdTo);
        }
    }

    public record Hit(String entity, String id, String projectId, String title, String state, Instant createdAt) {
    }

    private final SearchQueryRepository queries;
    private final Cursors cursors;

    public SearchService(SearchQueryRepository queries, Cursors cursors) {
        this.queries = queries;
        this.cursors = cursors;
    }

    public Page<Hit> search(@Valid SearchQuery q) {
        ProjectId project = q.project() == null ? null : ProjectId.of(q.project());
        Set<SearchQueryRepository.Kind> kinds = parseKinds(q.entities());
        List<String> keywords = validateKeywords(q.keywords());
        KeywordMatch match = q.match() == null ? KeywordMatch.ANY
                : Tokens.parseName("match", q.match(), KeywordMatch.values());
        Set<String> excluded = parseExcludedStates(q.excludeStates());
        Instant from = q.createdFrom() == null ? null : UtcInstant.parse("created_from", q.createdFrom()).value();
        Instant to = q.createdTo() == null ? null : UtcInstant.parse("created_to", q.createdTo()).value();
        MaxRows maxRows = cursors.maxRows(q.maxRows());

        SearchQueryRepository.Filter filter = new SearchQueryRepository.Filter(project, kinds, keywords, match,
                excluded, from, to);
        var page = cursors.decode(q.cursor(), PageCursor.Kind.SEARCH, filter)
                .map(SearchService::toPosition)
                .map(after -> queries.page(filter, maxRows, after))
                .orElseGet(() -> queries.page(filter, maxRows, null));
        return cursors.page(page, PageCursor.Kind.SEARCH, filter,
                h -> new Hit(kindToken(h.kind()), h.id(), h.projectId(), h.title(), h.state(), h.createdAt()),
                SearchService::encodeSortKey, SearchQueryRepository.Hit::id);
    }

    private static String kindToken(SearchQueryRepository.Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    private static SearchQueryRepository.Kind parseKind(String path, String raw) {
        if ("issue".equals(raw)) {
            return SearchQueryRepository.Kind.ISSUE;
        }
        if ("spec".equals(raw)) {
            return SearchQueryRepository.Kind.SPEC;
        }
        if ("task".equals(raw)) {
            return SearchQueryRepository.Kind.TASK;
        }
        if ("criterion".equals(raw)) {
            return SearchQueryRepository.Kind.CRITERION;
        }
        throw ValidationException.invalid(path, ValidationReason.UNKNOWN_VALUE, ALLOWED_KINDS, String.valueOf(raw));
    }

    private static Set<SearchQueryRepository.Kind> parseKinds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of(SearchQueryRepository.Kind.values());
        }
        Set<SearchQueryRepository.Kind> out = new LinkedHashSet<>();
        for (String item : raw) {
            out.add(parseKind("entities", item));
        }
        return out;
    }

    private static List<String> validateKeywords(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        for (String kw : raw) {
            if (kw == null || kw.isBlank()) {
                throw ValidationException.invalid("keywords", ValidationReason.MUST_NOT_BE_BLANK);
            }
            if (kw.length() > MAX_KEYWORD_CHARS) {
                throw ValidationException.invalid("keywords", ValidationReason.MUST_BE_AT_MOST_CHARS,
                        Integer.toString(MAX_KEYWORD_CHARS));
            }
            out.add(kw);
        }
        return out;
    }

    private static Set<String> parseExcludedStates(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        List<String> allowed = List.copyOf(ALL_STATES);
        for (String state : raw) {
            if (state == null || !ALL_STATES.contains(state)) {
                throw ValidationException.invalid("exclude_states", ValidationReason.UNKNOWN_VALUE, allowed,
                        String.valueOf(state));
            }
            out.add(state);
        }
        return out;
    }

    private static String encodeSortKey(SearchQueryRepository.Hit hit) {
        Instant sortAt = hit.createdAt() == null ? Instant.EPOCH : hit.createdAt();
        return new UtcInstant(sortAt).toString() + "|" + kindToken(hit.kind());
    }

    private static SearchQueryRepository.Position toPosition(Cursors.Position pos) {
        if (pos.sortKey() == null) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED);
        }
        int bar = pos.sortKey().indexOf('|');
        if (bar <= 0 || bar == pos.sortKey().length() - 1) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED);
        }
        String rawAt = pos.sortKey().substring(0, bar);
        String rawKind = pos.sortKey().substring(bar + 1);
        Instant at;
        SearchQueryRepository.Kind kind;
        try {
            at = UtcInstant.parse("cursor", rawAt).value();
            kind = parseKind("cursor", rawKind);
        } catch (ValidationException invalid) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED, invalid);
        }
        return new SearchQueryRepository.Position(at, kind, pos.id());
    }
}
