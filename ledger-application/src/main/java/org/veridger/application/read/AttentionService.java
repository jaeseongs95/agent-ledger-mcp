// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.read;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.veridger.database.repository.AttentionQueryRepository;
import org.veridger.database.repository.EntityLookupRepository;
import org.veridger.domain.rule.AttentionKind;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.value.MaxRows;
import org.veridger.domain.value.PageCursor;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.Tokens;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** One attention item enriched with title, owner, and updated timestamp for the console overview. */
    public record HydratedItem(String kind, String entity, String id, String projectId, String title, String owner,
            Instant updatedAt) {
    }

    /** Bounded per-kind attention count exposed to web/application callers without leaking repository types. */
    public record BoundedCount(int count, boolean atLeast) {
    }

    private final AttentionQueryRepository attention;
    private final Cursors cursors;
    private final EntityLookupRepository entityLookup;

    public AttentionService(AttentionQueryRepository attention, Cursors cursors) {
        this(attention, cursors, null);
    }

    @Autowired
    public AttentionService(AttentionQueryRepository attention, Cursors cursors, EntityLookupRepository entityLookup) {
        this.attention = attention;
        this.cursors = cursors;
        this.entityLookup = entityLookup;
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

    /** Bounded per-kind counts without hydrating rows (used by the admin overview). */
    public Map<AttentionKind, BoundedCount> counts(String project, int cap) {
        String scope = Texts.nullIfBlank(project);
        ProjectId projectId = scope == null ? null : ProjectId.of(scope);
        Map<AttentionKind, AttentionQueryRepository.BoundedCount> raw = attention.counts(projectId, cap);
        Map<AttentionKind, BoundedCount> result = new EnumMap<>(AttentionKind.class);
        for (Map.Entry<AttentionKind, AttentionQueryRepository.BoundedCount> entry : raw.entrySet()) {
            AttentionQueryRepository.BoundedCount value = entry.getValue();
            result.put(entry.getKey(), new BoundedCount(value.count(), value.atLeast()));
        }
        return result;
    }

    /**
     * Hydrates a page of attention items with title, effective owner, and updated timestamp using at most one batched
     * primary-key lookup per entity table.
     */
    public List<HydratedItem> hydrate(List<Item> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (entityLookup == null) {
            return items.stream()
                    .map(i -> new HydratedItem(i.kind(), i.entity(), i.id(), i.projectId(), null, null, null))
                    .toList();
        }
        Set<String> issueIds = new LinkedHashSet<>();
        Set<String> specIds = new LinkedHashSet<>();
        Set<String> taskIds = new LinkedHashSet<>();
        for (Item item : items) {
            if (Objects.equals(HistoryEntity.ISSUE.token(), item.entity())) {
                issueIds.add(item.id());
            } else if (Objects.equals(HistoryEntity.SPEC.token(), item.entity())) {
                specIds.add(item.id());
            } else if (Objects.equals(HistoryEntity.TASK.token(), item.entity())) {
                taskIds.add(item.id());
            }
        }
        Map<String, EntityLookupRepository.AttentionDetailRow> byKey = new HashMap<>();
        for (EntityLookupRepository.AttentionDetailRow row : entityLookup.attentionDetails(issueIds, specIds,
                taskIds)) {
            byKey.put(row.entity().token() + "\0" + row.id(), row);
        }
        List<HydratedItem> out = new ArrayList<>(items.size());
        for (Item item : items) {
            EntityLookupRepository.AttentionDetailRow detail = byKey.get(item.entity() + "\0" + item.id());
            out.add(new HydratedItem(item.kind(), item.entity(), item.id(), item.projectId(),
                    detail == null ? null : detail.title(),
                    detail == null ? null : detail.ownerActorId(),
                    detail == null ? null : detail.updatedAt()));
        }
        return out;
    }
}
