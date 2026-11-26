// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.read;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.veridger.database.repository.EntityLookupRepository;
import org.veridger.database.repository.EntityLookupRepository.Found;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.rule.HistoryEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fetches issues, specifications, tasks, and milestone criteria by exact IDs in caller-requested order without
 * erroring on unknown IDs.
 */
@Service
@Transactional(readOnly = true)
public class GetService {

    public static final int MAX_IDS = 200;

    public record Item(String entity, String id, String projectId, String state, long version, String mergedInto) {
    }

    public record Result(List<Item> items, List<String> notFound) {
    }

    private final EntityLookupRepository lookup;

    public GetService(EntityLookupRepository lookup) {
        this.lookup = lookup;
    }

    public Result get(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS) {
            throw ValidationException.invalid("ids", ValidationReason.MUST_HOLD_ITEMS, "1",
                    Integer.toString(MAX_IDS));
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String raw : ids) {
            if (raw == null || raw.isBlank()) {
                throw ValidationException.invalid("ids", ValidationReason.MUST_NOT_BE_BLANK);
            }
            distinct.add(raw);
        }
        List<String> keys = List.copyOf(distinct);
        Map<String, Found> issues = new LinkedHashMap<>();
        Map<String, Found> specs = new LinkedHashMap<>();
        Map<String, Found> tasks = new LinkedHashMap<>();
        Map<String, Found> criteria = new LinkedHashMap<>();
        for (EntityLookupRepository.TypedFound tf : lookup.findAny(keys)) {
            switch (tf.entity()) {
                case ISSUE -> issues.put(tf.row().id(), tf.row());
                case SPEC -> specs.put(tf.row().id(), tf.row());
                case TASK -> tasks.put(tf.row().id(), tf.row());
                case CRITERION -> criteria.put(tf.row().id(), tf.row());
                case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE -> {
                }
            }
        }

        List<Item> items = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String id : keys) {
            boolean found = false;
            Found issue = issues.get(id);
            if (issue != null) {
                items.add(toItem(HistoryEntity.ISSUE, issue));
                found = true;
            }
            Found spec = specs.get(id);
            if (spec != null) {
                items.add(toItem(HistoryEntity.SPEC, spec));
                found = true;
            }
            Found task = tasks.get(id);
            if (task != null) {
                items.add(toItem(HistoryEntity.TASK, task));
                found = true;
            }
            Found crit = criteria.get(id);
            if (crit != null) {
                items.add(toItem(HistoryEntity.CRITERION, crit));
                found = true;
            }
            if (!found) {
                missing.add(id);
            }
        }
        return new Result(items, missing);
    }

    private static Item toItem(HistoryEntity entity, Found row) {
        return new Item(entity.token(), row.id(), row.projectId(), row.state(), row.version(), row.mergedInto());
    }
}
