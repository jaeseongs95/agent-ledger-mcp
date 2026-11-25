// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.application.support.HeldTransaction;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.testing.TestOwners;
import org.pgledger.testing.database.LockWaits;

/**
 * R9-D0083: the direction of a relation kind is changed while a relation of that kind is written. The two meet
 * deterministically, with the lock of the database and not the clock: a second connection holds the counter
 * {@code HISTORY}, the relation is written (its row is inserted, its history row waits for the counter), the change of the
 * direction is started, and the second connection commits. Whichever of the two the database lets go first, the relation that
 * is stored must be stored under the direction the code has.
 */
@LedgerServiceTest
class RelationDirectionRaceTest {

    private static CodeCommand relationKind(String kind, String direction) {
        return new CodeCommand("RELATION_KIND", kind, "경쟁", "Race", null, null, 64, null, direction);
    }

    @Test
    @DisplayName("R9-D0083: a relation written while the direction of its kind is changed is never stored under the other direction")
    void aRelationIsNeverStoredUnderTheOtherDirection(Scenario s) throws Exception {
        String kind = "RACE_" + s.code();
        CommonCodeService codes = s.bean(CommonCodeService.class);
        codes.set(relationKind(kind, null), TestOwners.OWNER);
        String one = s.newIssue("one").id();
        String two = s.newIssue("two").id();
        LinkService links = s.bean(LinkService.class);

        HeldTransaction<Integer> holder = HeldTransaction.start(s.ctx(), () -> s.ctx().jdbc()
                .sql("UPDATE id_counter SET issued_no = issued_no WHERE name = 'HISTORY'").update());
        int holderPid = holder.awaitHeld();
        Future<?> link = Parallel.start(() -> links.link(Scenario.AUTHOR,
                new LinkCommand(Scenario.uuid(), kind, two, one, Scenario.REASON, null)));
        LockWaits.awaitWaitingFor(s.ctx().jdbc(), holderPid);
        Future<?> change = Parallel.start(() -> codes.set(relationKind(kind, "GIVEN"), TestOwners.OWNER));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(10)).until(() -> change.isDone()
                || s.ctx().jdbc().sql("SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() "
                        + "AND wait_event_type = 'Lock'").query(Long.class).single() >= 2);
        holder.release();
        failure(link);
        failure(change);

        String direction = s.ctx().jdbc().sql("SELECT direction FROM common_code WHERE code_group = 'RELATION_KIND' "
                + "AND code = :code").param("code", kind).query(String.class).single();
        long stored = s.count("SELECT COUNT(*) FROM issue_relation WHERE kind = ?", kind);
        assertThat("GIVEN".equals(direction) && stored > 0)
                .as("the code is %s and %d relation(s) of it are stored in the order of the other direction", direction, stored)
                .isFalse();
    }

    private static Throwable failure(Future<?> call) throws Exception {
        try {
            call.get(60, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }
}
