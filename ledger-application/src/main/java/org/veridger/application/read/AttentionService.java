// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.read;

import java.util.List;
import org.veridger.database.repository.AttentionQueryRepository;
import org.veridger.domain.rule.AttentionKind;
import org.veridger.domain.value.MaxRows;
import org.veridger.domain.value.PageCursor;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.Tokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues, specifications, and tasks needing attention, ordered by kind and id in keyset pages. */
@Service
@Transactional(readOnly = true)
public class AttentionService {

    public record AttentionQuery(String project, String kind, Number maxRows, String cursor) {

        public AttentionQuery {
            project = Texts.nullIfBlank(project);
            kind = Texts.nullIfBlank(kind);
        }
    }

    /** One attention item; {@code entity} is {@code issue}, {@code spec}, or {@code task}. */
    public record Item(String kind, String entity, String id, String projectId) {
    }

    private final AttentionQueryRepository attention;
    private final Cursors cursors;

    public AttentionService(AttentionQueryRepository attention, Cursors cursors) {
        this.attention = attention;
        this.cursors = cursors;
    }

    public Page<Item> attention(AttentionQuery q) {
        ProjectId project = q.project() == null ? null : ProjectId.of(q.project());
        AttentionKind kind = q.kind() == null ? null : Tokens.parseName("kind", q.kind(), AttentionKind.values());
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        Object criteria = List.of(String.valueOf(project), String.valueOf(kind));
        var page = cursors.decode(q.cursor(), PageCursor.Kind.ATTENTION, criteria)
                .map(position -> new AttentionQueryRepository.Position(
                        Tokens.parseName("cursor", position.sortKey(), AttentionKind.values()), position.id()))
                .map(after -> attention.page(project, kind, maxRows, after))
                .orElseGet(() -> attention.page(project, kind, maxRows, null));
        return cursors.page(page, PageCursor.Kind.ATTENTION, criteria,
                i -> new Item(i.kind().name(), i.kind().entity().token(), i.id(), i.projectId()),
                i -> i.kind().name(), AttentionQueryRepository.Item::id);
    }
}
