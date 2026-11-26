// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.domain.value.Sha256;
import org.agentledger.migrate1x.database.LegacyObject;
import org.agentledger.migrate1x.database.LegacyObject.Kind;
import org.agentledger.migrate1x.database.LegacyRoleSkip;

/** RET-018: the list that {@code retire-1x} shows and the digest the owner confirms. */
class LegacyRetirePlanTest {

    private static final List<LegacyObject> OBJECTS = List.of(
            new LegacyObject.Trigger("common_code", "common_code_keep"),
            new LegacyObject.Named(Kind.TABLE, "task"),
            new LegacyObject.Routine(Kind.FUNCTION, "whoami", "p_author text"),
            new LegacyObject.Named(Kind.SCHEMA, "ledger"));

    @Test
    @DisplayName("RET-018 the lines are the objects in the given order and then the roles that stay; the digest is the SHA-256 of the lines, each ended by a line feed")
    void theDigestIsThatOfTheLines() {
        LegacyRetirePlan plan = new LegacyRetirePlan("ledger", OBJECTS,
                List.of(new LegacyRoleSkip("ledger_owner", LegacyRoleSkip.Reason.OTHER_MEMBERS)));

        assertThat(plan.lines()).containsExactly("TRIGGER common_code.common_code_keep", "TABLE task",
                "FUNCTION whoami(p_author text)", "SCHEMA ledger", "SKIPPED ROLE ledger_owner OTHER_MEMBERS");
        assertThat(plan.digest()).isEqualTo(Sha256.ofText("TRIGGER common_code.common_code_keep\nTABLE task\n"
                + "FUNCTION whoami(p_author text)\nSCHEMA ledger\nSKIPPED ROLE ledger_owner OTHER_MEMBERS\n"));
    }

    @Test
    @DisplayName("RET-018 a different list (an object more, another order, a role kept for another reason) is a different digest")
    void anotherListIsAnotherDigest() {
        Sha256 base = new LegacyRetirePlan("ledger", OBJECTS, List.of()).digest();

        assertThat(new LegacyRetirePlan("ledger", OBJECTS.reversed(), List.of()).digest()).isNotEqualTo(base);
        assertThat(new LegacyRetirePlan("ledger", OBJECTS.subList(0, 3), List.of()).digest()).isNotEqualTo(base);
        assertThat(new LegacyRetirePlan("ledger", OBJECTS, List.of(
                new LegacyRoleSkip("ledger_owner", LegacyRoleSkip.Reason.RUNNING_ACCOUNT))).digest()).isNotEqualTo(base);
        assertThat(new LegacyRetirePlan("ledger", OBJECTS, List.of()).digest()).isEqualTo(base);
    }
}
