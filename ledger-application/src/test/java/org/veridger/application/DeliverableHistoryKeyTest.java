// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.history.HistoryKey;
import org.veridger.domain.model.HistoryEntry;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.value.EntityId;
import org.veridger.domain.value.Sha256;
import org.veridger.domain.value.TextRule;

/**
 * R9-D0055: the composite history id of a deliverable, {@code <target>:<id>@<kind>:<ref>}, fits the 300 characters of
 * {@code history.entity_id} for every id, kind and reference the tools accept, and a composite that fitted before is
 * not changed.
 */
class DeliverableHistoryKeyTest {

    private static int characters(String text) {
        return text.codePointCount(0, text.length());
    }

    @Test
    @DisplayName("R9-D0055: a composite that fits (up to 300 characters) is the composite of every row written before")
    void aCompositeThatFitsIsKept() {
        assertThat(HistoryKey.deliverableEntityId("issue", "E2E-I-0001", "code_change", "abc"))
                .isEqualTo("issue:E2E-I-0001@code_change:abc");
        String prefix = "task:T1@k:";
        String exactly300 = "r".repeat(HistoryEntry.MAX_ENTITY_ID_LENGTH - prefix.length());
        assertThat(HistoryKey.deliverableEntityId("task", "T1", "k", exactly300)).isEqualTo(prefix + exactly300)
                .hasSize(HistoryEntry.MAX_ENTITY_ID_LENGTH);
    }

    @Test
    @DisplayName("R9-D0055: a composite of 301 characters keeps the prefix and ends in the SHA-256 of the reference, at 300 characters")
    void aCompositeThatDoesNotFitIsShortened() {
        String prefix = "task:T1@k:";
        String ref = "r".repeat(HistoryEntry.MAX_ENTITY_ID_LENGTH - prefix.length() + 1);
        String key = HistoryKey.deliverableEntityId("task", "T1", "k", ref);
        assertThat(key).hasSize(HistoryEntry.MAX_ENTITY_ID_LENGTH).startsWith(prefix)
                .endsWith("#" + Sha256.ofText(ref).hex());
        assertThat(HistoryKey.deliverableEntityId("task", "T1", "k", ref)).as("the same key every time").isEqualTo(key);
        assertThat(new HistoryKey(HistoryEntity.DELIVERABLE, key).ownerId()).isEqualTo("T1");
    }

    @Test
    @DisplayName("R9-D0055: references that differ only after the part that is kept have different keys")
    void referencesThatDifferLateHaveDifferentKeys() {
        String common = "c".repeat(TextRule.DELIVERABLE_REF.max() - 1);
        assertThat(HistoryKey.deliverableEntityId("issue", "I1", "code_change", common + "a"))
                .isNotEqualTo(HistoryKey.deliverableEntityId("issue", "I1", "code_change", common + "b"));
    }

    @Test
    @DisplayName("R9-D0055: the longest id, kind and reference give a key of 300 characters, also for characters outside the BMP")
    void theLongestInputsFit() {
        String id = "i".repeat(EntityId.MAX_LENGTH);
        String kind = "k".repeat(32);
        for (String unit : new String[] {"x", Character.toString(0x1F600)}) {
            String ref = unit.repeat(TextRule.DELIVERABLE_REF.max());
            String key = HistoryKey.deliverableEntityId("issue", id, kind, ref);
            assertThat(characters(key)).as("characters of the key of '%s' references", unit)
                    .isEqualTo(HistoryEntry.MAX_ENTITY_ID_LENGTH);
            assertThat(key.codePoints().noneMatch(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE))
                    .as("no half of a surrogate pair is left").isTrue();
            assertThat(new HistoryKey(HistoryEntity.DELIVERABLE, key).ownerId()).isEqualTo(id);
        }
    }
}
